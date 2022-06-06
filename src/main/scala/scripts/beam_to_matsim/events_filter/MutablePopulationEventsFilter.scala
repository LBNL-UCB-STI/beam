package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.{BeamActivityEnd, BeamActivityStart, BeamEvent, BeamModeChoice}

import scala.collection.mutable

object MutablePopulationEventsFilter {

  def apply(
    populationSampling: Seq[PopulationSample] = Seq.empty[PopulationSample]
  ): MutablePopulationEventsFilter = {

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

    new MutablePopulationEventsFilter(selectNewPersonById)
  }
}

case class MutablePopulationEventsFilter(selectNewPersonById: String => Boolean) extends MutableSamplingFilter {

  private val metPersons = mutable.Map.empty[String, Boolean]
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
    case mChoice: BeamModeChoice if personSelected(mChoice.personId)      => addPersonEvent(mChoice.personId, mChoice)
    case actend: BeamActivityEnd if personSelected(actend.personId)       => addPersonEvent(actend.personId, actend)
    case actstart: BeamActivityStart if personSelected(actstart.personId) => addPersonEvent(actstart.personId, actstart)

    case _ =>
  }

  override def vehiclesTrips: Traversable[VehicleTrip] = Seq.empty[VehicleTrip]

  override def personsTrips: Traversable[PersonTrip] = Seq.empty[PersonTrip]

  override def personsEvents: Traversable[PersonEvents] = personsEventsMap.values

}
