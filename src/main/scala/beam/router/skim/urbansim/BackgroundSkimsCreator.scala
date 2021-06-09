package beam.router.skim.urbansim

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.geozone.H3Wrapper
import beam.router.Modes.BeamMode
import beam.router.{FreeFlowTravelTime, Router}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim._
import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerEventFactory, ODSkimmer}
import beam.router.skim.urbansim.MasterActor.Response
import beam.sim.config.BeamExecutionConfig
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.ProfilingUtils
import beam.utils.csv.GenericCsvReader
import com.google.inject.Injector
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.MutableScenario
import scopt.OParser

import java.io.{BufferedWriter, Closeable, File}
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.util.control.NonFatal

case class InputParameters(
  configPath: Path = null,
  input: Path = null,
  output: Path = null
)

case class CsvInputRow(
  origin: String,
  destination: String,
  mode: String
)

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
      opt[File]("output").required().action((x, c) => c.copy(output = x.toPath)).text("output csv file path")
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

      implicit val actorSystem = ActorSystem()
      implicit val ec = actorSystem.dispatcher

      val inputODs = readCsv(params.input.toString)
      logger.info(s"inputODs $inputODs")

      val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)

      val (scenarioBuilt, beamScenario) = buildBeamServicesAndScenario(
        beamExecutionConfig.beamConfig,
        beamExecutionConfig.matsimConfig
      )
      val scenario: MutableScenario = scenarioBuilt
      val injector: Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
      val beamServices: BeamServices = buildBeamServices(injector, scenario)
      val tazClustering: TAZClustering = new TAZClustering(beamScenario.tazTreeMap)
      val skimmer = BackgroundSkimsCreator.createFilteredSkimmer(beamServices, tazClustering, inputODs)

      val skimsCreator = new BackgroundSkimsCreator(
        beamServices = beamServices,
        beamScenario = beamScenario,
        geoClustering = tazClustering,
        abstractSkimmer = skimmer,
        travelTime = new FreeFlowTravelTime,
        beamModes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectWalkRoute = false,
        buildDirectCarRoute = true,
        calculationTimeoutHours = 1
      )

      skimsCreator.start()
      skimsCreator.getResult
        .map(skimmer => {
          logger.info("Got populated skimmer")
          logger.info(skimmer.abstractSkimmer.currentSkim.toString())
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

}

class BackgroundSkimsCreator(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val geoClustering: GeoClustering,
  val abstractSkimmer: AbstractSkimmer,
  val travelTime: TravelTime,
  val beamModes: Seq[BeamMode],
  val withTransit: Boolean,
  val buildDirectWalkRoute: Boolean,
  val buildDirectCarRoute: Boolean,
  val calculationTimeoutHours: Int
)(implicit actorSystem: ActorSystem)
    extends LazyLogging {

  import BackgroundSkimsCreator._

  private implicit val timeout: Timeout = Timeout(calculationTimeoutHours, TimeUnit.HOURS)

  val r5Parameters: R5Parameters = R5Parameters(
    beamServices.beamConfig,
    beamScenario.transportNetwork,
    beamScenario.vehicleTypes,
    beamScenario.fuelTypePrices,
    beamScenario.ptFares,
    beamServices.geo,
    beamScenario.dates,
    beamServices.networkHelper,
    beamServices.fareCalculator,
    beamServices.tollCalculator
  )

  private val useR5 = beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.routerType == "r5"

  val maybeR5Router: Option[R5Wrapper] = if (useR5) {
    val r5Wrapper = new R5Wrapper(
      r5Parameters,
      travelTime,
      travelTimeNoiseFraction = 0.0
    )
    Some(r5Wrapper)
  } else {
    None
  }

  val maybeODRouter: Option[ODRouterR5GHForActivitySimSkims] =
    if (useR5) { None } else {
      Some(ODRouterR5GHForActivitySimSkims(r5Parameters, getPeakSecondsFromConfig(beamServices), Some(travelTime)))
    }

  val router: Router = if (useR5) { maybeR5Router.get } else { maybeODRouter.get }

  val skimmerEventFactory: AbstractSkimmerEventFactory =
    beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind match {
      case "od"          => new ODSkimmerEventFactory
      case "activitySim" => new ActivitySimSkimmerEventFactory(beamServices.beamConfig)
      case kind @ _      => throw new IllegalArgumentException(s"Unexpected skims kind: $kind")
    }

  var odRequester: ODRequester = new ODRequester(
    vehicleTypes = beamScenario.vehicleTypes,
    router = router,
    scenario = beamServices.matsimServices.getScenario,
    geoUtils = beamServices.geo,
    beamModes = beamModes,
    beamConfig = beamServices.beamConfig,
    modeChoiceCalculatorFactory = beamServices.modeChoiceCalculatorFactory,
    withTransit = withTransit,
    buildDirectWalkRoute = buildDirectWalkRoute,
    buildDirectCarRoute = buildDirectCarRoute,
    skimmerEventFactory
  )

  private val masterActorRef: ActorRef = {
    val actorName = s"Modes-${beamModes.mkString("_")}-with-transit-$withTransit-${UUID.randomUUID()}"

    val masterProps = MasterActor.props(
      geoClustering,
      abstractSkimmer,
      odRequester,
      requestTimes = getPeakSecondsFromConfig(beamServices)
    )
    actorSystem.actorOf(masterProps, actorName)
  }

  def start(): Unit = {
    masterActorRef ! MasterActor.Request.Start
  }

  def stop(): Unit = {
    masterActorRef ! MasterActor.Request.Stop
    logger.info(s"Routes execution time: ${odRequester.requestsExecutionTime.toShortString}")
    if (maybeODRouter.nonEmpty) {
      val execInfo = maybeODRouter.map(_.totalRouteExecutionInfo.toString()).getOrElse("")
      logger.info(s"Routes execution time detailed: $execInfo")
    }
  }

  def reduceParallelismTo(parallelism: Int): Unit = {
    masterActorRef ! MasterActor.Request.ReduceParallelismTo(parallelism)
  }

  def increaseParallelismTo(parallelism: Int): Unit = {
    masterActorRef ! MasterActor.Request.IncreaseParallelismTo(parallelism)
  }

  def getResult: Future[Response.PopulatedSkimmer] = {
    masterActorRef.ask(MasterActor.Request.WaitToFinish).mapTo[MasterActor.Response.PopulatedSkimmer]
  }
}

object BackgroundSkimsCreator {

  def getPeakSecondsFromConfig(beamServices: BeamServices): List[Int] = {
    getPeakHoursFromConfig(beamServices).map { hour =>
      (hour * 3600).toInt
    }
  }

  def getPeakHoursFromConfig(beamServices: BeamServices): List[Double] = {
    // it seems there is no way to specify default list value in configuration
    beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.peakHours match {
      case Some(hours) => hours
      case _           => List(8.5)
    }
  }

  def createSkimmer(beamServices: BeamServices, clustering: GeoClustering): AbstractSkimmer = {
    (clustering, beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind) match {
      case (tazClustering: TAZClustering, "od")          => createTAZOdSkimmer(beamServices, tazClustering)
      case (h3Clustering: H3Clustering, "od")            => createH3ODSkimmer(beamServices, h3Clustering)
      case (tazClustering: TAZClustering, "activitySim") => createTAZActivitySimSkimmer(beamServices, tazClustering)
      case (h3Clustering: H3Clustering, "activitySim")   => createH3ActivitySimSkimmer(beamServices, h3Clustering)
      case (clustering, skimsKind) =>
        throw new IllegalArgumentException(
          s"Unexpected pair: skims kind ($skimsKind) with clustering ($clustering)"
        )
    }
  }

  def createFilteredSkimmer(
    beamServices: BeamServices,
    clustering: TAZClustering,
    inputODs: Vector[CsvInputRow]
  ): AbstractSkimmer = {
    lazy val tazs = clustering.tazTreeMap.getTAZs
      .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
      .groupBy(_.id)
      .mapValues(_.head)
    val odRows = inputODs.map(od => (tazs(od.origin), tazs(od.destination), od.mode))

    (beamServices.beamConfig.beam.urbansim.backgroundODSkimsCreator.skimsKind) match {
      case "od" => createFilteredTAZOdSkimmer(beamServices, odRows)
      case "activitySim" =>
        createFilteredTAZActivitySimSkimmer(beamServices, odRows)
      case skimsKind =>
        throw new IllegalArgumentException(
          s"Unexpected skims kind ($skimsKind)"
        )
    }
  }

  def createFilteredTAZActivitySimSkimmer(
    beamServices: BeamServices,
    odRows: Vector[(GeoUnit.TAZ, GeoUnit.TAZ, String)]
  ): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          val EAHours = (3 until 6).toList
          val AMHours = (6 until 10).toList
          val MDHours = (10 until 15).toList
          val PMHours = (15 until 19).toList
          val EVHours = (0 until 3).toList ++ (19 until 24).toList

          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(ExcerptData.csvHeader)
            writer.write("\n")

            ProfilingUtils.timed("Writing skims for time periods for all pathTypes", x => logger.info(x)) {
              odRows.foreach {
                case (origin, destination, mode) =>
                  def getExcerptDataForTimePeriod(timePeriodName: String, timePeriodHours: List[Int]): ExcerptData = {
                    getExcerptData(
                      timePeriodName,
                      timePeriodHours,
                      origin,
                      destination,
                      // TODO
                      ActivitySimPathType.allPathTypes.find(_.toString == mode).get
                    )
                  }

                  val ea = getExcerptDataForTimePeriod("EA", EAHours)
                  val am = getExcerptDataForTimePeriod("AM", AMHours)
                  val md = getExcerptDataForTimePeriod("MD", MDHours)
                  val pm = getExcerptDataForTimePeriod("PM", PMHours)
                  val ev = getExcerptDataForTimePeriod("EV", EVHours)

                  List(ea, am, md, pm, ev).foreach { excerptData: ExcerptData =>
                    writer.write(excerptData.toCsvString)
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

  private val additionalSkimFileNamePart = ".UrbanSim"

  def createTAZActivitySimSkimmer(
    beamServices: BeamServices,
    tazClustering: TAZClustering
  ): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".TAZ.Full.csv.gz"
          )
          val origins = tazClustering.tazTreeMap.getTAZs
            .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
            .toSeq

          writeSkimsForTimePeriods(origins, origins, filePath)
          logger.info(s"Written UrbanSim peak skims to $filePath")
        }
      }
    }

  def createH3ActivitySimSkimmer(beamServices: BeamServices, h3Clustering: H3Clustering): ActivitySimSkimmer =
    new ActivitySimSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".H3.Full.csv.gz"
          )

          val origins: Seq[GeoUnit.H3] = h3Clustering.h3Indexes.map { h3Index =>
            val wgsCenter = H3Wrapper.wgsCoordinate(h3Index.index).coord
            val utmCenter = beamServices.geo.wgs2Utm(wgsCenter)
            val areaInSquareMeters = H3Wrapper.hexAreaM2(h3Index.index.resolution)
            GeoUnit.H3(h3Index.index.value, utmCenter, areaInSquareMeters)
          }

          writeSkimsForTimePeriods(origins, origins, filePath)
          logger.info(s"Written UrbanSim peak skims to $filePath")
        }
      }
    }

  def createFilteredTAZOdSkimmer(
    beamServices: BeamServices,
    odRows: Vector[(GeoUnit.TAZ, GeoUnit.TAZ, String)]
  ): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      lazy val hours = getPeakHoursFromConfig(beamServices)
      lazy val uniqueTimeBins: Seq[Int] = hours.map(math.round(_).toInt)

      override def writeToDisk(filePath: String): Unit = {
        ProfilingUtils.timed(s"writeFullSkims", v => logger.info(v)) {
          val dummyId = Id.create(
            beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
            classOf[BeamVehicleType]
          )

          var writer: BufferedWriter = null
          try {
            writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
            writer.write(skimFileHeader + "\n")

            odRows.foreach {
              case (origin, destination, mode) =>
                // TODO
                val beamMode = BeamMode.fromString(mode).get
                uniqueTimeBins
                  .foreach { timeBin =>
                    val theSkim: ODSkimmer.Skim = currentSkim
                      .get(ODSkimmerKey(timeBin, beamMode, origin.id, destination.id))
                      .map(_.asInstanceOf[ODSkimmerInternal].toSkimExternal)
                      .getOrElse {
                        if (origin.equals(destination)) {
                          val newDestCoord = new Coord(
                            origin.center.getX,
                            origin.center.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
                          )
                          readOnlySkim
                            .asInstanceOf[ODSkims]
                            .getSkimDefaultValue(
                              beamMode,
                              origin.center,
                              newDestCoord,
                              timeBin * 3600,
                              dummyId,
                              beamServices.beamScenario
                            )
                        } else {
                          readOnlySkim
                            .asInstanceOf[ODSkims]
                            .getSkimDefaultValue(
                              beamMode,
                              origin.center,
                              destination.center,
                              timeBin * 3600,
                              dummyId,
                              beamServices.beamScenario
                            )
                        }
                      }

                    //     "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,observations,iterations"
                    writer.write(
                      s"$timeBin,$mode,${origin.id},${destination.id},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedCost},${theSkim.distance},${theSkim.energy},${theSkim.count}\n"
                    )
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

  def createTAZOdSkimmer(beamServices: BeamServices, tazClustering: TAZClustering): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".TAZ.Full.csv.gz"
          )
          val hours = getPeakHoursFromConfig(beamServices)
          val uniqueTimeBins: Seq[Int] = hours.map(math.round(_).toInt)
          val origins = tazClustering.tazTreeMap.getTAZs
            .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
            .toSeq

          writeFullSkims(origins, origins, uniqueTimeBins, filePath)
          logger.info(s"Written UrbanSim peak skims for hours $hours to $filePath")
        }
      }
    }

  def createH3ODSkimmer(beamServices: BeamServices, h3Clustering: H3Clustering): ODSkimmer =
    new ODSkimmer(beamServices.matsimServices, beamServices.beamScenario, beamServices.beamConfig) {
      override def writeToDisk(event: IterationEndsEvent): Unit = {
        ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + additionalSkimFileNamePart + ".H3.Full.csv.gz"
          )

          val hours = getPeakHoursFromConfig(beamServices)
          val uniqueTimeBins: Seq[Int] = hours.map(math.round(_).toInt)
          val origins: Seq[GeoUnit.H3] = h3Clustering.h3Indexes.map { h3Index =>
            val wgsCenter = H3Wrapper.wgsCoordinate(h3Index.index).coord
            val utmCenter = beamServices.geo.wgs2Utm(wgsCenter)
            val areaInSquareMeters = H3Wrapper.hexAreaM2(h3Index.index.resolution)
            GeoUnit.H3(h3Index.index.value, utmCenter, areaInSquareMeters)
          }

          writeFullSkims(origins, origins, uniqueTimeBins, filePath)
          logger.info(s"Written UrbanSim peak skims for hours $hours to $filePath")
        }
      }
    }
}

