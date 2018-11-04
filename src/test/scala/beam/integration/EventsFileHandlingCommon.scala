package beam.integration

import java.io.File

import org.matsim.api.core.v01.events.Event
import org.matsim.core.config.Config
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.immutable.Queue
import scala.io.Source

trait EventsFileHandlingCommon {

  def getEventsFilePath(matsimConfig: Config, extension: String, iteration: Int = 0): File = {
    new File(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.events.$extension"
    )
  }

  def collectEvents(filePath: String): Queue[Event] = {
    var events: Queue[Event] = Queue()
    val handler = new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        events = events :+ event
      }
    }
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(handler)

    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)

    events
  }
}
