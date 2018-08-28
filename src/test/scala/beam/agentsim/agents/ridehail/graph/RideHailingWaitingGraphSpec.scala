package beam.agentsim.agents.ridehail.graph
import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.RideHailWaitingStats
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

object RideHailingWaitingGraphSpec {

  class RideHailingWaitingGraph(waitingComp: RideHailWaitingStats.WaitingStatsComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val railHailingStat =
      new RideHailWaitingStats(waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || event.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingStat.processStats(event)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingStat.createGraph(event)
    }
  }
}

class RideHailingWaitingGraphSpec {}
