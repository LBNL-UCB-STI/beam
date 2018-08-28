package beam.agentsim.agents.ridehail.graph
import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.RideHailingWaitingSingleStats
import beam.integration.IntegrationSpecCommon
import beam.sim.{BeamHelper, BeamServices}
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{Matchers, WordSpecLike}

object RideHailingWaitingSingleStatsSpec {

  class RideHailingWaitingSingleGraph(
    beamServices: BeamServices,
    waitingComp: RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation
  ) extends BasicEventHandler
      with IterationEndsListener {

    private val railHailingSingleStat =
      new RideHailingWaitingSingleStats(beamServices.beamConfig, waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingSingleStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || event.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingSingleStat.processStats(event)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingSingleStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingSingleStat.createGraph(event)
    }
  }
}

class RideHailingWaitingSingleStatsSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {}
