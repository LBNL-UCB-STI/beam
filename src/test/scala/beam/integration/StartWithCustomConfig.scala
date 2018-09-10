package beam.integration

import java.io.File

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}

class StartWithCustomConfig(val config: Config)
    extends EventsFileHandlingCommon
    with IntegrationSpecCommon
    with BeamHelper {

  lazy val conf =
    config.withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
  lazy val beamConfig = BeamConfig(conf)

  lazy val (matsimConfig, _) = runBeamWithConfig(conf)

  lazy val file: File = getEventsFilePath(matsimConfig, beamConfig.beam.outputs.events.fileOutputFormats)

  lazy val eventsReader: ReadEvents = new ReadEventsBeam

  lazy val listValueTagEventFile =
    eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  lazy val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map { case (k, v) => (k, v.size) }
}
