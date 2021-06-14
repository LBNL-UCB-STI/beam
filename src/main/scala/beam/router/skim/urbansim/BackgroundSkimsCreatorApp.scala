package beam.router.skim.urbansim

import akka.actor.ActorSystem
import beam.router.{FreeFlowTravelTime, LinkTravelTimeContainer}
import beam.router.Modes.BeamMode
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim._
import beam.router.skim.core.{AbstractSkimmer, ODSkimmer}
import beam.sim.config.BeamExecutionConfig
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.{DateUtils, ProfilingUtils}
import beam.utils.csv.GenericCsvReader
import com.google.inject.Injector
import org.matsim.core.scenario.MutableScenario
import scopt.OParser

import java.io.{BufferedWriter, Closeable, File}
import java.nio.file.Path
import scala.util.control.NonFatal

case class InputParameters(
  configPath: Path = null,
  input: Path = null,
  output: Path = null,
  linkstatsPath: Option[Path] = None,
  parallelism: Int = 1
)

case class CsvInputRow(origin: String, destination: String, mode: String)

case class ODRow(origin: GeoUnit.TAZ, destination: GeoUnit.TAZ, mode: String)

/*
Example of parameters usage:
 --configPath test/input/beamville/beam.conf
 --input test/input/beamville/input.csv
 --output test/input/beamville/output.csv
 --linkstatsPath test/input/beamville/linkstats.csv.gz
 --parallelism 2
 */
object BackgroundSkimsCreatorApp extends App with BeamHelper {
  private val parser = {
    val builder = OParser.builder[InputParameters]
    import builder._

    def fileValidator(file: File): Either[String, Unit] =
      if (file.isFile) success
      else failure(s"$file does not exist")

    OParser.sequence(
      programName("BackgroundSkimsCreator"),
      opt[File]("configPath")
        .required()
        .validate(fileValidator)
        .action((x, c) => c.copy(configPath = x.toPath))
        .text("Beam config path"),
      opt[File]("input")
        .required()
        .validate(fileValidator)
        .action((x, c) => c.copy(input = x.toPath))
        .text("input csv file path"),
      opt[File]("output").required().action((x, c) => c.copy(output = x.toPath)).text("output csv file path"),
      opt[File]("linkstatsPath")
        .validate(fileValidator)
        .action((x, c) => c.copy(linkstatsPath = Some(x.toPath)))
        .text("linkstats file path in csv.gz format"),
      opt[Int]("parallelism").action((x, c) => c.copy(parallelism = x)).text("Parallelism level")
    )
  }

  def toCsvRow(rec: java.util.Map[String, String]): CsvInputRow =
    CsvInputRow(rec.get("origin"), rec.get("destination"), rec.get("mode"))

  private def readCsv(csvPath: String): Vector[CsvInputRow] = {
    val (iter: Iterator[CsvInputRow], toClose: Closeable) =
      GenericCsvReader.readAs[CsvInputRow](csvPath, toCsvRow, _ => true)
    try {
      iter.toVector
    } finally {
      toClose.close()
    }
  }

  OParser.parse(parser, args, InputParameters()) match {
    case Some(params) =>
      val manualArgs = Array[String]("--config", params.configPath.toString)
      val (_, config) = prepareConfig(manualArgs, isConfigArgRequired = true)

      val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)

      val (scenarioBuilt, beamScenario) = buildBeamServicesAndScenario(
        beamExecutionConfig.beamConfig,
        beamExecutionConfig.matsimConfig
      )
      val scenario: MutableScenario = scenarioBuilt
      val injector: Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
      val beamServices: BeamServices = buildBeamServices(injector, scenario)
      val clustering: TAZClustering = new TAZClustering(beamScenario.tazTreeMap)
      val timeBinSizeInSeconds = beamExecutionConfig.beamConfig.beam.agentsim.timeBinSize
      val maxHour = DateUtils.getMaxHour(beamExecutionConfig.beamConfig)
      val travelTime = params.linkstatsPath match {
        case Some(path) => new LinkTravelTimeContainer(path.toString, timeBinSizeInSeconds, maxHour)
        case None       => new FreeFlowTravelTime
      }

      val tazMap: Map[String, GeoUnit.TAZ] = clustering.tazTreeMap.getTAZs
        .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
        .groupBy(_.id)
        .mapValues(_.head)

      val odRows = readCsv(params.input.toString).map(od => ODRow(tazMap(od.origin), tazMap(od.destination), od.mode))

      val skimmer = createSkimmer(beamServices, odRows)

      implicit val actorSystem = ActorSystem()
      implicit val ec = actorSystem.dispatcher

      val skimsCreator = new BackgroundSkimsCreator(
        beamServices = beamServices,
        beamScenario = beamScenario,
        geoClustering = clustering,
        abstractSkimmer = skimmer,
        travelTime = travelTime,
        beamModes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectWalkRoute = false,
        buildDirectCarRoute = true,
        calculationTimeoutHours = 1
      )

      logger.info("Parallelism " + params.parallelism)
      skimsCreator.increaseParallelismTo(params.parallelism)
      skimsCreator.start()

      skimsCreator.getResult
        .map(skimmer => {
          logger.info("Got populated skimmer")
          skimmer.abstractSkimmer.writeToDisk(params.output.toString)
          None
        })
        .andThen {
          case _ =>
            logger.info("Stopping skimsCreator")
            skimsCreator.stop()
            logger.info("Terminating actorSystem")
            actorSystem.terminate().andThen {
              case _ =>
                logger.info("actorSystem terminated")
                System.exit(0)
            }
        }
    case _ =>
      logger.error("Could not process parameters")
  }

  def createSkimmer(
    beamServices: BeamServices,
    odRows: Vector[ODRow]
  ): AbstractSkimmer = {
    beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind match {
      case "od"          => createOdSkimmer(beamServices, odRows)
      case "activitySim" => createActivitySimSkimmer(beamServices, odRows)
      case skimsKind =>
        throw new IllegalArgumentException(
          s"Unexpected skims kind ($skimsKind)"
        )
    }
  }

  def createActivitySimSkimmer(beamServices: BeamServices, odRows: Vector[ODRow]): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(ExcerptData.csvHeader)
            writer.write("\n")

            ProfilingUtils.timed("Writing skims for time periods for all pathTypes", x => logger.info(x)) {
              odRows.foreach {
                case ODRow(origin, destination, mode) =>
                  val pathType = ActivitySimPathType.allPathTypes.find(_.toString == mode).get
                  writeSkimRow(writer, origin, destination, pathType)
              }
            }
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Could not write skim in '$filePath': ${ex.getMessage}", ex)
          } finally {
            if (null != writer)
              writer.close()
          }

          logger.info(s"Written UrbanSim peak skims to $filePath")
        }
      }
    }

  def createOdSkimmer(beamServices: BeamServices, odRows: Vector[ODRow]): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      lazy val hours = BackgroundSkimsCreator.getPeakHoursFromConfig(beamServices)
      lazy val uniqueTimeBins: Seq[Int] = hours.map(math.round(_).toInt)

      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(skimFileHeader + "\n")

            odRows.foreach {
              case ODRow(origin, destination, mode) =>
                val beamMode = BeamMode.withValue(mode)
                writeSkimRow(writer, uniqueTimeBins, origin, destination, beamMode)
            }
          } catch {
            case NonFatal(ex) =>
              logger.error(s"Could not write skim in '$filePath': ${ex.getMessage}", ex)
          } finally {
            if (null != writer)
              writer.close()
          }
          logger.info(s"Written UrbanSim peak skims for hours $hours to $filePath")
        }
      }
    }
}
