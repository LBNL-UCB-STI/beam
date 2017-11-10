package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.ConfigModule

import scala.util.Try

class StartWithCustomConfig(
                             modeChoice: Option[String] = None,
                             numDriversAsFractionOfPopulation: Option[Double] = None,
                             defaultCostPerMile: Option[Double] = None,
                             defaultCostPerMinute: Option[Double] = None,
                             transitCapacity: Option[Double] = None,
                             transitPrice: Option[Double] = None,
                             tollPrice: Option[Double] = None,
                             rideHailPrice: Option[Double] = None) extends
  EventsFileHandlingCommon with IntegrationSpecCommon with RunBeam {
  lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam.conf")

  val beamConfig = customBeam(configFileName, modeChoice, numDriversAsFractionOfPopulation,
  defaultCostPerMile,defaultCostPerMinute,transitCapacity,transitPrice,tollPrice,rideHailPrice)

  val exec = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))

  val file: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , beamConfig.beam.outputs.events.fileOutputFormats)

  val eventsReader: ReadEvents = new ReadEventsBeam

  val listValueTagEventFile = eventsReader.getListTagsFrom(file.getPath, tagToReturn = "mode", eventType = Some("ModeChoice"))

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map{case (k, v) => (k, v.size)}
}
