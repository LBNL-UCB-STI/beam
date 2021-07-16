package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.{BeamEvent, BeamPathTraversal}

import scala.collection.mutable

object MutableVehiclesFilter {

  trait SelectNewVehicle {
    def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean
    def fitIn(chance: Double): Boolean = Math.random() <= chance
  }

  def apply(selectNewVehicle: SelectNewVehicle): MutableVehiclesFilter = new MutableVehiclesFilter(selectNewVehicle)

  def apply(
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehicleSamplingOtherTypes: Double = 1.0
  ): MutableVehiclesFilter = {
    val vehicleTypeSamplesMap = Map(vehicleSampling.map(vs => vs.vehicleType -> vs.percentage): _*)

    val selectNewVehicleByIdType: (String, String) => Boolean =
      if (vehicleSampling.isEmpty && vehicleSamplingOtherTypes >= 1.0) (_, _) => true
      else if (vehicleSampling.isEmpty) (_, _) => Math.random() <= vehicleSamplingOtherTypes
      else if (vehicleSamplingOtherTypes >= 1.0) { (_, vehicleType) =>
        vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => true
        }
      } else { (_, vehicleType) =>
        vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => Math.random() <= vehicleSamplingOtherTypes
        }
      }

    object SelectNewVehicle1 extends SelectNewVehicle {
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectNewVehicleByIdType(vehicleId, vehicleType)
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }

  def withListOfExclude(
    excludedVehicles: mutable.HashSet[String],
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehicleSamplingOtherTypes: Double = 1.0
  ): MutableVehiclesFilter = {
    val vehicleTypeSamplesMap = Map(vehicleSampling.map(vs => vs.vehicleType -> vs.percentage): _*)

    val selectNewVehicleByIdType: (String, String) => Boolean =
      if (vehicleSampling.isEmpty && vehicleSamplingOtherTypes >= 1.0) (_, _) => true
      else if (vehicleSampling.isEmpty) (_, _) => Math.random() <= vehicleSamplingOtherTypes
      else if (vehicleSamplingOtherTypes >= 1.0) { (vId, vehicleType) =>
        !excludedVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => true
        })
      } else { (vId, vehicleType) =>
        !excludedVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => Math.random() <= vehicleSamplingOtherTypes
        })
      }

    object SelectNewVehicle1 extends SelectNewVehicle {
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectNewVehicleByIdType(vehicleId, vehicleType)
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }

  def withListOfInclude(
    preSelectVehicles: mutable.HashSet[String],
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehicleSamplingOtherTypes: Double = 1.0
  ): MutableVehiclesFilter = {
    val vehicleTypeSamplesMap = Map(vehicleSampling.map(vs => vs.vehicleType -> vs.percentage): _*)

    val selectNewVehicleByIdType: (String, String) => Boolean =
      if (vehicleSampling.isEmpty && vehicleSamplingOtherTypes >= 1.0) (vId, _) => preSelectVehicles.contains(vId)
      else if (vehicleSampling.isEmpty)
        (vId, _) => preSelectVehicles.contains(vId) && Math.random() <= vehicleSamplingOtherTypes
      else if (vehicleSamplingOtherTypes >= 1.0) { (vId, vehicleType) =>
        preSelectVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => true
        })
      } else { (vId, vehicleType) =>
        preSelectVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => Math.random() <= vehicleSamplingOtherTypes
        })
      }

    object SelectNewVehicle1 extends SelectNewVehicle {
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectNewVehicleByIdType(vehicleId, vehicleType)
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }

  def withListOfIncludeAndNecessary(
    preSelectVehicles: mutable.HashSet[String],
    necessaryVehicles: mutable.HashSet[String],
    vehicleSampling: Seq[VehicleSample] = Seq.empty[VehicleSample],
    vehicleSamplingOtherTypes: Double = 1.0
  ): MutableVehiclesFilter = {
    val vehicleTypeSamplesMap = Map(vehicleSampling.map(vs => vs.vehicleType -> vs.percentage): _*)

    val selectNewVehicleByIdType: (String, String) => Boolean =
      if (vehicleSampling.isEmpty && vehicleSamplingOtherTypes >= 1.0)
        (vId, _) => necessaryVehicles.contains(vId) || preSelectVehicles.contains(vId)
      else if (vehicleSampling.isEmpty)
        (vId, _) =>
          necessaryVehicles.contains(vId) || (preSelectVehicles.contains(vId) && Math
            .random() <= vehicleSamplingOtherTypes)
      else if (vehicleSamplingOtherTypes >= 1.0) { (vId, vehicleType) =>
        necessaryVehicles
          .contains(vId) || (preSelectVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => true
        }))
      } else { (vId, vehicleType) =>
        necessaryVehicles
          .contains(vId) || (preSelectVehicles.contains(vId) && (vehicleTypeSamplesMap.get(vehicleType) match {
          case Some(percentage) => Math.random() <= percentage
          case None             => Math.random() <= vehicleSamplingOtherTypes
        }))
      }

    object SelectNewVehicle1 extends SelectNewVehicle {
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectNewVehicleByIdType(vehicleId, vehicleType)
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }

  def withListOfVehicleModes(vehicleModes: Seq[String], sampling: Double): MutableVehiclesFilter = {
    object SelectNewVehicle1 extends SelectNewVehicle {
      val selectedModes: mutable.HashSet[String] = mutable.HashSet(vehicleModes.map(_.toLowerCase): _*)
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectedModes.contains(vehicleMode.toLowerCase) && Math.random() <= sampling
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }

  def withListOfVehicleModesAndSelectedIds(
    selectedIds: mutable.HashSet[String],
    vehicleModes: Seq[String],
    sampling: Double
  ): MutableVehiclesFilter = {
    object SelectNewVehicle1 extends SelectNewVehicle {
      val selectedModes: mutable.HashSet[String] = mutable.HashSet(vehicleModes.map(_.toLowerCase): _*)
      override def select(vehicleMode: String, vehicleType: String, vehicleId: String): Boolean =
        selectedIds.contains(vehicleId) && selectedModes.contains(vehicleMode.toLowerCase) && Math.random() <= sampling
    }

    MutableVehiclesFilter(SelectNewVehicle1)
  }
}

