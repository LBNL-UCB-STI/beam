package beam.sim.config

import org.matsim.core.config.{Config => MatsimConfig}

case class BeamExecutionConfig(beamConfig: BeamConfig, matsimConfig: MatsimConfig, outputDirectory: String) {
  import BeamExecutionConfig._

  val executionId: String = executionIdFromDirectory(outputDirectory)

}

object BeamExecutionConfig {

  def executionIdFromDirectory(directory: String): String = {
    directory.split("/").last.replace("_", "").replace("-", "")
  }

}
