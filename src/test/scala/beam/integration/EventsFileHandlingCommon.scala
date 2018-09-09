package beam.integration

import java.io.File

import org.matsim.api.core.v01.events.Event
import org.matsim.core.config.Config
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.immutable.Queue
import scala.io.Source

trait EventsFileHandlingCommon {

  def getListIDsWithTag(file: File, tagIgnore: String, positionID: Int): List[String] = {
    var listResult = List[String]()
    for (line <- Source.fromFile(file.getPath).getLines) {
      if (!line.startsWith(tagIgnore)) {
        listResult = line.split(",")(positionID) :: listResult
      }
    }

    return listResult

  }

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
