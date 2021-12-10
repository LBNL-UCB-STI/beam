package beam.router

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.cch.CchWrapper
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.gtfs.FareCalculator
import beam.router.model._
import beam.router.osm.TollCalculator
import beam.router.r5.{CarWeightCalculator, R5Parameters, R5Wrapper}
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.osmlib.OSM
import com.conveyal.r5.api.util._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import java.io.File
import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.reflect.io.Directory

class RoutingWorker(workerParams: R5Parameters) extends Actor with ActorLogging with MetricsSupport {

  def this(config: Config) {
    this(workerParams = {
      R5Parameters.fromConfig(config)
    })
  }

  private val carRouter = workerParams.beamConfig.beam.routing.carRouter

  private val noOfTimeBins = Math
    .floor(
      Time.parseTime(workerParams.beamConfig.beam.agentsim.endTime) /
      workerParams.beamConfig.beam.agentsim.timeBinSize
    )
    .toInt

  private val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1
    else Runtime.getRuntime.availableProcessors() - 2

  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("r5-routing-worker-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val tickTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(2.seconds, 10.seconds, self, "tick")(context.dispatcher)
  private var msgs = 0
  private var firstMsgTime: Option[ZonedDateTime] = None
  log.info("RoutingWorker[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  private def getNameAndHashCode: String = s"RoutingWorker[${hashCode()}], Path: `${self.path}`"

  private var workAssigner: ActorRef = context.parent

  private var r5: R5Wrapper = new R5Wrapper(
    workerParams,
    new FreeFlowTravelTime,
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
  private var binToCarGraphHopper: Map[Int, GraphHopperWrapper] = _
  private var walkGraphHopper: GraphHopperWrapper = _
  private var cchWrapper: CchWrapper = _

  private val linksBelowMinCarSpeed =
    workerParams.networkHelper.allLinks
      .count(l => l.getFreespeed < workerParams.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    log.warning(
      "{} links are below quick_fix_minCarSpeedInMetersPerSecond, already in free-flow",
      linksBelowMinCarSpeed
    )
  }

  override def preStart(): Unit = {
    if (carRouter == "staticGH" || carRouter == "quasiDynamicGH") {
      new Directory(new File(graphHopperDir)).deleteRecursively()
      createWalkGraphHopper()
      createCarGraphHoppers(new FreeFlowTravelTime)
    } else if (carRouter == "nativeCCH") {
      log.info("Init CchNative")
      cchWrapper = ProfilingUtils.timed("Cch native construction", log.info(_))(CchWrapper(workerParams))
    }

    askForMoreWork()
  }

  override def postStop(): Unit = {
    tickTask.cancel()
    execSvc.shutdown()
  }

  // Let the dispatcher on which the Future in receive will be running
  // be the dispatcher on which this actor is running.
  val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
    .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
    .toMap

  override final def receive: Receive = {
    case "tick" =>
      firstMsgTime match {
        case Some(firstMsgTimeValue) =>
          val seconds =
            ChronoUnit.SECONDS.between(firstMsgTimeValue, ZonedDateTime.now(ZoneOffset.UTC))
          if (seconds > 0) {
            val rate = msgs.toDouble / seconds
            if (seconds > 60) {
              firstMsgTime = None
              msgs = 0
            }
            if (workerParams.beamConfig.beam.outputs.displayPerformanceTimings) {
              log.info(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            } else {
              log.debug(
                "Receiving {} per seconds of RoutingRequest with first message time set to {} for the next round",
                rate,
                firstMsgTime
              )
            }
          }
        case None => //
      }
    case WorkAvailable =>
      workAssigner = sender
      askForMoreWork()

    case request: RoutingRequest =>
      msgs = msgs + 1
      if (firstMsgTime.isEmpty) firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val eventualResponse = Future {
        latency("request-router-time", Metrics.RegularLevel) {
          if (!request.withTransit && (carRouter == "staticGH" || carRouter == "quasiDynamicGH")) {
            // run graphHopper for only cars
            val ghCarResponse = calcCarGhRoute(request)
            // run graphHopper for only walk
            val ghWalkResponse = calcWalkGhRoute(request)

            val modesToExclude = calcExcludeModes(
              ghCarResponse.exists(_.itineraries.nonEmpty),
              ghWalkResponse.exists(_.itineraries.nonEmpty)
            )

            joinResponsesOrCallR5(modesToExclude, request, ghCarResponse, ghWalkResponse)
          } else if (!request.withTransit && carRouter == "nativeCCH") {
            val cchResponse = calcCarNativeCCHRoute(request)

            val modesToExclude = calcExcludeModes(
              cchResponse.exists(_.itineraries.nonEmpty),
              successfulWalkResponse = false
            )

            joinResponsesOrCallR5(modesToExclude, request, cchResponse)
          } else {
            r5.calcRoute(request)
          }
        }
      }
      eventualResponse.recover { case e =>
        log.error(e, "calcRoute failed")
        RoutingFailure(e, request)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(newTravelTime)
      } else if (carRouter == "nativeCCH") {
        rebuildNativeCCHWeights(newTravelTime)
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info("{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      val newTravelTime =
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(workerParams.beamConfig.beam.agentsim.timeBinSize, map)
      if (carRouter == "quasiDynamicGH") {
        createCarGraphHoppers(newTravelTime)
      } else if (carRouter == "nativeCCH") {
        rebuildNativeCCHWeights(newTravelTime)
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info(
        "{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
        getNameAndHashCode,
        map.keySet().size()
      )
      askForMoreWork()

    case EmbodyWithCurrentTravelTime(
          leg: BeamLeg,
          vehicleId: Id[Vehicle],
          vehicleTypeId: Id[BeamVehicleType],
          embodyRequestId: Int,
          triggerId
        ) =>
      val response: RoutingResponse =
        r5.embodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, embodyRequestId, triggerId)
      sender ! response
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  private def createWalkGraphHopper(): Unit = {
    log.info("Init GH Walk")
    GraphHopperWrapper.createWalkGraphDirectoryFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
      graphHopperDir
    )

    walkGraphHopper = new WalkGraphHopperWrapper(
      graphHopperDir,
      workerParams.geo,
      id2Link,
      workerParams.beamConfig.beam.routing.gh.useAlternativeRoutes
    )
  }

  private def createCarGraphHoppers(travelTime: TravelTime): Unit = {
    log.info("Init GH Car")
    // Clean up GHs variable and than calculate new ones
    binToCarGraphHopper = Map()
    new Directory(new File(carGraphHopperDir)).deleteRecursively()

    val carWeightCalculator = new CarWeightCalculator(workerParams)
    val graphHopperInstances = if (carRouter == "quasiDynamicGH") noOfTimeBins else 1

    val futures = (0 until graphHopperInstances).map { i =>
      Future {
        val ghDir = Paths.get(carGraphHopperDir, i.toString).toString

        val wayId2TravelTime = workerParams.networkHelper.allLinks.toSeq
          .map(link =>
            link.getId.toString.toLong ->
            carWeightCalculator.calcTravelTime(
              link.getId.toString.toInt,
              travelTime,
              i * workerParams.beamConfig.beam.agentsim.timeBinSize
            )
          )
          .toMap

        GraphHopperWrapper.createCarGraphDirectoryFromR5(
          carRouter,
          workerParams.transportNetwork,
          new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
          ghDir,
          wayId2TravelTime
        )

        i -> new CarGraphHopperWrapper(
          carRouter,
          ghDir,
          workerParams.geo,
          workerParams.vehicleTypes,
          workerParams.fuelTypePrices,
          wayId2TravelTime,
          id2Link,
          workerParams.beamConfig.beam.routing.gh.useAlternativeRoutes
        )
      }
    }

    val s = System.currentTimeMillis()
    binToCarGraphHopper = Await.result(Future.sequence(futures), 20.minutes).toMap
    val e = System.currentTimeMillis()
    log.info(s"GH built in ${e - s} ms")
  }

  private def rebuildNativeCCHWeights(newTravelTime: TravelTime): Unit = {
    ProfilingUtils.timed("Cch native rebuilt weights", log.info(_))(cchWrapper.rebuildNativeCCHWeights(newTravelTime))
  }

  private def calcCarNativeCCHRoute(req: RoutingRequest) = {
    val carMode = Modes.BeamMode.CAR
    if (req.streetVehicles.exists(_.mode == carMode)) {
      Some(cchWrapper.calcRoute(req.copy(streetVehicles = req.streetVehicles.filter(_.mode == carMode))))
    } else
      Some(
        RoutingResponse(
          Seq(),
          req.requestId,
          Some(req),
          isEmbodyWithCurrentTravelTime = false,
          triggerId = req.triggerId
        )
      )
  }

  private def calcCarGhRoute(request: RoutingRequest): Option[RoutingResponse] = {
    val carMode = Modes.BeamMode.CAR
    if (request.streetVehicles.exists(_.mode == carMode)) {
      val idx =
        if (carRouter == "quasiDynamicGH")
          Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
        else 0
      Some(
        binToCarGraphHopper(idx).calcRoute(
          request.copy(streetVehicles = request.streetVehicles.filter(_.mode == carMode))
        )
      )
    } else None
  }

  private def calcWalkGhRoute(request: RoutingRequest): Option[RoutingResponse] = {
    val walkMode = Modes.BeamMode.WALK
    if (request.streetVehicles.exists(_.mode == walkMode)) {
      Some(
        walkGraphHopper.calcRoute(request.copy(streetVehicles = request.streetVehicles.filter(_.mode == walkMode)))
      )
    } else None
  }

  private def calcExcludeModes(successfulCarResponse: Boolean, successfulWalkResponse: Boolean) = {
    if (successfulCarResponse && successfulWalkResponse) {
      List(CAR, WALK)
    } else if (successfulCarResponse) {
      List(CAR)
    } else if (successfulWalkResponse) {
      List(WALK)
    } else {
      List()
    }
  }

  private def joinResponsesOrCallR5(
    modesToExclude: List[BeamMode],
    request: RoutingRequest,
    responses: Option[RoutingResponse]*
  ): RoutingResponse = {
    if (modesToExclude.isEmpty) {
      r5.calcRoute(request)
    } else {
      val filteredStreetVehicles = request.streetVehicles.filterNot(it => modesToExclude.contains(it.mode))
      val r5ResponseOption = if (filteredStreetVehicles.isEmpty) {
        None
      } else {
        Some(r5.calcRoute(request.copy(streetVehicles = filteredStreetVehicles)))
      }

      val definedResponses = responses.flatten
      (definedResponses, r5ResponseOption) match {
        case (head +: _, Some(r5Resp)) =>
          head.copy(
            itineraries = r5Resp.itineraries ++ definedResponses.flatMap(_.itineraries),
            searchedModes = r5Resp.searchedModes ++ definedResponses.flatMap(_.searchedModes)
          )
        case (head +: _, None) =>
          head.copy(
            itineraries = definedResponses.flatMap(_.itineraries),
            searchedModes = definedResponses.flatMap(_.searchedModes).toSet
          )
        case (Seq(), Some(r5Resp)) =>
          r5Resp
        case (Seq(), None) => r5.calcRoute(request)
      }
    }
  }
}

object RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 1.38

  // 3.1 mph -> 1.38 meter per second, changed from 1 mph
  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    networkHelper: NetworkHelper,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator
  ): Props = Props(
    new RoutingWorker(
      R5Parameters(
        beamScenario.beamConfig,
        transportNetwork,
        beamScenario.vehicleTypes,
        beamScenario.fuelTypePrices,
        beamScenario.ptFares,
        new GeoUtilsImpl(beamScenario.beamConfig),
        beamScenario.dates,
        networkHelper,
        fareCalculator,
        tollCalculator
      )
    )
  )

  case class R5Request(
    from: Coord,
    to: Coord,
    time: Int,
    directMode: LegMode,
    accessMode: LegMode,
    withTransit: Boolean,
    egressMode: LegMode,
    timeValueOfMoney: Double,
    beamVehicleTypeId: Id[BeamVehicleType]
  )

  def createBushwackingBeamLeg(
    atTime: Int,
    startUTM: Location,
    endUTM: Location,
    geo: GeoUtils
  ): BeamLeg = {
    val distanceInMeters =
      GeoUtils.minkowskiDistFormula(startUTM, endUTM) //changed from geo.distUTMInMeters(startUTM, endUTM)
    val bushwhackingTime = Math.round(distanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
    val path = BeamPath(
      Vector(),
      Vector(),
      None,
      SpaceTime(geo.utm2Wgs(startUTM), atTime),
      SpaceTime(geo.utm2Wgs(endUTM), atTime + bushwhackingTime.toInt),
      distanceInMeters
    )
    BeamLeg(atTime, WALK, bushwhackingTime.toInt, path)
  }

  def createBushwackingTrip(
    originUTM: Location,
    destUTM: Location,
    atTime: Int,
    body: StreetVehicle,
    geo: GeoUtils
  ): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      Vector(
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(atTime, originUTM, destUTM, geo),
          body.id,
          body.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = true
        )
      ),
      Some("Bushwhacking")
    )
  }

  class StopVisitor(
    val streetLayer: StreetLayer,
    val dominanceVariable: StreetRouter.State.RoutingVariable,
    val maxStops: Int,
    val minTravelTimeSeconds: Int,
    val destinationSplit: Split
  ) extends RoutingVisitor {
    private val NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.getNoEntryKey
    val stops: TIntIntMap = new TIntIntHashMap
    private var s0: StreetRouter.State = _
    private val destinationSplitVertex0 = if (destinationSplit != null) destinationSplit.vertex0 else -1
    private val destinationSplitVertex1 = if (destinationSplit != null) destinationSplit.vertex1 else -1

    override def visitVertex(state: StreetRouter.State): Unit = {
      s0 = state
      val stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex)
      if (stop != NO_STOP_FOUND) {
        if (state.getDurationSeconds < minTravelTimeSeconds) return
        if (!stops.containsKey(stop) || stops.get(stop) > state.getRoutingVariable(dominanceVariable))
          stops.put(stop, state.getRoutingVariable(dominanceVariable))
      }
    }

    override def shouldBreakSearch: Boolean =
      stops.size >= this.maxStops || s0.vertex == destinationSplitVertex0 || s0.vertex == destinationSplitVertex1
  }

}
