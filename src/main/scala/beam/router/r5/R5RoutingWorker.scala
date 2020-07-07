package beam.router.r5

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadLocalRandom}
import java.util.{Collections, Optional}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.choice.mode.{DrivingCost, PtFares}
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.WALK
import beam.router.Modes._
import beam.router._
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.model.BeamLeg._
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{EmbodiedBeamTrip, RoutingModel, _}
import beam.router.osm.TollCalculator
import beam.router.r5.R5RoutingWorker.{createBushwackingBeamLeg, R5Request, StopVisitor}
import beam.sim.BeamScenario
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile}
import beam.utils._
import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.{TransitLayer, TransportNetwork}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import gnu.trove.map.TIntIntMap
import gnu.trove.map.hash.TIntIntHashMap
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Try

case class WorkerParameters(
  beamConfig: BeamConfig,
  transportNetwork: TransportNetwork,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  fuelTypePrices: Map[FuelType, Double],
  ptFares: PtFares,
  geo: GeoUtils,
  dates: DateUtils,
  networkHelper: NetworkHelper,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator
)

object WorkerParameters {

  def fromConfig(config: Config): WorkerParameters = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.init()
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    LoggingUtil.initLogger(outputDirectory, beamConfig.beam.logger.keepConsoleAppenderOn)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(networkCoordinator.network)
    val dates: DateUtils = DateUtils(
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
    )
    val geo = new GeoUtilsImpl(beamConfig)
    val vehicleTypes = readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    val fuelTypePrices = readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap
    val ptFares = PtFares(beamConfig.beam.agentsim.agents.ptFare.filePath)
    val fareCalculator = new FareCalculator(beamConfig)
    val tollCalculator = new TollCalculator(beamConfig)
    BeamRouter.checkForConsistentTimeZoneOffsets(dates, networkCoordinator.transportNetwork)
    WorkerParameters(
      beamConfig,
      networkCoordinator.transportNetwork,
      vehicleTypes,
      fuelTypePrices,
      ptFares,
      geo,
      dates,
      new NetworkHelperImpl(networkCoordinator.network),
      fareCalculator,
      tollCalculator
    )
  }
}

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
      val eventualResponse = Future {
        latency("request-router-time", Metrics.RegularLevel) {
          r5.calcRoute(request)
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

class R5Wrapper(workerParams: WorkerParameters, travelTime: TravelTime, travelTimeNoiseFraction: Double)
    extends MetricsSupport {
  private val maxDistanceForBikeMeters: Int =
    workerParams.beamConfig.beam.routing.r5.maxDistanceLimitByModeInMeters.bike

  private val WorkerParameters(
    beamConfig,
    transportNetwork,
    vehicleTypes,
    fuelTypePrices,
    ptFares,
    geo,
    dates,
    networkHelper,
    fareCalculator,
    tollCalculator
  ) = workerParams

  private val maxFreeSpeed = networkHelper.allLinks.map(_.getFreespeed).max

  def embodyWithCurrentTravelTime(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    embodyRequestId: Int
  ): RoutingResponse = {
    val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
      leg.travelPath.linkIds,
      leg.startTime,
      travelTimeByLinkCalculator(vehicleTypes(vehicleTypeId), shouldAddNoise = false),
      toR5StreetMode(leg.mode),
      transportNetwork.streetLayer
    )
    val startLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.head)
    val endLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.last)
    val duration = linksTimesAndDistances.travelTimes.tail.sum
    val updatedTravelPath = BeamPath(
      linksTimesAndDistances.linkIds,
      linksTimesAndDistances.travelTimes,
      None,
      SpaceTime(startLoc.getX, startLoc.getY, leg.startTime),
      SpaceTime(
        endLoc.getX,
        endLoc.getY,
        leg.startTime + Math.round(duration.toFloat)
      ),
      distanceInM = linksTimesAndDistances.distances.tail.sum
    )
    val toll = tollCalculator.calcTollByLinkIds(updatedTravelPath)
    val updatedLeg = leg.copy(travelPath = updatedTravelPath, duration = updatedTravelPath.duration)
    val drivingCost = DrivingCost.estimateDrivingCost(updatedLeg, vehicleTypes(vehicleTypeId), fuelTypePrices)
    val totalCost = drivingCost + (if (updatedLeg.mode == BeamMode.CAR) toll else 0)
    val response = RoutingResponse(
      Vector(
        EmbodiedBeamTrip(
          Vector(
            EmbodiedBeamLeg(
              updatedLeg,
              vehicleId,
              vehicleTypeId,
              asDriver = true,
              totalCost,
              unbecomeDriverOnCompletion = true
            )
          )
        )
      ),
      embodyRequestId,
      None,
      isEmbodyWithCurrentTravelTime = true
    )
    response
  }

  private def getStreetPlanFromR5(request: R5Request): ProfileResponse = {
    countOccurrence("r5-plans-count", request.time)

    val profileRequest = createProfileRequest
    profileRequest.fromLon = request.from.getX
    profileRequest.fromLat = request.from.getY
    profileRequest.toLon = request.to.getX
    profileRequest.toLat = request.to.getY
    profileRequest.fromTime = request.time
    profileRequest.toTime = request.time + 61 // Important to allow 61 seconds for transit schedules to be considered!
    profileRequest.directModes = if (request.directMode == null) {
      util.EnumSet.noneOf(classOf[LegMode])
    } else {
      util.EnumSet.of(request.directMode)
    }

    try {
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (mode <- profileRequest.directModes.asScala) {
        val streetRouter = new StreetRouter(
          transportNetwork.streetLayer,
          travelTimeCalculator(
            vehicleTypes(request.beamVehicleTypeId),
            profileRequest.fromTime,
            shouldAddNoise = !profileRequest.hasTransit
          ), // Add error if it is not transit
          turnCostCalculator,
          travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
        )
        if (request.accessMode == LegMode.BICYCLE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        streetRouter.profileRequest = profileRequest
        streetRouter.streetMode = toR5StreetMode(mode)
        streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
        if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
          if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
            latency("route-transit-time", Metrics.VerboseLevel) {
              streetRouter.route() //latency 1
            }
            val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
            if (lastState != null) {
              val streetPath = new StreetPath(lastState, transportNetwork, false)
              val streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer)
              directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
            }
          }
        }
      }
      directOption.summary = directOption.generateSummary
      profileResponse.addOption(directOption)
      profileResponse.recomputeStats(profileRequest)
      profileResponse
    } catch {
      case _: IllegalStateException =>
        new ProfileResponse
      case _: ArrayIndexOutOfBoundsException =>
        new ProfileResponse
    }
  }

  private def createProfileRequest = {
    val profileRequest = new ProfileRequest()
    // Warning: carSpeed is not used for link traversal (rather, the OSM travel time model is used),
    // but for R5-internal bushwhacking from network to coordinate, AND ALSO for the A* remaining weight heuristic,
    // which means that this value must be an over(!)estimation, otherwise we will miss optimal routes,
    // particularly in the presence of tolls.
    profileRequest.carSpeed = maxFreeSpeed.toFloat
    profileRequest.maxWalkTime = 30
    profileRequest.maxCarTime = 30
    profileRequest.maxBikeTime = 30
    // Maximum number of transit segments. This was previously hardcoded as 4 in R5, now it is a parameter
    // that defaults to 8 unless I reset it here. It is directly related to the amount of work the
    // transit router has to do.
    profileRequest.maxRides = 4
    profileRequest.streetTime = 2 * 60
    profileRequest.maxTripDurationMinutes = 4 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    profileRequest.monteCarloDraws = beamConfig.beam.routing.r5.numberOfSamples
    profileRequest.date = dates.localBaseDate
    // Doesn't calculate any fares, is just a no-op placeholder
    profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator
    profileRequest.suboptimalMinutes = 0
    profileRequest
  }

  def calcRoute(request: RoutingRequest): RoutingResponse = {
    //    log.debug(routingRequest.toString)

    // For each street vehicle (including body, if available): Route from origin to street vehicle, from street vehicle to destination.
    val isRouteForPerson = request.streetVehicles.exists(_.mode == WALK)

    def calcRouteToVehicle(vehicle: StreetVehicle): Option[EmbodiedBeamLeg] = {
      val mainRouteFromVehicle = request.streetVehiclesUseIntermodalUse == Access && isRouteForPerson && vehicle.mode != WALK
      if (mainRouteFromVehicle) {
        val body = request.streetVehicles.find(_.mode == WALK).get
        if (geo.distUTMInMeters(vehicle.locationUTM.loc, request.originUTM) > beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
          val from = geo.snapToR5Edge(
            transportNetwork.streetLayer,
            geo.utm2Wgs(request.originUTM),
            10E3
          )
          val to = geo.snapToR5Edge(
            transportNetwork.streetLayer,
            geo.utm2Wgs(vehicle.locationUTM.loc),
            10E3
          )
          val directMode = LegMode.WALK
          val accessMode = LegMode.WALK
          val egressMode = LegMode.WALK
          val profileResponse =
            latency("walkToVehicleRoute-router-time", Metrics.RegularLevel) {
              getStreetPlanFromR5(
                R5Request(
                  from,
                  to,
                  request.departureTime,
                  directMode,
                  accessMode,
                  withTransit = false,
                  egressMode,
                  request.timeValueOfMoney,
                  body.vehicleTypeId
                )
              )
            }
          if (profileResponse.options.isEmpty) {
            Some(
              EmbodiedBeamLeg(
                createBushwackingBeamLeg(request.departureTime, from, to, geo),
                body.id,
                body.vehicleTypeId,
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              )
            )
          } else {
            val streetSegment = profileResponse.options.get(0).access.get(0)
            Some(
              buildStreetBasedLegs(
                streetSegment,
                request.departureTime,
                body,
                unbecomeDriverOnCompletion = false
              )
            )
          }
        } else {
          Some(
            EmbodiedBeamLeg(
              dummyLeg(request.departureTime, geo.utm2Wgs(vehicle.locationUTM.loc)),
              body.id,
              body.vehicleTypeId,
              body.asDriver,
              0.0,
              unbecomeDriverOnCompletion = false
            )
          )
        }
      } else {
        None
      }
    }

    def routeFromVehicleToDestination(vehicle: StreetVehicle) = {
      // assume 13 mph / 5.8 m/s as average PT speed: http://cityobservatory.org/urban-buses-are-slowing-down/
      val estimateDurationToGetToVeh: Int = math
        .round(geo.distUTMInMeters(request.originUTM, vehicle.locationUTM.loc) / 5.8)
        .intValue()
      val time = request.departureTime + estimateDurationToGetToVeh
      val from = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(vehicle.locationUTM.loc),
        10E3
      )
      val to = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(request.destinationUTM),
        10E3
      )
      val directMode = vehicle.mode.r5Mode.get.left.get
      val accessMode = vehicle.mode.r5Mode.get.left.get
      val egressMode = LegMode.WALK
      val profileResponse =
        latency("vehicleOnEgressRoute-router-time", Metrics.RegularLevel) {
          getStreetPlanFromR5(
            R5Request(
              from,
              to,
              time,
              directMode,
              accessMode,
              withTransit = false,
              egressMode,
              request.timeValueOfMoney,
              vehicle.vehicleTypeId
            )
          )
        }
      if (!profileResponse.options.isEmpty) {
        val streetSegment = profileResponse.options.get(0).access.get(0)
        buildStreetBasedLegs(
          streetSegment,
          time,
          vehicle,
          unbecomeDriverOnCompletion = true
        )
      } else {
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(request.departureTime, from, to, geo),
          vehicle.id,
          vehicle.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = true
        )
      }
    }

    /*
     * Our algorithm captures a few different patterns of travel. Two of these require extra routing beyond what we
     * call the "main" route calculation below. In both cases, we have a single main transit route
     * which is only calculate once in the code below. But we optionally add a WALK leg from the origin to the
     * beginning of the route (called "mainRouteFromVehicle" as opposed to main route from origin). Or we optionally
     * add a vehicle-based trip on the egress portion of the trip (called "mainRouteToVehicle" as opposed to main route
     * to destination).
     *
     * Or we use the R5 egress concept to accomplish "mainRouteRideHailTransit" pattern we use DRIVE mode on both
     * access and egress legs. For the other "mainRoute" patterns, we want to fix the location of the vehicle, not
     * make it dynamic. Also note that in all cases, these patterns are only the result of human travelers, we assume
     * AI is fixed to a vehicle and therefore only needs the simplest of routes.
     *
     * For the mainRouteFromVehicle pattern, the traveler is using a vehicle within the context of a
     * trip that could be multimodal (e.g. drive to transit) or unimodal (drive only). We don't assume the vehicle is
     * co-located with the person, so this first block of code determines the distance from the vehicle to the person and based
     * on a threshold, optionally routes a WALK leg to the vehicle and adjusts the main route location & time accordingly.
     *
     */
    val mainRouteToVehicle = request.streetVehiclesUseIntermodalUse == Egress && isRouteForPerson
    val mainRouteRideHailTransit = request.streetVehiclesUseIntermodalUse == AccessAndEgress && isRouteForPerson

    val profileRequest = createProfileRequest
    val accessVehicles = if (mainRouteToVehicle) {
      Vector(request.streetVehicles.find(_.mode == WALK).get)
    } else if (mainRouteRideHailTransit) {
      request.streetVehicles.filter(_.mode != WALK)
    } else {
      request.streetVehicles
    }

    val maybeWalkToVehicle: Map[StreetVehicle, Option[EmbodiedBeamLeg]] =
      accessVehicles.map(v => v -> calcRouteToVehicle(v)).toMap

    val bestAccessVehiclesByR5Mode: Map[LegMode, StreetVehicle] = accessVehicles
      .groupBy(_.mode.r5Mode.get.left.get)
      .mapValues(vehicles => vehicles.minBy(maybeWalkToVehicle(_).map(leg => leg.beamLeg.duration).getOrElse(0)))

    val egressVehicles = if (mainRouteRideHailTransit) {
      request.streetVehicles.filter(_.mode != WALK)
    } else if (request.withTransit) {
      Vector(request.streetVehicles.find(_.mode == WALK).get)
    } else {
      Vector()
    }
    val destinationVehicles = if (mainRouteToVehicle) {
      request.streetVehicles.filter(_.mode != WALK)
    } else {
      Vector()
    }
    if (request.withTransit) {
      profileRequest.transitModes = util.EnumSet.allOf(classOf[TransitModes])
    }

    val destinationVehicle = destinationVehicles.headOption
    val vehicleToDestinationLeg = destinationVehicle.map(v => routeFromVehicleToDestination(v))

    val accessRouters = mutable.Map[LegMode, StreetRouter]()
    val accessStopsByMode = mutable.Map[LegMode, StopVisitor]()
    val profileResponse = new ProfileResponse
    val directOption = new ProfileOption
    profileRequest.reverseSearch = false
    for (vehicle <- bestAccessVehiclesByR5Mode.values) {
      val theOrigin = if (mainRouteToVehicle || mainRouteRideHailTransit) {
        request.originUTM
      } else {
        vehicle.locationUTM.loc
      }
      val theDestination = if (mainRouteToVehicle) {
        destinationVehicle.get.locationUTM.loc
      } else {
        request.destinationUTM
      }
      val from = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(theOrigin),
        10E3
      )
      val to = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(theDestination),
        10E3
      )
      profileRequest.fromLon = from.getX
      profileRequest.fromLat = from.getY
      profileRequest.toLon = to.getX
      profileRequest.toLat = to.getY
      val walkToVehicleDuration = maybeWalkToVehicle(vehicle).map(leg => leg.beamLeg.duration).getOrElse(0)
      profileRequest.fromTime = request.departureTime + walkToVehicleDuration
      profileRequest.toTime = profileRequest.fromTime + 61 // Important to allow 61 seconds for transit schedules to be considered!
      val streetRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator(
          vehicleTypes(vehicle.vehicleTypeId),
          profileRequest.fromTime,
          shouldAddNoise = !profileRequest.hasTransit()
        ),
        turnCostCalculator,
        travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
      )
      if (vehicle.mode == BeamMode.BIKE) {
        streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
      }
      streetRouter.profileRequest = profileRequest
      streetRouter.streetMode = toR5StreetMode(vehicle.mode)
      if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
        if (profileRequest.hasTransit) {
          val destinationSplit = transportNetwork.streetLayer.findSplit(
            profileRequest.toLat,
            profileRequest.toLon,
            StreetLayer.LINK_RADIUS_METERS,
            streetRouter.streetMode
          )
          val stopVisitor = new StopVisitor(
            transportNetwork.streetLayer,
            streetRouter.quantityToMinimize,
            streetRouter.transitStopSearchQuantity,
            profileRequest.getMinTimeLimit(streetRouter.streetMode),
            destinationSplit
          )
          streetRouter.setRoutingVisitor(stopVisitor)
          streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(vehicle.mode.r5Mode.get.left.get)
          streetRouter.route()
          accessRouters.put(vehicle.mode.r5Mode.get.left.get, streetRouter)
          accessStopsByMode.put(vehicle.mode.r5Mode.get.left.get, stopVisitor)
          if (!mainRouteRideHailTransit) {
            // Not interested in direct options in the ride-hail-transit case,
            // only in the option where we actually use non-empty ride-hail for access and egress.
            // This is only for saving a computation, and only because the requests are structured like they are.
            if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
              val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
              if (lastState != null) {
                val streetPath = new StreetPath(lastState, transportNetwork, false)
                val streetSegment =
                  new StreetSegment(streetPath, vehicle.mode.r5Mode.get.left.get, transportNetwork.streetLayer)
                directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
              } else if (profileRequest.streetTime * 60 > streetRouter.timeLimitSeconds) {
                val streetRouter = new StreetRouter(
                  transportNetwork.streetLayer,
                  travelTimeCalculator(
                    vehicleTypes(vehicle.vehicleTypeId),
                    profileRequest.fromTime,
                    shouldAddNoise = !profileRequest.hasTransit
                  ),
                  turnCostCalculator,
                  travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
                )
                if (vehicle.mode == BeamMode.BIKE) {
                  streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
                }
                streetRouter.profileRequest = profileRequest
                streetRouter.streetMode = toR5StreetMode(vehicle.mode)
                streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
                streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
                streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)
                streetRouter.route()
                val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
                if (lastState != null) {
                  val streetPath = new StreetPath(lastState, transportNetwork, false)
                  val streetSegment =
                    new StreetSegment(streetPath, vehicle.mode.r5Mode.get.left.get, transportNetwork.streetLayer)
                  directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
                }
              }
            }
          }
        } else if (!mainRouteRideHailTransit) {
          streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
          if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
            streetRouter.route()
            val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
            if (lastState != null) {
              val streetPath = new StreetPath(lastState, transportNetwork, false)
              val streetSegment =
                new StreetSegment(streetPath, vehicle.mode.r5Mode.get.left.get, transportNetwork.streetLayer)
              directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
            }
          }
        }
      }
    }
    directOption.summary = directOption.generateSummary
    profileResponse.addOption(directOption)

    if (profileRequest.hasTransit) {
      val egressRouters = mutable.Map[LegMode, StreetRouter]()
      val egressStopsByMode = mutable.Map[LegMode, StopVisitor]()
      profileRequest.reverseSearch = true
      for (vehicle <- egressVehicles) {
        val theDestination = if (mainRouteToVehicle) {
          destinationVehicle.get.locationUTM.loc
        } else {
          request.destinationUTM
        }
        val to = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(theDestination),
          10E3
        )
        profileRequest.toLon = to.getX
        profileRequest.toLat = to.getY
        val streetRouter = new StreetRouter(
          transportNetwork.streetLayer,
          travelTimeCalculator(
            vehicleTypes(vehicle.vehicleTypeId),
            profileRequest.fromTime,
            shouldAddNoise = !profileRequest.hasTransit
          ),
          turnCostCalculator,
          travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
        )
        if (vehicle.mode == BeamMode.BIKE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        streetRouter.streetMode = toR5StreetMode(vehicle.mode)
        streetRouter.profileRequest = profileRequest
        streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(vehicle.mode.r5Mode.get.left.get)
        val destinationSplit = transportNetwork.streetLayer.findSplit(
          profileRequest.fromLat,
          profileRequest.fromLon,
          StreetLayer.LINK_RADIUS_METERS,
          streetRouter.streetMode
        )
        val stopVisitor = new StopVisitor(
          transportNetwork.streetLayer,
          streetRouter.quantityToMinimize,
          streetRouter.transitStopSearchQuantity,
          profileRequest.getMinTimeLimit(streetRouter.streetMode),
          destinationSplit
        )
        streetRouter.setRoutingVisitor(stopVisitor)
        if (streetRouter.setOrigin(profileRequest.toLat, profileRequest.toLon)) {
          streetRouter.route()
          egressRouters.put(vehicle.mode.r5Mode.get.left.get, streetRouter)
          egressStopsByMode.put(vehicle.mode.r5Mode.get.left.get, stopVisitor)
        }
      }

      val transitPaths = latency("getpath-transit-time", Metrics.VerboseLevel) {
        profileRequest.fromTime = request.departureTime
        profileRequest.toTime = request.departureTime + 61 // Important to allow 61 seconds for transit schedules to be considered!
        val router = new McRaptorSuboptimalPathProfileRouter(
          transportNetwork,
          profileRequest,
          accessStopsByMode.mapValues(_.stops).asJava,
          egressStopsByMode.mapValues(_.stops).asJava,
          (departureTime: Int) =>
            new BeamDominatingList(
              profileRequest.inRoutingFareCalculator,
              Integer.MAX_VALUE,
              departureTime + profileRequest.maxTripDurationMinutes * 60
          ),
          null
        )
        Try(router.getPaths.asScala).getOrElse(Nil) // Catch IllegalStateException in R5.StatsCalculator
      }

      for (transitPath <- transitPaths) {
        profileResponse.addTransitPath(
          accessRouters.asJava,
          egressRouters.asJava,
          transitPath,
          transportNetwork,
          profileRequest.getFromTimeDateZD
        )
      }

      latency("transfer-transit-time", Metrics.VerboseLevel) {
        profileResponse.generateStreetTransfers(transportNetwork, profileRequest)
      }
    }
    profileResponse.recomputeStats(profileRequest)

    val embodiedTrips = profileResponse.options.asScala.flatMap { option =>
      option.itinerary.asScala
        .map { itinerary =>
          // Using itinerary start as access leg's startTime
          val tripStartTime = dates
            .toBaseMidnightSeconds(
              itinerary.startTime,
              transportNetwork.transitLayer.routes.size() == 0
            )
            .toInt

          var arrivalTime: Int = Int.MinValue
          val embodiedBeamLegs = mutable.ArrayBuffer.empty[EmbodiedBeamLeg]
          val access = option.access.get(itinerary.connection.access)
          val vehicle = bestAccessVehiclesByR5Mode(access.mode)
          maybeWalkToVehicle(vehicle).foreach(walkLeg => {
            // Glue the walk to vehicle in front of the trip without a gap
            embodiedBeamLegs += walkLeg
              .copy(beamLeg = walkLeg.beamLeg.updateStartTime(tripStartTime - walkLeg.beamLeg.duration))
          })

          embodiedBeamLegs += buildStreetBasedLegs(
            access,
            tripStartTime,
            vehicle,
            unbecomeDriverOnCompletion = access.mode != LegMode.WALK || option.transit == null
          )

          arrivalTime = embodiedBeamLegs.last.beamLeg.endTime

          val transitSegments = Optional.ofNullable(option.transit).orElse(Collections.emptyList()).asScala
          val transitJourneyIDs =
            Optional.ofNullable(itinerary.connection.transit).orElse(Collections.emptyList()).asScala
          // Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
          // assuming that: For each transit in option there is a TransitJourneyID in connection
          val segments = transitSegments zip transitJourneyIDs

          // Lazy because this looks expensive and we may not need it because there's _another_ fare
          // calculation that takes precedence
          lazy val fares = latency("fare-transit-time", Metrics.VerboseLevel) {
            val fareSegments = getFareSegments(segments.toVector)
            filterFaresOnTransfers(fareSegments)
          }
          segments.foreach {
            case (transitSegment, transitJourneyID) =>
              val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
              val tripPattern = profileResponse.getPatterns.asScala
                .find { tp =>
                  tp.getTripPatternIdx == segmentPattern.patternIdx
                }
                .getOrElse(throw new RuntimeException())
              val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
              val route = transportNetwork.transitLayer.routes.get(tripPattern.getRouteIdx)
              val fromStop = tripPattern.getStops.get(segmentPattern.fromIndex)
              val toStop = tripPattern.getStops.get(segmentPattern.toIndex)
              val startTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              val endTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              val stopSequence =
                tripPattern.getStops.asScala.toList.slice(segmentPattern.fromIndex, segmentPattern.toIndex + 1)
              val segmentLeg = BeamLeg(
                startTime,
                Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type)),
                java.time.temporal.ChronoUnit.SECONDS
                  .between(
                    segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                    segmentPattern.toArrivalTime.get(transitJourneyID.time)
                  )
                  .toInt,
                BeamPath(
                  Vector(),
                  Vector(),
                  Some(
                    TransitStopsInfo(
                      route.agency_id,
                      tripPattern.getRouteId,
                      Id.createVehicleId(tripId),
                      segmentPattern.fromIndex,
                      segmentPattern.toIndex
                    )
                  ),
                  SpaceTime(fromStop.lon, fromStop.lat, startTime),
                  SpaceTime(toStop.lon, toStop.lat, endTime),
                  stopSequence.sliding(2).map(x => getDistanceBetweenStops(x.head, x.last)).sum
                )
              )
              embodiedBeamLegs += EmbodiedBeamLeg(
                segmentLeg,
                segmentLeg.travelPath.transitStops.get.vehicleId,
                null,
                asDriver = false,
                ptFares
                  .getPtFare(
                    Some(segmentLeg.travelPath.transitStops.get.agencyId),
                    Some(segmentLeg.travelPath.transitStops.get.routeId),
                    request.attributesOfIndividual.flatMap(_.age)
                  )
                  .getOrElse {
                    val fs =
                      fares.view
                        .filter(_.patternIndex == segmentPattern.patternIdx)
                        .map(_.fare.price)
                    if (fs.nonEmpty) fs.min else 0.0
                  },
                unbecomeDriverOnCompletion = false
              )
              arrivalTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              if (transitSegment.middle != null) {
                val body = request.streetVehicles.find(_.mode == WALK).get
                embodiedBeamLegs += buildStreetBasedLegs(
                  transitSegment.middle,
                  arrivalTime,
                  body,
                  unbecomeDriverOnCompletion = false
                )
                arrivalTime = arrivalTime + transitSegment.middle.duration
              }
          }

          if (itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            val vehicle = egressVehicles.find(v => v.mode.r5Mode.get.left.get == egress.mode).get
            embodiedBeamLegs += buildStreetBasedLegs(
              egress,
              arrivalTime,
              vehicle,
              unbecomeDriverOnCompletion = true
            )
            val body = request.streetVehicles.find(_.mode == WALK).get
            if (isRouteForPerson && egress.mode != LegMode.WALK) {
              embodiedBeamLegs += EmbodiedBeamLeg(
                dummyLeg(arrivalTime + egress.duration, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
                body.id,
                body.vehicleTypeId,
                body.asDriver,
                0.0,
                unbecomeDriverOnCompletion = true
              )
            }
          }

          vehicleToDestinationLeg.foreach { legWithFare =>
            // Glue the drive to the final destination behind the trip without a gap
            embodiedBeamLegs += legWithFare.copy(
              beamLeg = legWithFare.beamLeg.updateStartTime(embodiedBeamLegs.last.beamLeg.endTime)
            )
          }
          if (isRouteForPerson && embodiedBeamLegs.last.beamLeg.mode != WALK) {
            val body = request.streetVehicles.find(_.mode == WALK).get
            embodiedBeamLegs += EmbodiedBeamLeg(
              dummyLeg(embodiedBeamLegs.last.beamLeg.endTime, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
              body.id,
              body.vehicleTypeId,
              body.asDriver,
              0.0,
              unbecomeDriverOnCompletion = true
            )
          }
          EmbodiedBeamTrip(embodiedBeamLegs)
        }
        .filter { trip: EmbodiedBeamTrip =>
          //TODO make a more sensible window not just 30 minutes
          trip.legs.head.beamLeg.startTime >= request.departureTime && trip.legs.head.beamLeg.startTime <= request.departureTime + 1800
        }
    }

    if (!embodiedTrips.exists(_.tripClassifier == WALK) && !mainRouteToVehicle) {
      val maybeBody = accessVehicles.find(_.mode == WALK)
      if (maybeBody.isDefined) {
        val dummyTrip = R5RoutingWorker.createBushwackingTrip(
          new Coord(request.originUTM.getX, request.originUTM.getY),
          new Coord(request.destinationUTM.getX, request.destinationUTM.getY),
          request.departureTime,
          maybeBody.get,
          geo
        )
        RoutingResponse(
          embodiedTrips :+ dummyTrip,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false
        )
      } else {
        RoutingResponse(
          embodiedTrips,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false
        )
      }
    } else {
      RoutingResponse(embodiedTrips, request.requestId, Some(request), isEmbodyWithCurrentTravelTime = false)
    }
  }

  private def getDistanceBetweenStops(fromStop: Stop, toStop: Stop): Double = {
    geo.distLatLon2Meters(new Coord(fromStop.lon, fromStop.lat), new Coord(toStop.lon, toStop.lat))
  }

  private def buildStreetBasedLegs(
    segment: StreetSegment,
    tripStartTime: Int,
    vehicle: StreetVehicle,
    unbecomeDriverOnCompletion: Boolean
  ): EmbodiedBeamLeg = {
    val startPoint = SpaceTime(
      segment.geometry.getStartPoint.getX,
      segment.geometry.getStartPoint.getY,
      tripStartTime
    )
    val endCoord = new Coord(
      segment.geometry.getEndPoint.getX,
      segment.geometry.getEndPoint.getY,
    )

    var activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds += edge.edgeId.intValue()
    }
    val beamLeg: BeamLeg =
      createBeamLeg(vehicle.vehicleTypeId, startPoint, endCoord, segment.mode, activeLinkIds)
    val toll = if (segment.mode == LegMode.CAR) {
      val osm = segment.streetEdges.asScala
        .map(
          e =>
            transportNetwork.streetLayer.edgeStore
              .getCursor(e.edgeId)
              .getOSMID
        )
        .toVector
      tollCalculator.calcTollByOsmIds(osm) + tollCalculator.calcTollByLinkIds(beamLeg.travelPath)
    } else 0.0
    val drivingCost = if (segment.mode == LegMode.CAR) {
      DrivingCost.estimateDrivingCost(beamLeg, vehicleTypes(vehicle.vehicleTypeId), fuelTypePrices)
    } else 0.0
    EmbodiedBeamLeg(
      beamLeg,
      vehicle.id,
      vehicle.vehicleTypeId,
      vehicle.asDriver,
      drivingCost + toll,
      unbecomeDriverOnCompletion
    )
  }

  def createBeamLeg(
    vehicleTypeId: Id[BeamVehicleType],
    startPoint: SpaceTime,
    endCoord: Coord,
    legMode: LegMode,
    activeLinkIds: IndexedSeq[Int]
  ): BeamLeg = {
    val tripStartTime: Int = startPoint.time
    // During routing `travelTimeByLinkCalculator` is used with shouldAddNoise = true (if it is not transit)
    // That trick gives us back diverse route. Now we want to compute travel time per link and we don't want to include that noise
    val linksTimesDistances = RoutingModel.linksToTimeAndDistance(
      activeLinkIds,
      tripStartTime,
      travelTimeByLinkCalculator(vehicleTypes(vehicleTypeId), shouldAddNoise = false), // Do not add noise!
      toR5StreetMode(legMode),
      transportNetwork.streetLayer
    )
    val distance = linksTimesDistances.distances.tail.sum // note we exclude the first link to keep with MATSim convention
    val theTravelPath = BeamPath(
      linkIds = activeLinkIds,
      linkTravelTime = linksTimesDistances.travelTimes,
      transitStops = None,
      startPoint = startPoint,
      endPoint = SpaceTime(endCoord, startPoint.time + math.round(linksTimesDistances.travelTimes.tail.sum.toFloat)),
      distanceInM = distance
    )
    val beamLeg = BeamLeg(
      tripStartTime,
      mapLegMode(legMode),
      theTravelPath.duration,
      travelPath = theTravelPath
    )
    beamLeg
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments IndexedSeq[(TransitSegment, TransitJourneyID)]
    * @return a collection of FareSegments for an itinerary.
    */
  private def getFareSegments(
    segments: IndexedSeq[(TransitSegment, TransitJourneyID)]
  ): IndexedSeq[BeamFareSegment] = {
    segments
      .groupBy(s => getRoute(s._1, s._2).agency_id)
      .flatMap(t => {
        val pattern = getPattern(t._2.head._1, t._2.head._2)
        val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)

        var rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

        if (rules.isEmpty) {
          val route = getRoute(pattern)
          val agencyId = route.agency_id
          val routeId = route.route_id

          val fromId = getStopId(t._2.head._1.from)
          val toId = getStopId(t._2.last._1.to)

          val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime
            .get(t._2.last._2.time)
          val duration = ChronoUnit.SECONDS.between(fromTime, toTime)

          val containsIds =
            t._2
              .flatMap(s => IndexedSeq(getStopId(s._1.from), getStopId(s._1.to)))
              .toSet

          rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds)
            .map(f => BeamFareSegment(f, pattern.patternIdx, duration))
        }
        rules
      })
      .toIndexedSeq
  }

  private def getFareSegments(
    transitSegment: TransitSegment,
    transitJourneyID: TransitJourneyID,
    fromTime: ZonedDateTime
  ): IndexedSeq[BeamFareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration =
      ChronoUnit.SECONDS
        .between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(
      f => BeamFareSegment(f, pattern.patternIdx, duration)
    )
    if (fr.nonEmpty && fr.forall(_.patternIndex == fr.head.patternIndex))
      fr = Vector(fr.minBy(_.fare.price))
    fr
  }

  private def getFareSegments(
    agencyId: String,
    routeId: String,
    fromId: String,
    toId: String,
    containsIds: Set[String] = null
  ): IndexedSeq[BeamFareSegment] =
    fareCalculator.getFareSegments(agencyId, routeId, fromId, toId, containsIds)

  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes
      .get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

  private def travelTimeCalculator(
    vehicleType: BeamVehicleType,
    startTime: Int,
    shouldAddNoise: Boolean
  ): TravelTimeCalculator = {
    val ttc = travelTimeByLinkCalculator(vehicleType, shouldAddNoise)
    (edge: EdgeStore#Edge, durationSeconds: Int, streetMode: StreetMode, _) =>
      {
        ttc(startTime + durationSeconds, edge.getEdgeIndex, streetMode).floatValue()
      }
  }

  private val travelTimeNoises: Array[Double] = if (travelTimeNoiseFraction == 0.0) {
    Array.empty
  } else {
    Array.fill(1000000) {
      ThreadLocalRandom.current().nextDouble(1 - travelTimeNoiseFraction, 1 + travelTimeNoiseFraction)
    }
  }
  private val noiseIdx: AtomicInteger = new AtomicInteger(0)

  private def travelTimeByLinkCalculator(
    vehicleType: BeamVehicleType,
    shouldAddNoise: Boolean
  ): (Double, Int, StreetMode) => Double = {
    val profileRequest = createProfileRequest
    (time: Double, linkId: Int, streetMode: StreetMode) =>
      {
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
        val maxSpeed: Double = vehicleType.maxVelocity.getOrElse(profileRequest.getSpeedForMode(streetMode))
        val minTravelTime = (edge.getLengthM / maxSpeed).ceil.toInt
        val minSpeed = beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond
        val maxTravelTime = (edge.getLengthM / minSpeed).ceil.toInt
        if (streetMode != StreetMode.CAR) {
          minTravelTime
        } else {
          val link = networkHelper.getLinkUnsafe(linkId)
          assert(link != null)
          val physSimTravelTime = travelTime.getLinkTravelTime(link, time, null, null)
          val physSimTravelTimeWithNoise =
            (if (travelTimeNoiseFraction == 0.0 || !shouldAddNoise) { physSimTravelTime } else {
               val idx = Math.abs(noiseIdx.getAndIncrement() % travelTimeNoises.length)
               physSimTravelTime * travelTimeNoises(idx)
             }).ceil.toInt
          val linkTravelTime = Math.max(physSimTravelTimeWithNoise, minTravelTime)
          Math.min(linkTravelTime, maxTravelTime)
        }
      }
  }

  private val turnCostCalculator: TurnCostCalculator =
    new TurnCostCalculator(transportNetwork.streetLayer, true) {
      override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
    }

  private def travelCostCalculator(timeValueOfMoney: Double, startTime: Int): TravelCostCalculator =
    (edge: EdgeStore#Edge, legDurationSeconds: Int, traversalTimeSeconds: Float) => {
      traversalTimeSeconds + (timeValueOfMoney * tollCalculator.calcTollByLinkId(
        edge.getEdgeIndex,
        startTime + legDurationSeconds
      )).toFloat
    }
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