class MutableVehiclesFilter(selectNewVehicle: MutableVehiclesFilter.SelectNewVehicle) extends MutableSamplingFilter {

  private val metVehicles = mutable.Map.empty[String, Boolean]
  private val vehicleTrips = mutable.Map.empty[String, VehicleTrip]

  def vehicleSelected(vId: String, vType: String, vMode: String): Boolean = metVehicles.get(vId) match {
    case Some(decision) => decision
    case None =>
      val decision = selectNewVehicle.select(vMode, vType, vId)
      metVehicles(vId) = decision
      decision
  }

  def addVehiclePTE(pte: BeamPathTraversal): Unit = {
    vehicleTrips.get(pte.vehicleId) match {
      case Some(trip) => trip.trip += pte
      case None       => vehicleTrips(pte.vehicleId) = VehicleTrip(pte)
    }
  }

  override def filter(event: BeamEvent): Unit = event match {
    case pte: BeamPathTraversal =>
      metVehicles.get(pte.vehicleId) match {
        case Some(true) => addVehiclePTE(pte)
        case None =>
          val decision = vehicleSelected(pte.vehicleId, pte.vehicleType, pte.mode)
          if (decision) addVehiclePTE(pte)
        case _ =>
      }

    case _ =>
  }

  override def vehiclesTrips: Traversable[VehicleTrip] = vehicleTrips.values

  override def personsTrips: Traversable[PersonTrip] = Seq.empty[PersonTrip]

  override def personsEvents: Traversable[PersonEvents] = Seq.empty[PersonEvents]
}
