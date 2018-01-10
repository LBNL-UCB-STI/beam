package beam.integration

import java.io.File

import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig}
import com.typesafe.config.Config

class StartWithCustomConfig(val config: Config) extends
  EventsFileHandlingCommon with IntegrationSpecCommon with BeamHelper {

  val beamConfig = BeamConfig(config)

  val matsimConfig: org.matsim.core.config.Config = runBeamWithConfig(config)

  val file: File = getEventsFilePath(matsimConfig, beamConfig.beam.outputs.events.fileOutputFormats)

  val eventsReader: ReadEvents = new ReadEventsBeam

  val listValueTagEventFile = eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map{case (k, v) => (k, v.size)}
}
