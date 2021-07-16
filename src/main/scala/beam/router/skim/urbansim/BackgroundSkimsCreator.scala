package beam.router.skim.urbansim

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.{GeoIndex, GeoZoneSummaryItem, H3Wrapper, TAZIndex}
import beam.router.Modes.BeamMode
import beam.router.Router
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.skim._
import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerEventFactory, ODSkimmer}
import beam.router.skim.urbansim.MasterActor.Response
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.router.util.TravelTime

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.Future

class BackgroundSkimsCreator(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val ODs: Array[(GeoIndex, GeoIndex)],
  val abstractSkimmer: AbstractSkimmer,
  val travelTime: TravelTime,
  val beamModes: Seq[BeamMode],
  val withTransit: Boolean,
  val buildDirectWalkRoute: Boolean,
  val buildDirectCarRoute: Boolean,
  val calculationTimeoutHours: Int
)(implicit actorSystem: ActorSystem)
    extends LazyLogging {
  def this(
    beamServices: BeamServices,
    beamScenario: BeamScenario,
    geoClustering: GeoClustering,
    abstractSkimmer: AbstractSkimmer,
    travelTime: TravelTime,
    beamModes: Seq[BeamMode],
    withTransit: Boolean,
    buildDirectWalkRoute: Boolean,
    buildDirectCarRoute: Boolean,
    calculationTimeoutHours: Int
  )(implicit actorSystem: ActorSystem) {
    this(
      beamServices,
      beamScenario,
      ODs = geoClustering match {
        case h3Clustering: H3Clustering =>
          val h3Indexes: Seq[GeoZoneSummaryItem] = h3Clustering.h3Indexes
          h3Indexes.flatMap { srcGeo =>
            h3Indexes.map { dstGeo =>
              (srcGeo.index, dstGeo.index)
            }
          }.toArray

        case tazClustering: TAZClustering =>
          val tazs = tazClustering.tazTreeMap.getTAZs
          tazs.flatMap { srcTAZ =>
            tazs.map { destTAZ =>
              (TAZIndex(srcTAZ), TAZIndex(destTAZ))
            }
          }.toArray
      },
      abstractSkimmer,
      travelTime,
      beamModes,
      withTransit,
      buildDirectWalkRoute,
      buildDirectCarRoute,
      calculationTimeoutHours
    )
  }

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
    if (useR5) { None }
    else {
      Some(ODRouterR5GHForActivitySimSkims(r5Parameters, getPeakSecondsFromConfig(beamServices), Some(travelTime)))
    }

  val router: Router = if (useR5) { maybeR5Router.get }
  else { maybeODRouter.get }

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
      abstractSkimmer,
      odRequester,
      requestTimes = getPeakSecondsFromConfig(beamServices),
      ODs
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
