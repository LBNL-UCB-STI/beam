package beam.agentsim.agents.ridehail.graph
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.FuelUsageStats
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

object FuelUsageStatsGraphSpec {

  class FuelUsageStatsGraph(compute: FuelUsageStats.FuelUsageStatsComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val fuelUsageStats =
      new FuelUsageStats(compute)

    override def reset(iteration: Int): Unit = {
      fuelUsageStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE) =>
          fuelUsageStats.processStats(event)
        case evn: PathTraversalEvent =>
          fuelUsageStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      fuelUsageStats.createGraph(event)
    }
  }
}

class FuelUsageStatsGraphSpec {}
