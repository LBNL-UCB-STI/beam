package beam.integration

import java.io.File

import beam.sim.config.BeamConfig
import org.matsim.core.config.Config

import scala.io.Source

trait EventsFileHandlingCommon {
  def beamConfig: BeamConfig

  def getListIDsWithTag(file: File,
                        tagIgnore: String,
                        positionID: Int): List[String] = {
    var listResult = List[String]()
    for (line <- Source.fromFile(file.getPath).getLines) {
      if (!line.startsWith(tagIgnore)) {
        listResult = line.split(",")(positionID) :: listResult
      }
    }

    return listResult

  }

  def getEventsFilePath(matsimConfig: Config,
                        extension: String,
                        iteration: Int = 0): File = {
    new File(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.events.$extension")
  }
}
