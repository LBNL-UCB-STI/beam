package beam.router

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, WALK}
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
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.utils.misc.Time
import org.matsim.vehicles.Vehicle

import java.io.File
import java.nio.file.Paths
import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.{ExecutorService, Executors}
import java.util.concurrent.TimeoutException
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.reflect.io.Directory
import scala.util.{Failure, Success, Try}

class RoutingWorker(workerParams: R5Parameters, networks2: Option[(TransportNetwork, Network)])
    extends Actor
    with ActorLogging
    with MetricsSupport {

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

  private val routingTimeout: Option[FiniteDuration] = {
    val timeoutMs = workerParams.beamConfig.beam.routing.r5.routingRequestTimeout
    if (timeoutMs > 0) Some(timeoutMs.milliseconds) else None
  }
  private val slowRoutingWarnThresholdMs: Option[Long] = Some(10L * 1000L)

  log.info("RoutingWorker[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  private def getNameAndHashCode: String = s"RoutingWorker[${hashCode()}], Path: `${self.path}`"

  private def straightLineDistanceMiles(request: RoutingRequest): Double = {
    val dx = request.originUTM.getX - request.destinationUTM.getX
    val dy = request.originUTM.getY - request.destinationUTM.getY
    Math.hypot(dx, dy) / 1609.344
  }

  private def summarizeVehicles(request: RoutingRequest): String = {
    request.streetVehicles
      .map(v => s"${v.id}:${v.mode}:${v.vehicleTypeId}")
      .mkString("[", ", ", "]")
  }

  private def dumpR5WorkerStacks(maxFramesPerThread: Int = 40): String = {
    Thread.getAllStackTraces.asScala.toVector
      .collect {
        case (thread, stack) if thread.getName.startsWith("r5-routing-worker-") =>
          val header =
            s"""thread=${thread.getName}, id=${thread.getId}, state=${thread.getState}"""
          val frames = stack.take(maxFramesPerThread).map(s => s"    at $s").mkString("\n")
          s"$header\n$frames"
      }
      .sortBy { dump =>
        val prefix = "thread=r5-routing-worker-"
        val start = dump.indexOf(prefix)
        if (start < 0) Int.MaxValue
        else {
          val idx = start + prefix.length
          val end = dump.indexOf(",", idx)
          Try(dump.substring(idx, if (end > idx) end else dump.length).toInt).getOrElse(Int.MaxValue)
        }
      }
      .mkString("\n\n")
  }

  private def maybeLogSlowRouting(
    request: RoutingRequest,
    startedAtMs: Long,
    outcome: String,
    maybeError: Option[Throwable] = None
  ): Unit = {
    val elapsedMs = System.currentTimeMillis() - startedAtMs
    slowRoutingWarnThresholdMs.foreach { thresholdMs =>
      if (elapsedMs >= thresholdMs) {
        val base =
          s"[SLOW-ROUTING] duration=${elapsedMs}ms, requestId=${request.requestId}, triggerId=${request.triggerId}, " +
          s"personId=${request.personId.map(_.toString).getOrElse("none")}, withTransit=${request.withTransit}, " +
          s"requestedMode=${request.requestedMode.map(_.toString).getOrElse("None")}, departureTime=${request.departureTime}, " +
          s"origin=(${request.originUTM.getX}, ${request.originUTM.getY}), " +
          s"destination=(${request.destinationUTM.getX}, ${request.destinationUTM.getY}), " +
          f"distanceInMiles=${straightLineDistanceMiles(request)}%.3f, " +
          s"streetVehicles=${summarizeVehicles(request)}, outcome=$outcome"
        maybeError match {
          case Some(err) =>
            log.warning(s"$base, error=${err.getClass.getName}: ${Option(err.getMessage).getOrElse("")}")
          case None => log.warning(base)
        }
      }
    }
  }

  private def withRoutingTimeout(
    request: RoutingRequest,
    future: Future[RoutingResponse],
    routeStartedAtMsFuture: Future[Long]
  ): Future[RoutingResponse] = {
    routingTimeout match {
      case None => future
      case Some(timeout) =>
        val p = Promise[RoutingResponse]()
        routeStartedAtMsFuture.onComplete {
          case Success(routeStartedAtMs) =>
            val timeoutTask = context.system.scheduler.scheduleOnce(timeout) {
              val elapsedMs = System.currentTimeMillis() - routeStartedAtMs
              val stacks = dumpR5WorkerStacks()
              log.error(
                s"[ROUTING-TIMEOUT] requestId=${request.requestId}, triggerId=${request.triggerId}, " +
                s"personId=${request.personId.map(_.toString).getOrElse("none")}, withTransit=${request.withTransit}, " +
                s"requestedMode=${request.requestedMode.map(_.toString).getOrElse("None")}, departureTime=${request.departureTime}, " +
                s"elapsedMs=$elapsedMs, timeoutMs=${timeout.toMillis}, " +
                s"origin=(${request.originUTM.getX}, ${request.originUTM.getY}), destination=(${request.destinationUTM.getX}, ${request.destinationUTM.getY}), " +
                f"distanceInMiles=${straightLineDistanceMiles(request)}%.3f, streetVehicles=${summarizeVehicles(request)}, " +
                s"attributes=${request.attributesOfIndividual.map(_.toString).getOrElse("none")}\n" +
                s"[ROUTING-TIMEOUT-THREAD-DUMP]\n$stacks"
              )
              p.tryFailure(
                new TimeoutException(
                  s"Routing timed out after ${timeout.toMillis}ms for requestId=${request.requestId}, personId=${request.personId
                    .map(_.toString)
                    .getOrElse("none")}"
                )
              )
            }(context.dispatcher)

            future.onComplete { result =>
              timeoutTask.cancel()
              p.tryComplete(result)
            }(executionContext)

          case Failure(err) =>
            p.tryFailure(err)
        }(context.dispatcher)

        p.future
    }
  }

  private var workAssigner: ActorRef = context.parent

  private[beam] var r5: R5Wrapper = new R5Wrapper(
    workerParams,
    new BeamFreeFlowTravelTime(networkHelper = workerParams.networkHelper),
    workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
  )

  private var secondR5: Option[R5Wrapper] = for {
    (transportNetwork, network) <- networks2
  } yield {
    val networkHelperImpl = new NetworkHelperImpl(network)
    new R5Wrapper(
      workerParams.copy(transportNetwork = transportNetwork, networkHelper = networkHelperImpl),
      new BeamFreeFlowTravelTime(networkHelperImpl),
      workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
    )
  }

  private val graphHopperDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "graphhopper").toString
  private val carGraphHopperDir: String = Paths.get(graphHopperDir, "car").toString
  private var binToCarGraphHopper: Map[Int, GraphHopperWrapper] = _
  private var walkGraphHopper: GraphHopperWrapper = _
  private var cchWrapper: CchWrapper = _

  private val linksBelowMinCarSpeed =
    workerParams.networkHelper.allLinks
      .count(l => l.getFreespeed < workerParams.beamConfig.beam.physsim.minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    log.warning(
      "{} links are below minCarSpeedInMetersPerSecond, already in free-flow",
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

    case RoutingWorker.GetR5Wrapper =>
      sender() ! r5

    case request: RoutingRequest =>
      msgs = msgs + 1
      if (firstMsgTime.isEmpty) firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val replyTo = sender()
      val routeStartedAtMs = Promise[Long]()
      val routeFuture = Future {
        routeStartedAtMs.trySuccess(System.currentTimeMillis())
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
            (secondR5, request.withTransit) match {
              case (Some(r52), true) =>
                val resp1 = r5.calcRoute(request)
                val resp2 = r52.calcRoute(request)

                def union(it1: Seq[EmbodiedBeamTrip], it2: Seq[EmbodiedBeamTrip]): Seq[EmbodiedBeamTrip] = {
                  val filteredIt2 = it2.filterNot(trip2 => it1.exists(trip1 => equals(trip1, trip2)))
                  it1 ++ filteredIt2
                }

                def equals(trip1: EmbodiedBeamTrip, trip2: EmbodiedBeamTrip): Boolean = {
                  trip1.tripClassifier == trip2.tripClassifier &&
                  trip1.legs.size == trip2.legs.size &&
                  trip1.totalTravelTimeInSecs == trip2.totalTravelTimeInSecs
                }

                resp1.copy(
                  itineraries = union(resp1.itineraries, resp2.itineraries),
                  computedInMs = resp1.computedInMs + resp2.computedInMs
                )

              case _ =>
                r5.calcRoute(request)
            }
          }
        }
      }
      val eventualResponse = withRoutingTimeout(request, routeFuture, routeStartedAtMs.future)
      def routeStartTimeForLogs: Long =
        routeStartedAtMs.future.value.collect { case Success(ts) => ts }.getOrElse(System.currentTimeMillis())

      eventualResponse.onComplete {
        case Success(_) =>
          maybeLogSlowRouting(request, routeStartTimeForLogs, "success")
        case Failure(err) =>
          maybeLogSlowRouting(request, routeStartTimeForLogs, "failure", Some(err))
      }(executionContext)

      eventualResponse.recover { case e =>
        log.error(e, "calcRoute failed")
        RoutingFailure(e, request)
      } pipeTo replyTo
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
      secondR5 = for {
        (transportNetwork, network) <- networks2
      } yield new R5Wrapper(
        workerParams.copy(transportNetwork = transportNetwork, networkHelper = new NetworkHelperImpl(network)),
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
      secondR5 = for {
        (transportNetwork, network) <- networks2
      } yield new R5Wrapper(
        workerParams.copy(transportNetwork = transportNetwork, networkHelper = new NetworkHelperImpl(network)),
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
  val DEFAULT_CAR_SPEED_IN_METERS_PER_SECOND = 18.0

  case object GetR5Wrapper

  def fromConfig(config: Config) {
    val (workerParams, networks2) = R5Parameters.fromConfig(config)
    new RoutingWorker(workerParams, networks2)
  }

  // 3.1 mph -> 1.38 meter per second, changed from 1 mph
  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    networks2: Option[(TransportNetwork, Network)],
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
      ),
      networks2
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
    geo: GeoUtils,
    mode: BeamMode = WALK
  ): BeamLeg = {
    val spd = mode match {
      case WALK => BUSHWHACKING_SPEED_IN_METERS_PER_SECOND
      case CAR  => DEFAULT_CAR_SPEED_IN_METERS_PER_SECOND
      case _    => BUSHWHACKING_SPEED_IN_METERS_PER_SECOND
    }
    val distanceInMeters =
      GeoUtils.minkowskiDistFormula(startUTM, endUTM) //changed from geo.distUTMInMeters(startUTM, endUTM)
    val bushwhackingTime = Math.round(distanceInMeters / spd)
    val path = BeamPath(
      Array[Int](),
      Array[Double](),
      None,
      SpaceTime(geo.utm2Wgs(startUTM), atTime),
      SpaceTime(geo.utm2Wgs(endUTM), atTime + bushwhackingTime.toInt),
      distanceInMeters
    )
    BeamLeg(atTime, mode, bushwhackingTime.toInt, path)
  }

  def createBushwackingTrip(
    originUTM: Location,
    destUTM: Location,
    atTime: Int,
    vehicle: StreetVehicle,
    geo: GeoUtils,
    mode: BeamMode = WALK,
    unbecomeDriverOnCompletion: Boolean = true
  ): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      Vector(
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(atTime, originUTM, destUTM, geo, mode),
          vehicle.id,
          vehicle.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = unbecomeDriverOnCompletion
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
