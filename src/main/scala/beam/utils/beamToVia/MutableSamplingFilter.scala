package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}

import scala.collection.mutable

trait MutableSamplingFilter {
  def filterAndFix(event: BeamEvent): Seq[BeamEvent]
}

case class MutableVehiclesFilter(
  vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
  vehicleSamplingOtherTypes: Double = 1.0,
  excludedVehicles: Seq[String] = Seq.empty[String]
) extends MutableSamplingFilter {

  private val metVehicles = mutable.Map.empty[String, Boolean]
  private val vehicleTypeSamplesMap = Map(vehicleSampling.map(vs => vs.vehicleType -> vs.percentage): _*)
  private val excludedVehilesId = mutable.HashSet(excludedVehicles: _*)

  private val selectNewVehicleByType: String => Boolean =
    if (vehicleSampling.isEmpty && vehicleSamplingOtherTypes >= 1.0) _ => true
    else if (vehicleSampling.isEmpty) _ => Math.random() <= vehicleSamplingOtherTypes
    else if (vehicleSamplingOtherTypes >= 1.0) { vehicleType =>
      vehicleTypeSamplesMap.get(vehicleType) match {
        case Some(percentage) => Math.random() <= percentage
        case None             => true
      }
    } else { vehicleType =>
      vehicleTypeSamplesMap.get(vehicleType) match {
        case Some(percentage) => Math.random() <= percentage
        case None             => Math.random() <= vehicleSamplingOtherTypes
      }
    }

  def vehicleSelected(vId: String, vType: String): Boolean =
    !excludedVehilesId.contains(vId) && (metVehicles.get(vId) match {
      case Some(decision) => decision
      case None =>
        val decision = selectNewVehicleByType(vType)
        metVehicles(vId) = decision
        decision
    })

  val empty = Seq.empty[BeamEvent]

  override def filterAndFix(event: BeamEvent): Seq[BeamEvent] = event match {
    case pte: BeamPathTraversal =>
      metVehicles.get(pte.vehicleId) match {
        case Some(true) => Seq(pte)
        case None =>
          val decision = vehicleSelected(pte.vehicleId, pte.vehicleType)
          if (decision) Seq(pte)
          else empty
        case _ => empty
      }

    case plv: BeamPersonLeavesVehicle =>
      metVehicles.get(plv.vehicleId) match {
        case Some(true) => Seq(plv)
        case _          => empty
      }

    case _ => empty
  }
}

case class MutablePopulationFilter(
  populationSampling: Seq[PopulationSample] = Seq.empty[PopulationSample],
) extends MutableSamplingFilter {

  private val metPersons = mutable.Map.empty[String, Boolean]
  private val vehiclePassengers = mutable.Map.empty[String, Int]
  private val personToVehicle = mutable.Map.empty[String, Option[String]]

  private val selectNewPersonById: String => Boolean =
    if (populationSampling.isEmpty) _ => true
    else
      id => {
        val rule = populationSampling.collectFirst {
          case rule: PopulationSample if rule.personIsInteresting(id) => rule
        }
        rule match {
          case Some(r) => Math.random() <= r.percentage
          case _       => false
        }
      }

  override def toString: String =
    metPersons.foldLeft(0) {
      case (size, (_, true)) => size + 1
      case (size, _)         => size
    } + " persons in population"

  private def personSelected(id: String): Boolean = metPersons.get(id) match {
    case Some(decision) => decision
    case None =>
      val decision = selectNewPersonById(id)
      metPersons(id) = decision
      decision
  }

  val empty = Seq.empty[BeamEvent]

  private def passengerAdd(vehicleId: String): Unit = vehiclePassengers.get(vehicleId) match {
    case Some(passengers) => vehiclePassengers(vehicleId) = passengers + 1
    case None             => vehiclePassengers(vehicleId) = 1
  }

  private def passengerRem(vehicleId: String): Unit = vehiclePassengers.get(vehicleId) match {
    case Some(passengers) if passengers >= 1 => vehiclePassengers(vehicleId) = passengers - 1
    case _                                   => vehiclePassengers(vehicleId) = 0
  }

  override def filterAndFix(event: BeamEvent): Seq[BeamEvent] = event match {
    case pev: BeamPersonEntersVehicle =>
      if (personSelected(pev.personId)) {
        val ple = personToVehicle.get(pev.personId) match {
          case Some(Some(prevVehicleId)) => Some(BeamPersonLeavesVehicle(pev.time, pev.personId, prevVehicleId))
          case _                         => None
        }

        personToVehicle(pev.personId) = Some(pev.vehicleId)
        passengerAdd(pev.vehicleId)

        ple match {
          case Some(e) => Seq(e, pev)
          case _       => Seq(pev)
        }

      } else empty

    case plv: BeamPersonLeavesVehicle =>
      if (personSelected(plv.personId)) {
        personToVehicle(plv.personId) = None

        passengerRem(plv.vehicleId)
        Seq(plv)
      } else empty

    case pte: BeamPathTraversal =>
      val driverSelected = personSelected(pte.driverId)
      if (driverSelected) {
        personToVehicle(pte.driverId) = Some(pte.vehicleId)
        Seq(pte)
      } else
        vehiclePassengers.get(pte.vehicleId) match {
          case Some(passengers) if passengers > 0 => Seq(pte)
          case _                                  => empty
        }

    case _ => empty
  }
}
