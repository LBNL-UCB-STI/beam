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

}
