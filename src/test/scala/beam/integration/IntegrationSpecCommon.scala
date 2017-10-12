package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.ConfigModule

import scala.util.Try

trait IntegrationSpecCommon {

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall{case (a, b) => cf(a, b)}
  }
}


class StartWithCustomConfig(
                             modeChoice: Option[String] = None,
                             numDriversAsFractionOfPopulation: Option[Double] = None,
                             defaultCostPerMile: Option[Double] = None,
                             defaultCostPerMinute: Option[Double] = None,
                             transitCapacity: Option[Double] = None,
                             transitPrice: Option[Double] = None,
                             tollPrice: Option[Double] = None,
                             rideHailPrice: Option[Double] = None) extends EventsFileHandlingCommon with RunBeam {
  lazy val configFileName = Some(s"${System.getenv("PWD")}/test/input/beamville/beam_50.conf")

  val beamConfig = {

    ConfigModule.ConfigFileName = configFileName

    ConfigModule.beamConfig.copy(
      beam = ConfigModule.beamConfig.beam.copy(
        agentsim = ConfigModule.beamConfig.beam.agentsim.copy(
          agents = ConfigModule.beamConfig.beam.agentsim.agents.copy(
            modalBehaviors = ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.copy(
              modeChoiceClass = modeChoice.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass)
            ), rideHailing = ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.copy(
              defaultCostPerMile = defaultCostPerMile.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMile),
              defaultCostPerMinute = defaultCostPerMinute.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.defaultCostPerMinute),
              numDriversAsFractionOfPopulation = numDriversAsFractionOfPopulation.getOrElse(ConfigModule.beamConfig.beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation)
            )
          ), tuning = ConfigModule.beamConfig.beam.agentsim.tuning.copy(
            transitCapacity = transitCapacity.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.transitCapacity),
            transitPrice = transitPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.transitPrice),
            tollPrice = tollPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.tollPrice),
            rideHailPrice = rideHailPrice.getOrElse(ConfigModule.beamConfig.beam.agentsim.tuning.rideHailPrice)
          )
        ), outputs = ConfigModule.beamConfig.beam.outputs.copy(
          eventsFileOutputFormats = "xml"
        )
      )
    )
  }

  val exec = Try(runBeamWithConfig(beamConfig, ConfigModule.matSimConfig))

  val file: File = getRouteFile(beamConfig.beam.outputs.outputDirectory , beamConfig.beam.outputs.eventsFileOutputFormats)

  val eventsReader: ReadEvents = getEventsReader(beamConfig)

  val listValueTagEventFile = eventsReader.getListTagsFrom(new File(file.getPath),"type=\"ModeChoice\"","mode")

  val groupedCount = listValueTagEventFile
    .groupBy(s => s)
    .map{case (k, v) => (k, v.size)}
}