case class RouteExecutionInfo(
  r5ExecutionTime: Long = 0,
  ghCarExecutionDuration: Long = 0,
  ghWalkExecutionDuration: Long = 0,
  r5Responses: Long = 0,
  ghCarResponses: Long = 0,
  ghWalkResponses: Long = 0
) {
  val nanosToSec = 0.000000001

  def toShortString: String =
    s"total execution time in seconds: ${(r5ExecutionTime + ghCarExecutionDuration + ghWalkExecutionDuration) * nanosToSec}"

  override def toString: String =
    toShortString +
    s"\nr5 execution time in seconds | number of responses: ${r5ExecutionTime * nanosToSec} | $r5Responses " +
    s"\ngh car route execution time in seconds | number of responses: ${ghCarExecutionDuration * nanosToSec} | $ghCarResponses" +
    s"\ngh walk route execution time in seconds | number of responses: ${ghWalkExecutionDuration * nanosToSec} | $ghWalkResponses"
}

object RouteExecutionInfo {

  def sum(debugInfo1: RouteExecutionInfo, debugInfo2: RouteExecutionInfo): RouteExecutionInfo = {
    RouteExecutionInfo(
      r5ExecutionTime = debugInfo1.r5ExecutionTime + debugInfo2.r5ExecutionTime,
      ghCarExecutionDuration = debugInfo1.ghCarExecutionDuration + debugInfo2.ghCarExecutionDuration,
      ghWalkExecutionDuration = debugInfo1.ghWalkExecutionDuration + debugInfo2.ghWalkExecutionDuration,
      r5Responses = debugInfo1.r5Responses + debugInfo2.r5Responses,
      ghCarResponses = debugInfo1.ghCarResponses + debugInfo2.ghCarResponses,
      ghWalkResponses = debugInfo1.ghWalkResponses + debugInfo2.ghWalkResponses
    )
  }
}
