package beam.agentsim.agents.ridehail.graph
import beam.analysis.plots.GraphsStatsAgentSimEventsListener
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler

trait EventAnalyzer {
  def eventFile(iteration: Int): Unit

  protected def parseEventFile(iteration: Int, handler: BasicEventHandler): Unit = {
    val eventsFilePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO
      .getIterationFilename(iteration, "events.xml")
    val eventManager = EventsUtils.createEventsManager()
    eventManager.addHandler(handler)
    val reader = new MatsimEventsReader(eventManager)
    reader.readFile(eventsFilePath)
  }
}
