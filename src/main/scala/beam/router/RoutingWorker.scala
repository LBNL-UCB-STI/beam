package beam.router

import java.io.File
import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.graphhopper.GraphHopperWrapper
import beam.router.gtfs.FareCalculator
import beam.router.model.{EmbodiedBeamTrip, _}
import beam.router.osm.TollCalculator
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.osmlib.OSM
import com.conveyal.r5.api.util._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.util.concurrent.{AtomicDouble, ThreadFactoryBuilder}
import com.typesafe.config.Config
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

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
  log.info("R5RoutingWorker_v2[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  private def getNameAndHashCode: String = s"R5RoutingWorker_v2[${hashCode()}], Path: `${self.path}`"

  private var workAssigner: ActorRef = context.parent

  private var r5: R5Wrapper = new R5Wrapper(
    workerParams,
    new FreeFlowTravelTime,
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private var graphHoppers: Map[Int, GraphHopperWrapper] = _

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
      createGraphHoppers()
      askForMoreWork()
    }
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

  val notTransitCarRouteRequestCounter = new AtomicInteger(0)
  val notTransitRouteRequestExecutionTime = new AtomicDouble(0.0)
  val recallCarRequestToR5 = new AtomicInteger(0)

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
            val start = System.currentTimeMillis()

            //run graphHopper for only cars
            val ghResponse = if (request.streetVehicles.exists(_.mode == CAR)) {
              notTransitCarRouteRequestCounter.incrementAndGet()
              val idx =
                if (carRouter == "quasiDynamicGH")
                  Math.floor(request.departureTime / workerParams.beamConfig.beam.agentsim.timeBinSize).toInt
                else 0
              Some(
                graphHoppers(idx).calcRoute(
                  request.copy(streetVehicles = request.streetVehicles.filter(_.mode == CAR))
                )
              )
            } else None

            val response = if (!ghResponse.exists(_.itineraries.nonEmpty)) {
              if (ghResponse.isDefined) {
                recallCarRequestToR5.incrementAndGet()
              }
              r5.calcRoute(request)
            } else if (request.streetVehicles.exists(_.mode != CAR)) {
              val r5Response =
                Some(r5.calcRoute(request.copy(streetVehicles = request.streetVehicles.filter(_.mode != CAR))))
              ghResponse.get.copy(
                ghResponse.map(_.itineraries).getOrElse(Seq.empty) ++
                r5Response.map(_.itineraries).getOrElse(Seq.empty)
              )
            } else ghResponse.get

            notTransitRouteRequestExecutionTime.addAndGet(System.currentTimeMillis() - start)
            response
          } else {
            r5.calcRoute(request)
          }
        }
      }
      eventualResponse.recover {
        case e =>
          log.error(e, "calcRoute failed")
          RoutingFailure(e, request.requestId)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      log.debug("===================================================================")
      log.debug(
        s"TOTAL NOT TRANSIT CAR ROUTING REQUESTS: ${notTransitCarRouteRequestCounter
          .get()}, TOTAL NOT TRANSIT EXECUTION TIME: ${notTransitRouteRequestExecutionTime
          .get()}, TOTAL RECALL R5 FOR CAR MODE REQUESTS: ${recallCarRequestToR5.get()}"
      )
      log.debug("===================================================================")
      notTransitRouteRequestExecutionTime.set(0)
      notTransitCarRouteRequestCounter.set(0)
      recallCarRequestToR5.set(0)

      if (carRouter == "quasiDynamicGH") {
        createGraphHoppers(Some(newTravelTime))
      }

      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info("{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      log.debug("===================================================================")
      log.debug(
        s"TOTAL NOT TRANSIT CAR ROUTING REQUESTS: ${notTransitCarRouteRequestCounter
          .get()}, TOTAL NOT TRANSIT EXECUTION TIME: ${notTransitRouteRequestExecutionTime
          .get()}, TOTAL RECALL R5 FOR CAR MODE REQUESTS: ${recallCarRequestToR5.get()}"
      )
      log.debug("===================================================================")
      notTransitRouteRequestExecutionTime.set(0)
      notTransitCarRouteRequestCounter.set(0)
      recallCarRequestToR5.set(0)
      val newTravelTime =
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(workerParams.beamConfig.beam.agentsim.timeBinSize, map)
      if (carRouter == "quasiDynamicGH") {
        createGraphHoppers(Some(newTravelTime))
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
        embodyRequestId: Int
        ) =>
      val response: RoutingResponse = r5.embodyWithCurrentTravelTime(leg, vehicleId, vehicleTypeId, embodyRequestId)
      sender ! response
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  private def createGraphHoppers(travelTime: Option[TravelTime] = None): Unit = {
    new Directory(new File(graphHopperDir)).deleteRecursively()

    val graphHopperInstances = if (carRouter == "quasiDynamicGH") noOfTimeBins else 1

    val futures = (0 until graphHopperInstances).map { i =>
      Future {
        val ghDir = Paths.get(graphHopperDir, i.toString).toString

        val wayId2TravelTime = travelTime
          .map { times =>
            workerParams.networkHelper.allLinks.toSeq
              .map(
                l =>
                  l.getId.toString.toLong ->
                  times.getLinkTravelTime(l, i * workerParams.beamConfig.beam.agentsim.timeBinSize, null, null)
              )
              .toMap
          }
          .getOrElse(Map.empty)

        GraphHopperWrapper.createGraphDirectoryFromR5(
          carRouter,
          workerParams.transportNetwork,
          new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
          ghDir,
          wayId2TravelTime
        )

        i -> new GraphHopperWrapper(
          carRouter,
          ghDir,
          workerParams.geo,
          workerParams.vehicleTypes,
          workerParams.fuelTypePrices,
          wayId2TravelTime,
          id2Link
        )
      }
    }

    graphHoppers = Await.result(Future.sequence(futures), 20.minutes).toMap
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
    val distanceInMeters = GeoUtils.minkowskiDistFormula(startUTM, endUTM) //changed from geo.distUTMInMeters(startUTM, endUTM)
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
      )
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
