package beam.agentsim.sim

import java.io.File

import beam.agentsim.config.BeamConfig
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

object RunBeam extends App{

  val tsConfig: Config = ConfigFactory.parseFile(new File("src/main/resources/config-template.conf")).resolve();
  val beamConfig = BeamConfig(tsConfig)

  Console.println(beamConfig.beam.outputs.defaultLoggingLevel)
  Console.println(beamConfig.beam.outputs.overrideLoggingLevels.head.classname)
  Console.println(beamConfig.beam.outputs.overrideLoggingLevels.head.value)
  Console.println(beamConfig.beam.outputs.overrideLoggingLevels(1).classname)
  Console.println(if (beamConfig.beam.outputs.explodeEventsIntoFiles) "yes" else "no")

}
