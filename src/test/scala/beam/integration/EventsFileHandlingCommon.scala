package beam.integration

import java.io.File

import beam.sim.config.BeamConfig
import org.matsim.core.config.Config

import scala.io.Source

trait EventsFileHandlingCommon {
  def beamConfig: BeamConfig

  def getListIDsWithTag(file: File, tagIgnore: String, positionID: Int): List[String] = {
    var listResult = List[String]()
    for (line <- Source.fromFile(file.getPath).getLines) {
      if (!line.startsWith(tagIgnore)) {
        listResult = line.split(",")(positionID) :: listResult
      }
    }

    return listResult

  }

  def getListOfSubDirectories(dir: File): String = {
    val simName = beamConfig.beam.agentsim.simulationName
    val prefix = s"${simName}_"
    dir.listFiles
      .filter(s => s.isDirectory && s.getName.startsWith(prefix))
      .map(_.getName)
      .toList
      .sorted
      .reverse
      .head
  }

  def getEventsFilePath(matsimConfig: Config, extension: String, iteration: Int = 0): File = {
    val route = s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.events.$extension"
    new File(route)
  }

  def getRouteFile(route_output: String, extension: String): File = {
    val route = s"$route_output/${getListOfSubDirectories(new File(route_output))}/ITERS/it.0/0.events.$extension"
    new File(route)
  }
}
