package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events._

import scala.collection.mutable

object MutablePopulationFilter {

  def apply(
    populationSampling: Seq[PopulationSample] = Seq.empty[PopulationSample]
  ): MutablePopulationFilter = {

    val selectNewPersonById: String => Boolean =
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

    new MutablePopulationFilter(selectNewPersonById)
  }
}

case class MutablePopulationFilter(selectNewPersonById: String => Boolean) extends MutableSamplingFilter {

  private val metPersons = mutable.Map.empty[String, Boolean]
  private val vehicleToPassengers = mutable.Map.empty[String, mutable.HashSet[String]]

  private val vehiclesTripsMap = mutable.Map.empty[String, VehicleTrip]
  private val personsTripsMap = mutable.Map.empty[String, PersonTrip]
  private val personsEventsMap = mutable.Map.empty[String, PersonEvents]

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

  private def addPersonEvent(personId: String, event: BeamEvent): Unit = {
    personsEventsMap.get(personId) match {
      case Some(pe) => pe.events += event
      case None     => personsEventsMap(personId) = PersonEvents(personId, event)
    }
  }

  override def filter(event: BeamEvent): Unit = event match {
    case pev: BeamPersonEntersVehicle if personSelected(pev.personId) =>
      vehicleToPassengers.get(pev.vehicleId) match {
        case Some(passengers) => passengers += pev.personId
        case None             => vehicleToPassengers(pev.vehicleId) = mutable.HashSet(pev.personId)
      }

    case plv: BeamPersonLeavesVehicle if personSelected(plv.personId) =>
      vehicleToPassengers.get(plv.vehicleId) match {
        case Some(passengers) => passengers -= plv.personId
        case None             =>
      }

    case mChoice: BeamModeChoice if personSelected(mChoice.personId)      => addPersonEvent(mChoice.personId, mChoice)
    case actend: BeamActivityEnd if personSelected(actend.personId)       => addPersonEvent(actend.personId, actend)
    case actstart: BeamActivityStart if personSelected(actstart.personId) => addPersonEvent(actstart.personId, actstart)

    case pte: BeamPathTraversal =>
      val driver =
        if (personSelected(pte.driverId)) Seq(pte.driverId)
        else Seq.empty[String]

      val persons = vehicleToPassengers.get(pte.vehicleId) match {
        case Some(passengers) => passengers ++ driver
        case None             => driver
      }

      if (persons.nonEmpty) {
        persons.foreach(p =>
          personsTripsMap.get(p) match {
            case Some(trip) => trip.trip += pte
            case None       => personsTripsMap(p) = PersonTrip(p, pte)
          }
        )

        vehiclesTripsMap.get(pte.vehicleId) match {
          case Some(trip) => trip.trip += pte
          case None       => vehiclesTripsMap(pte.vehicleId) = VehicleTrip(pte)
        }
      }

    case _ =>
  }

  override def vehiclesTrips: Traversable[VehicleTrip] = vehiclesTripsMap.values

  override def personsTrips: Traversable[PersonTrip] = personsTripsMap.values

  override def personsEvents: Traversable[PersonEvents] = personsEventsMap.values

}
