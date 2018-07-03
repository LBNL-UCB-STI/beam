package beam.integration

import java.io.File

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}

class StartWithCustomConfig(val config: Config) extends
  EventsFileHandlingCommon with IntegrationSpecCommon with BeamHelper {
  val conf = config.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
  val beamConfig = BeamConfig(conf)

  val (matsimConfig, _) = runBeamWithConfig(conf)

  val file: File = getEventsFilePath(matsimConfig, beamConfig.beam.outputs.events.fileOutputFormats)

  val eventsReader: ReadEvents = new ReadEventsBeam

  val listValueTagEventFile = eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map { case (k, v) => (k, v.size) }
}
