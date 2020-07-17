package beam.router.r5

import java.time.{ZoneOffset, ZonedDateTime}
import java.time.temporal.ChronoUnit
import java.util.concurrent.{ExecutorService, Executors}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router._
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.WALK
import beam.router.gtfs.FareCalculator
import beam.router.model.{EmbodiedBeamTrip, _}
import beam.router.osm.TollCalculator
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils._
import com.conveyal.r5.api.util._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.network.Network
import org.matsim.vehicles.{Vehicle, Vehicles}

class R5RoutingWorker(workerParams: WorkerParameters) extends Actor with ActorLogging with MetricsSupport {

  def this(config: Config) {
    this(workerParams = {
      WorkerParameters.fromConfig(config)
    })
  }

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
  private var msgs: Long = 0
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
    askForMoreWork()
  }

  override def postStop(): Unit = {
    tickTask.cancel()
    execSvc.shutdown()
  }

  // Let the dispatcher on which the Future in receive will be running
  // be the dispatcher on which this actor is running.

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
      msgs += 1
      if (firstMsgTime.isEmpty) firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val eventualResponse: Future[RoutingResponse] = Future {
        latency("request-router-time", Metrics.RegularLevel) {
          val routeResponse: RoutingResponse = r5.calcRoute(request)
          // if mode was bike go to every single link Id and if linkId contains bike-dedicated lines
          // undo the change
          routeResponse
        }
      }
      eventualResponse.recover {
        case e =>
          log.error(e, "calcRoute failed")
          RoutingFailure(e, request.requestId)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      r5 = new R5Wrapper(
        workerParams,
        newTravelTime,
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info(s"{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      r5 = new R5Wrapper(
        workerParams,
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(workerParams.beamConfig.beam.agentsim.timeBinSize, map),
        workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
      )
      log.info(
        s"{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
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
}

object R5RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 1.38

  // 3.1 mph -> 1.38 meter per second, changed from 1 mph
  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    network: Network,
    networkHelper: NetworkHelper,
    scenario: Scenario,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator,
    transitVehicles: Vehicles
  ): Props = Props(
    new R5RoutingWorker(
      WorkerParameters(
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
