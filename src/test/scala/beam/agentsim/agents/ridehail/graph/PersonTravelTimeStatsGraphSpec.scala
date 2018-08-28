package beam.agentsim.agents.ridehail.graph
import beam.analysis.plots.PersonTravelTimeStats
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

object PersonTravelTimeStatsGraphSpec {

  class PersonTravelTimeStatsGraph(comp: PersonTravelTimeStats.PersonTravelTimeComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val personTravelTimeStats =
      new PersonTravelTimeStats(comp)

    override def reset(iteration: Int): Unit = {
      personTravelTimeStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE) =>
          personTravelTimeStats.processStats(event)
        case evn if evn.getEventType.equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE) =>
          personTravelTimeStats.processStats(event)
        case evn: PersonArrivalEvent =>
          personTravelTimeStats.processStats(evn)
        case evn: PersonDepartureEvent =>
          personTravelTimeStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      personTravelTimeStats.createGraph(event)
    }
  }
}

class PersonTravelTimeStatsGraphSpec {}
