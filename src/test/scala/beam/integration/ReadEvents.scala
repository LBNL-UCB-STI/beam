package beam.integration

import java.io.File

import org.matsim.api.core.v01.events.Event
import org.matsim.core.config.Config
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

object ReadEvents {

  def fromFile(filePath: String): Traversable[Event] =
    new Traversable[Event] {
      override def foreach[U](f: Event => U): Unit = {
        val eventsManager = EventsUtils.createEventsManager()
        eventsManager.addHandler(new BasicEventHandler {
          def handleEvent(event: Event): Unit = f(event)
        })
        new MatsimEventsReader(eventsManager).readFile(filePath)
      }
    }

  def getEventsFilePath(matsimConfig: Config, extension: String, iteration: Int = 0): File = {
    new File(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.events.$extension"
    )
  }

}
