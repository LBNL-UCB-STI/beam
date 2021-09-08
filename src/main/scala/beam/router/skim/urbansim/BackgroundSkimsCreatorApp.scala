package beam.router.skim.urbansim

import akka.actor.{ActorSystem, Terminated}
import beam.agentsim.infrastructure.geozone.{GeoIndex, TAZIndex}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim._
import beam.router.skim.core.{AbstractSkimmer, ODSkimmer}
import beam.router.{FreeFlowTravelTime, LinkTravelTimeContainer}
import beam.sim.config.BeamExecutionConfig
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.csv.GenericCsvReader
import beam.utils.{DateUtils, ProfilingUtils}
import com.google.inject.Injector
import org.matsim.core.scenario.MutableScenario
import scopt.OParser

import java.io.{BufferedWriter, Closeable, File}
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

case class InputParameters(
  configPath: Path = null,
  input: Option[Path] = None,
  output: Path = null,
  linkstatsPath: Option[Path] = None,
  ODSkimsPath: Option[Path] = None,
  parallelism: Int = 1
)

case class ODRow(origin: GeoUnit.TAZ, destination: GeoUnit.TAZ)

/*
Example of parameters usage:
 --configPath test/input/beamville/beam.conf
 --input test/input/beamville/input.csv
 --output test/input/beamville/output.csv
 --linkstatsPath test/input/beamville/linkstats.csv
 --ODSkimsPath test/input/beamville/odskims.csv
 --parallelism 2

Note that all csv files can automatically use gzip compression if specified with `csv.gz` extension
for example "--input test/input/beamville/input.csv.gz"

 Run with gradle:
 ./gradlew execute -PmainClass=beam.router.skim.urbansim.BackgroundSkimsCreatorApp -PappArgs=["'--configPath', 'test/input/beamville/beam-with-fullActivitySimBackgroundSkims.conf', '--output', 'output.csv', '--input', 'input.csv', '--ODSkimsPath', 'ODSkimsBeamville.csv',  '--linkstatsPath', '0.linkstats.csv'"]
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
        .validate(fileValidator)
        .action((x, c) => c.copy(input = Some(x.toPath)))
        .text("input csv file path"),
      opt[File]("output").required().action((x, c) => c.copy(output = x.toPath)).text("output csv file path"),
      opt[File]("linkstatsPath")
        .validate(fileValidator)
        .action((x, c) => c.copy(linkstatsPath = Some(x.toPath)))
        .text("linkstats file path in csv.gz format"),
      opt[File]("ODSkimsPath")
        .validate(fileValidator)
        .action((x, c) => c.copy(ODSkimsPath = Some(x.toPath)))
        .text("OD Skims file path"),
      opt[Int]("parallelism").action((x, c) => c.copy(parallelism = x)).text("Parallelism level")
    )
  }

  def toCsvRow(rec: java.util.Map[String, String], tazMap: Map[String, GeoUnit.TAZ]): ODRow =
    ODRow(tazMap(rec.get("origin")), tazMap(rec.get("destination")))

  def toCsvSkimRow(rec: java.util.Map[String, String]): ExcerptData =
    ExcerptData(
      timePeriodString = rec.get("timePeriod"),
      pathType = ActivitySimPathType.fromString(rec.get("pathType")).getOrElse(ActivitySimPathType.DRV_COM_WLK),
      originId = rec.get("origin"),
      destinationId = rec.get("destination"),
      weightedGeneralizedTime = rec.get("TIME_minutes").toDouble,
      weightedGeneralizedCost = rec.get("VTOLL_FAR").toDouble,
      weightedDistance = rec.get("DIST_meters").toDouble,
      weightedWalkAccess = rec.get("WACC_minutes").toDouble,
      weightedWalkEgress = rec.get("WEGR_minutes").toDouble,
      weightedWalkAuxiliary = rec.get("WAUX_minutes").toDouble,
      weightedTotalInVehicleTime = rec.get("TOTIVT_IVT_minutes").toDouble,
      weightedDriveTimeInMinutes = rec.get("DTIM_minutes").toDouble,
      weightedDriveDistanceInMeters = rec.get("DDIST_meters").toDouble,
      weightedFerryInVehicleTimeInMinutes = rec.get("FERRYIVT_minutes").toDouble,
      weightedLightRailInVehicleTimeInMinutes = rec.get("KEYIVT_minutes").toDouble,
      weightedTransitBoardingsCount = rec.get("BOARDS").toDouble,
      weightedCost = Option(rec.get("WeightedCost")).map(_.toDouble).getOrElse(0.0d),
      debugText = rec.get("DEBUG_TEXT")
    )

  private def readInputCsv(csvPath: String, tazMap: Map[String, GeoUnit.TAZ]): Vector[ODRow] = {
    val (iter: Iterator[ODRow], toClose: Closeable) =
      GenericCsvReader.readAs[ODRow](csvPath, toCsvRow(_, tazMap), _ => true)
    try {
      iter.toVector
    } finally {
      toClose.close()
    }
  }

  private def readSkimsCsv(csvPath: String): Vector[ExcerptData] = {
    val (iter: Iterator[ExcerptData], toClose: Closeable) =
      GenericCsvReader.readAs[ExcerptData](csvPath, toCsvSkimRow, _.weightedGeneralizedTime > 0)
    try {
      iter.toVector
    } finally {
      toClose.close()
    }
  }

  private def tazUnitToTAZ(taz: GeoUnit.TAZ): TAZ = new TAZ(taz.id, taz.center, taz.areaInSquareMeters)

  OParser.parse(parser, args, InputParameters()) match {
    case Some(params) =>
      implicit val ec = ExecutionContext.global
      try {
        runWithParams(params).andThen { case _ =>
          System.exit(0)
        }
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          System.exit(-1)
      }
    case _ =>
      logger.error("Could not process parameters")
  }

  def runWithParams(params: InputParameters): Future[Terminated] = {
    val manualArgs = Array[String]("--config", params.configPath.toString)
    val (_, config) = prepareConfig(manualArgs, isConfigArgRequired = true)
    val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)
    val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(
      beamExecutionConfig.beamConfig,
      beamExecutionConfig.matsimConfig
    )
    val scenario: MutableScenario = scenarioBuilt
    val injector: Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
    implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
    implicit val ec = actorSystem.dispatcher
    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])
    runWithServices(beamServices, params).flatMap { _ =>
      logger.info("Terminating actorSystem")
      actorSystem.terminate()
    }
  }

  def runWithServices(beamServices: BeamServices, params: InputParameters)(implicit actorSystem: ActorSystem) = {
    val maxHour = DateUtils.getMaxHour(beamServices.beamConfig)
    val timeBinSizeInSeconds = beamServices.beamConfig.beam.agentsim.timeBinSize
    val travelTime = params.linkstatsPath match {
      case Some(path) => new LinkTravelTimeContainer(path.toString, timeBinSizeInSeconds, maxHour)
      case None       => new FreeFlowTravelTime
    }

    val tazMap: Map[String, GeoUnit.TAZ] = beamServices.beamScenario.tazTreeMap.getTAZs
      .map(taz => taz.tazId.toString -> GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
      .toMap

    val odRows = params.input match {
      case Some(path) => readInputCsv(path.toString, tazMap)
      case None =>
        val origins = tazMap.values
        val destinations = tazMap.values
        origins.flatMap { origin =>
          destinations.collect {
            case destination if origin != destination =>
              ODRow(origin, destination)
          }
        }.toVector
    }
    val ODs: Array[(GeoIndex, GeoIndex)] = odRows.map { row =>
      (TAZIndex(tazUnitToTAZ(row.origin)), TAZIndex(tazUnitToTAZ(row.destination)))
    }.toArray

    // "indexing" existing skims by originId
    val existingSkims: Map[String, Vector[ExcerptData]] =
      params.ODSkimsPath.map(path => readSkimsCsv(path.toString)).getOrElse(Vector.empty).groupBy(_.originId)

    val skimmer = createSkimmer(beamServices, odRows, existingSkims)

    implicit val ec = actorSystem.dispatcher

    val backgroundODSkimsCreatorConfig = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator
    val skimsCreator = new BackgroundSkimsCreator(
      beamServices = beamServices,
      beamScenario = beamServices.beamScenario,
      ODs = ODs,
      abstractSkimmer = skimmer,
      travelTime = travelTime,
      beamModes = Seq(BeamMode.CAR, BeamMode.WALK),
      withTransit = backgroundODSkimsCreatorConfig.modesToBuild.transit,
      buildDirectWalkRoute = backgroundODSkimsCreatorConfig.modesToBuild.walk,
      buildDirectCarRoute = backgroundODSkimsCreatorConfig.modesToBuild.drive,
      calculationTimeoutHours = backgroundODSkimsCreatorConfig.calculationTimeoutHours
    )

    logger.info("Parallelism " + params.parallelism)
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(params.parallelism)

    skimsCreator.getResult.map(skimmer => {
      logger.info("Got populated skimmer")
      skimmer.abstractSkimmer.writeToDisk(params.output.toString)
      logger.info("Stopping skimsCreator")
      skimsCreator.stop()
    })
  }

  def createSkimmer(
    beamServices: BeamServices,
    rows: Vector[ODRow],
    existingSkims: Map[String, Vector[ExcerptData]]
  ): AbstractSkimmer = {
    beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind match {
      case "od"          => createOdSkimmer(beamServices, rows)
      case "activitySim" => createActivitySimSkimmer(beamServices, rows, existingSkims)
      case skimsKind =>
        throw new IllegalArgumentException(
          s"Unexpected skims kind ($skimsKind)"
        )
    }
  }

  def createActivitySimSkimmer(
    beamServices: BeamServices,
    rows: Vector[ODRow],
    existingSkims: Map[String, Vector[ExcerptData]]
  ): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {

      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(ExcerptData.csvHeader)
            writer.write("\n")

            ProfilingUtils.timed("Writing skims for time periods for all pathTypes", x => logger.info(x)) {
              rows.foreach { case ODRow(origin, destination) =>
                val skims = existingSkims
                  .get(origin.id)
                  .map(_.filter(skim => skim.destinationId == destination.id))
                  .getOrElse(Vector.empty)
                if (skims.nonEmpty) {
                  skims.foreach(s => writer.write(s.toCsvString))
                } else {
                  getExcerptDataForOD(origin, destination).foreach { excerptData =>
                    writer.write(excerptData.toCsvString)
                  }
                }
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

  def createOdSkimmer(beamServices: BeamServices, rows: Vector[ODRow]): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      lazy val hours = BackgroundSkimsCreator.getPeakHoursFromConfig(beamServices)
      lazy val uniqueTimeBins: Seq[Int] = hours.map(math.round(_).toInt)

      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(skimFileHeader + "\n")

            rows.foreach { case ODRow(origin, destination) =>
              BeamMode.allModes.foreach { beamMode =>
                writeSkimRow(writer, uniqueTimeBins, origin, destination, beamMode)
              }
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
