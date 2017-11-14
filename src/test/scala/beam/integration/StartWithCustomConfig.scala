package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.{BeamConfig, ConfigModule}
import com.typesafe.config.Config

class StartWithCustomConfig(val config: Config) extends
  EventsFileHandlingCommon with IntegrationSpecCommon with RunBeam {

  val beamConfig = BeamConfig(config)

  runBeamWithConfig(config, ConfigModule.matSimConfig(config))

  val file: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , beamConfig.beam.outputs.events.fileOutputFormats)

  val eventsReader: ReadEvents = new ReadEventsBeam

  val listValueTagEventFile = eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map{case (k, v) => (k, v.size)}
}
