package beam.router.r5

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util
import java.util.concurrent.{ExecutorService, Executors}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.choice.mode.{DrivingCost, PtFares}
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.Modes._
import beam.router._
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.model.BeamLeg._
import beam.router.model.RoutingModel.{LinksTimesDistances, TransitStopsInfo}
import beam.router.model.{EmbodiedBeamTrip, RoutingModel, _}
import beam.router.osm.TollCalculator
import beam.router.r5.R5RoutingWorker.{R5Request, TripWithFares}
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
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
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
import scala.util.{Failure, Success, Try}

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
  tollCalculator: TollCalculator,
  travelTimeAndCost: TravelTimeAndCost
)

case class LegWithFare(leg: BeamLeg, fare: Double)

object DestinationUnreachableException extends Exception

class R5RoutingWorker(workerParams: WorkerParameters) extends Actor with ActorLogging with MetricsSupport {

  def this(config: Config) {
    this(workerParams = {
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
      // TODO FIX ME
      val travelTimeAndCost = new TravelTimeAndCost {
        override def overrideTravelTimeAndCostFor(
          origin: Location,
          destination: Location,
          departureTime: Int,
          mode: BeamMode
        ): TimeAndCost = TimeAndCost(None, None)
      }
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
        tollCalculator,
        travelTimeAndCost
      )
    })
  }

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
    tollCalculator,
    travelTimeAndCost
  ) = workerParams

  private val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1
    else Runtime.getRuntime.availableProcessors() - 2
  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("r5-routing-worker-%d").build()
  )
  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val tickTask: Cancellable =
    context.system.scheduler.schedule(2.seconds, 10.seconds, self, "tick")(context.dispatcher)
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

  private var travelTime: TravelTime = new FreeFlowTravelTime

  val linksBelowMinCarSpeed =
    networkHelper.allLinks.count(l => l.getFreespeed < beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond)
  if (linksBelowMinCarSpeed > 0) {
    log.warning(
      "{} links are below quick_fix_minCarSpeedInMetersPerSecond, already in free-flow",
      linksBelowMinCarSpeed
    )
  }

  private val cache = CacheBuilder
    .newBuilder()
    .recordStats()
    .maximumSize(1000)
    .build(new CacheLoader[R5Request, ProfileResponse] {
      override def load(key: R5Request): ProfileResponse = {
        getPlanFromR5(key)
      }
    })

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
            if (beamConfig.beam.outputs.displayPerformanceTimings) {
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
          calcRoute(request)
            .copy(requestId = request.requestId)
        }
      }
      eventualResponse.recover {
        case e =>
          log.error(e, "calcRoute failed")
          RoutingFailure(e, request.requestId)
      } pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(newTravelTime) =>
      travelTime = newTravelTime
      log.info(s"{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      cache.invalidateAll()
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      travelTime = TravelTimeCalculatorHelper.CreateTravelTimeCalculator(beamConfig.beam.agentsim.timeBinSize, map)
      log.info(
        s"{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
        getNameAndHashCode,
        map.keySet().size()
      )
      cache.invalidateAll()
      askForMoreWork()

    case EmbodyWithCurrentTravelTime(
        leg: BeamLeg,
        vehicleId: Id[Vehicle],
        vehicleTypeId: Id[BeamVehicleType],
        embodyRequestId: Int
        ) =>
      val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
        leg.travelPath.linkIds,
        leg.startTime,
        travelTimeByLinkCalculator(vehicleTypes(vehicleTypeId)),
        toR5StreetMode(leg.mode),
        transportNetwork.streetLayer
      )
      val updatedTravelPath = buildStreetPath(linksTimesAndDistances, leg.startTime)
      val updatedLeg = leg.copy(travelPath = updatedTravelPath, duration = updatedTravelPath.duration)

      sender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            Vector(
              EmbodiedBeamLeg(
                updatedLeg,
                vehicleId,
                vehicleTypeId,
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = true
              )
            )
          )
        ),
        embodyRequestId
      )
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  private def getPlanFromR5(request: R5Request): ProfileResponse = {
    countOccurrence("r5-plans-count")

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
    if (request.withTransit) {
      profileRequest.transitModes = util.EnumSet.allOf(classOf[TransitModes])
      profileRequest.accessModes = util.EnumSet.of(request.accessMode)
      profileRequest.egressModes = util.EnumSet.of(request.egressMode)
    }

    try {
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (mode <- profileRequest.directModes.asScala) {
        val streetRouter = new StreetRouter(
          transportNetwork.streetLayer,
          travelTimeCalculator(vehicleTypes(request.beamVehicleTypeId), profileRequest.fromTime),
          turnCostCalculator,
          travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
        )
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

      if (profileRequest.hasTransit) {
        profileRequest.reverseSearch = false
        val accessRouters = mutable.Map[LegMode, StreetRouter]()
        for (mode <- profileRequest.accessModes.asScala) {
          val streetRouter = new StreetRouter(
            transportNetwork.streetLayer,
            travelTimeCalculator(vehicleTypes(request.beamVehicleTypeId), profileRequest.fromTime),
            turnCostCalculator,
            travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
          )
          streetRouter.profileRequest = profileRequest
          streetRouter.streetMode = toR5StreetMode(mode)
          //Gets correct maxCar/Bike/Walk time in seconds for access leg based on mode since it depends on the mode
          streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(mode)
          streetRouter.transitStopSearch = true
          streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
          if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
            streetRouter.route()
            //Searching for access paths
            accessRouters.put(mode, streetRouter)
          }
        }

        val egressRouters = mutable.Map[LegMode, StreetRouter]()
        profileRequest.reverseSearch = true
        for (mode <- profileRequest.egressModes.asScala) {
          val streetRouter = new StreetRouter(
            transportNetwork.streetLayer,
            travelTimeCalculator(vehicleTypes(request.beamVehicleTypeId), profileRequest.fromTime),
            turnCostCalculator,
            travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
          )
          streetRouter.transitStopSearch = true
          streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
          streetRouter.streetMode = toR5StreetMode(mode)
          streetRouter.profileRequest = profileRequest
          streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(mode)
          if (streetRouter.setOrigin(profileRequest.toLat, profileRequest.toLon)) {
            streetRouter.route()
            egressRouters.put(mode, streetRouter)
          }
        }

        val transitPaths = latency("getpath-transit-time", Metrics.VerboseLevel) {
          val router = new McRaptorSuboptimalPathProfileRouter(
            transportNetwork,
            profileRequest,
            accessRouters.mapValues(_.getReachedStops).asJava,
            egressRouters.mapValues(_.getReachedStops).asJava,
            (departureTime: Int) =>
              new FareDominatingList(
                profileRequest.inRoutingFareCalculator,
                Integer.MAX_VALUE,
                departureTime + profileRequest.maxTripDurationMinutes * 60
            ),
            null
          )
          router.getPaths.asScala
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
    profileRequest.carSpeed = 36.11f // 130 km/h, WARNING, see ^^ before changing
    profileRequest.maxWalkTime = 3 * 60
    profileRequest.maxCarTime = 4 * 60
    profileRequest.maxBikeTime = 4 * 60
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

  private def calcRoute(request: RoutingRequest): RoutingResponse = {
    //    log.debug(routingRequest.toString)

    // For each street vehicle (including body, if available): Route from origin to street vehicle, from street vehicle to destination.
    val isRouteForPerson = request.streetVehicles.exists(_.mode == WALK)

    def tripsForVehicle(vehicle: StreetVehicle): Seq[EmbodiedBeamTrip] = {
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
      // First classify the main route type
      val mainRouteFromVehicle = request.streetVehiclesUseIntermodalUse == Access && isRouteForPerson && vehicle.mode != WALK
      val mainRouteToVehicle = request.streetVehiclesUseIntermodalUse == Egress && isRouteForPerson && vehicle.mode != WALK
      val mainRouteRideHailTransit = request.streetVehiclesUseIntermodalUse == AccessAndEgress && isRouteForPerson && vehicle.mode != WALK

      val maybeWalkToVehicle: Option[BeamLeg] = if (mainRouteFromVehicle) {
        if (geo.distUTMInMeters(vehicle.locationUTM.loc, request.originUTM) > beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
          val body = request.streetVehicles.find(_.mode == WALK).get
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
              cache.get(
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
            Some(R5RoutingWorker.createBushwackingBeamLeg(request.departureTime, from, to, geo))
          } else {
            val streetSegment = profileResponse.options.get(0).access.get(0)
            val theTravelPath =
              buildStreetPath(streetSegment, request.departureTime, StreetMode.WALK, vehicleTypes(body.vehicleTypeId))
            Some(
              BeamLeg(
                request.departureTime,
                mapLegMode(LegMode.WALK),
                theTravelPath.duration,
                travelPath = theTravelPath
              )
            )
          }
        } else {
          Some(dummyLeg(request.departureTime, geo.utm2Wgs(vehicle.locationUTM.loc)))
        }
      } else {
        None
      }
      /*
       * For the mainRouteToVehicle pattern (see above), we look for RequestTripInfo.streetVehiclesUseIntermodalUse == Egress, and then we
       * route separately from the vehicle to the destination with an estimate of the start time and adjust the timing of this route
       * after finding the main route from origin to vehicle.
       */
      val maybeUseVehicleOnEgressTry: Try[Vector[LegWithFare]] = Try {
        if (mainRouteToVehicle) {
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
              cache.get(
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
            buildStreetBasedLegs(streetSegment, time, vehicleTypes(vehicle.vehicleTypeId))
          } else {
            throw DestinationUnreachableException // Cannot go to destination with this vehicle, so no options from this vehicle.
          }
        } else {
          Vector()
        }
      }

      maybeUseVehicleOnEgressTry match {
        case Success(maybeUseVehicleOnEgress) =>
          val theOrigin = if (mainRouteToVehicle || mainRouteRideHailTransit) {
            request.originUTM
          } else {
            vehicle.locationUTM.loc
          }
          val theDestination = if (mainRouteToVehicle) {
            vehicle.locationUTM.loc
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
          val directMode = if (mainRouteToVehicle) {
            LegMode.WALK
          } else if (mainRouteRideHailTransit) {
            null
          } else {
            vehicle.mode.r5Mode.get.left.get
          }
          val accessMode = if (mainRouteRideHailTransit) {
            LegMode.CAR
          } else {
            directMode
          }
          val egressMode = if (mainRouteRideHailTransit) {
            LegMode.CAR
          } else {
            LegMode.WALK
          }
          val walkToVehicleDuration =
            maybeWalkToVehicle.map(leg => leg.duration).getOrElse(0)
          val time = request.departureTime + walkToVehicleDuration
          val latencyTag = (if (request.withTransit)
                              "mainVehicleToDestinationRoute"
                            else "mainTransitRoute") + "-router-time"
          val profileResponse: ProfileResponse =
            latency(latencyTag, Metrics.RegularLevel) {
              cache.get(
                R5Request(
                  from,
                  to,
                  time,
                  directMode,
                  accessMode,
                  request.withTransit,
                  egressMode,
                  request.timeValueOfMoney,
                  vehicle.vehicleTypeId
                )
              )
            }

          val tripsWithFares = profileResponse.options.asScala.flatMap { option =>
            /*
             * Iterating all itinerary from a ProfileOption to construct the BeamTrip,
             * itinerary has a PointToPointConnection object that help relating access,
             * egress and transit for the particular itinerary. That contains indexes of
             * access and egress and actual object could be located from lists under option object,
             * as there are separate collections for each.
             *
             * And after locating through these indexes, constructing BeamLeg for each and
             * finally add these legs back to BeamTrip.
             */
            option.itinerary.asScala.view
              .filter { itin =>
                val startTime = dates.toBaseMidnightSeconds(
                  itin.startTime,
                  transportNetwork.transitLayer.routes.size() == 0
                )
                //TODO make a more sensible window not just 30 minutes
                startTime >= time && startTime <= time + 1800
              }
              .map { itinerary =>
                toBeamTrip(
                  isRouteForPerson,
                  maybeWalkToVehicle,
                  maybeUseVehicleOnEgress,
                  profileResponse,
                  option,
                  itinerary,
                  request,
                  vehicleTypes(vehicle.vehicleTypeId)
                )
              }
          }

          tripsWithFares.map(tripWithFares => {
            val indexOfFirstCarLegInParkingTrip = tripWithFares.trip.legs
              .sliding(2)
              .indexWhere(pair => pair.size == 2 && pair.head.mode == CAR && pair.head.mode == pair.last.mode)
            val embodiedLegs: IndexedSeq[EmbodiedBeamLeg] =
              for ((beamLeg, index) <- tripWithFares.trip.legs.zipWithIndex) yield {
                var cost = tripWithFares.legFares.getOrElse(index, 0.0)
                val age = request.attributesOfIndividual.flatMap(_.age)
                if (Modes.isR5TransitMode(beamLeg.mode)) {
                  val agencyId = beamLeg.travelPath.transitStops.get.agencyId
                  val routeId = beamLeg.travelPath.transitStops.get.routeId
                  cost = ptFares.getPtFare(Some(agencyId), Some(routeId), age).getOrElse(cost)
                }
                if (Modes.isR5TransitMode(beamLeg.mode)) {
                  EmbodiedBeamLeg(
                    beamLeg,
                    beamLeg.travelPath.transitStops.get.vehicleId,
                    null,
                    asDriver = false,
                    cost,
                    unbecomeDriverOnCompletion = false
                  )
                } else {
                  val unbecomeDriverAtComplete = Modes
                    .isR5LegMode(beamLeg.mode) && vehicle.asDriver && ((beamLeg.mode == CAR && (indexOfFirstCarLegInParkingTrip < 0 || index != indexOfFirstCarLegInParkingTrip)) ||
                  (beamLeg.mode != CAR && beamLeg.mode != WALK) ||
                  (beamLeg.mode == WALK && index == tripWithFares.trip.legs.size - 1))
                  if (beamLeg.mode == WALK) {
                    val body = request.streetVehicles.find(_.mode == WALK).get
                    EmbodiedBeamLeg(beamLeg, body.id, body.vehicleTypeId, body.asDriver, 0.0, unbecomeDriverAtComplete)
                  } else {
                    if (beamLeg.mode == CAR) {
                      cost = cost + DrivingCost
                        .estimateDrivingCost(beamLeg, vehicleTypes(vehicle.vehicleTypeId), fuelTypePrices)
                    }
                    EmbodiedBeamLeg(
                      beamLeg,
                      vehicle.id,
                      vehicle.vehicleTypeId,
                      vehicle.asDriver,
                      cost,
                      unbecomeDriverAtComplete
                    )
                  }
                }
              }
            EmbodiedBeamTrip(embodiedLegs)
          })
        case Failure(e) if e == DestinationUnreachableException => Nil
        case Failure(e)                                         => throw e
      }
    }

    val embodiedTrips =
      request.streetVehicles.flatMap(vehicle => tripsForVehicle(vehicle))

    if (!embodiedTrips.exists(_.tripClassifier == WALK)) {
      //      log.debug("No walk route found. {}", routingRequest)
      val maybeBody = request.streetVehicles.find(_.mode == WALK)
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
          request.requestId
        )
      } else {
        //        log.debug("Not adding a dummy walk route since agent has no body.")
        RoutingResponse(
          embodiedTrips,
          request.requestId
        )
      }
    } else {
      RoutingResponse(embodiedTrips, request.requestId)
    }
  }

  private def buildStreetBasedLegs(
    r5Leg: StreetSegment,
    tripStartTime: Int,
    vehicleType: BeamVehicleType
  ): Vector[LegWithFare] = {
    val theTravelPath = buildStreetPath(r5Leg, tripStartTime, toR5StreetMode(r5Leg.mode), vehicleType)
    val toll = if (r5Leg.mode == LegMode.CAR) {
      val osm = r5Leg.streetEdges.asScala
        .map(
          e =>
            transportNetwork.streetLayer.edgeStore
              .getCursor(e.edgeId)
              .getOSMID
        )
        .toVector
      tollCalculator.calcTollByOsmIds(osm) + tollCalculator.calcTollByLinkIds(theTravelPath)
    } else 0.0
    val theLeg = BeamLeg(
      tripStartTime,
      mapLegMode(r5Leg.mode),
      theTravelPath.duration,
      travelPath = theTravelPath
    )
    Vector(LegWithFare(theLeg, toll))
  }

  private def buildStreetPath(
    segment: StreetSegment,
    tripStartTime: Int,
    mode: StreetMode,
    vehicleType: BeamVehicleType
  ): BeamPath = {
    var activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds += edge.edgeId.intValue()
    }
    val linksTimesDistances = RoutingModel.linksToTimeAndDistance(
      activeLinkIds,
      tripStartTime,
      travelTimeByLinkCalculator(vehicleType),
      mode,
      transportNetwork.streetLayer
    )
    val distance = linksTimesDistances.distances.tail.sum // note we exclude the first link to keep with MATSim convention
    BeamPath(
      activeLinkIds,
      linksTimesDistances.travelTimes,
      None,
      SpaceTime(
        segment.geometry.getStartPoint.getX,
        segment.geometry.getStartPoint.getY,
        tripStartTime
      ),
      SpaceTime(
        segment.geometry.getEndPoint.getX,
        segment.geometry.getEndPoint.getY,
        tripStartTime + linksTimesDistances.travelTimes.tail.sum
      ),
      distance
    )
  }

  private def buildStreetPath(
    linksTimesDistances: LinksTimesDistances,
    tripStartTime: Int
  ): BeamPath = {
    val startLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesDistances.linkIds.head)
    val endLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesDistances.linkIds.last)
    BeamPath(
      linksTimesDistances.linkIds,
      linksTimesDistances.travelTimes,
      None,
      SpaceTime(startLoc.getX, startLoc.getY, tripStartTime),
      SpaceTime(
        endLoc.getX,
        endLoc.getY,
        tripStartTime + linksTimesDistances.travelTimes.tail.sum
      ),
      linksTimesDistances.distances.tail.foldLeft(0.0)(_ + _)
    )
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

  private def travelTimeCalculator(vehicleType: BeamVehicleType, startTime: Int): TravelTimeCalculator = {
    val ttc = travelTimeByLinkCalculator(vehicleType)
    (edge: EdgeStore#Edge, durationSeconds: Int, streetMode: StreetMode, _) =>
      {
        ttc(startTime + durationSeconds, edge.getEdgeIndex, streetMode)
      }
  }

  private def travelTimeByLinkCalculator(vehicleType: BeamVehicleType): (Int, Int, StreetMode) => Int = {
    val profileRequest = createProfileRequest
    (time: Int, linkId: Int, streetMode: StreetMode) =>
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
          val physSimTravelTime = travelTime.getLinkTravelTime(link, time, null, null).ceil.toInt
          val linkTravelTime = Math.max(physSimTravelTime, minTravelTime)
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

  private def toBeamTrip(
    isRouteForPerson: Boolean,
    maybeWalkToVehicle: Option[BeamLeg],
    maybeUseVehicleOnEgress: Seq[LegWithFare],
    response: ProfileResponse,
    option: ProfileOption,
    itinerary: Itinerary,
    routingRequest: RoutingRequest,
    vehicleType: BeamVehicleType
  ): TripWithFares = {
    // Using itinerary start as access leg's startTime
    val tripStartTime = dates
      .toBaseMidnightSeconds(
        itinerary.startTime,
        transportNetwork.transitLayer.routes.size() == 0
      )
      .toInt

    val legsWithFares = mutable.ArrayBuffer.empty[LegWithFare]
    maybeWalkToVehicle.foreach(walkLeg => {
      // If there's a gap between access leg start time and walk leg, we need to move that ahead
      // this covers the various contingencies for doing this.
      val delayStartTime =
        Math.max(0.0, (tripStartTime - routingRequest.departureTime) - walkLeg.duration)
      legsWithFares += LegWithFare(walkLeg.updateStartTime(walkLeg.startTime + delayStartTime.toInt), 0.0)
    })

    val isTransit = itinerary.connection.transit != null && !itinerary.connection.transit.isEmpty

    val access = option.access.get(itinerary.connection.access)
    legsWithFares ++= buildStreetBasedLegs(access, tripStartTime, vehicleType)

    // Optionally add a Dummy walk BeamLeg to the end of that trip
    if (isRouteForPerson && access.mode != LegMode.WALK) {
      if (!isTransit)
        legsWithFares += LegWithFare(
          dummyLeg(legsWithFares.last.leg.endTime, legsWithFares.last.leg.travelPath.endPoint.loc),
          0.0
        )
    }

    if (isTransit) {
      var arrivalTime: Int = Int.MinValue
      /*
   Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
   assuming that: For each transit in option there is a TransitJourneyID in connection
       */
      val segments = option.transit.asScala zip itinerary.connection.transit.asScala
      val fares = latency("fare-transit-time", Metrics.VerboseLevel) {
        val fareSegments = getFareSegments(segments.toVector)
        filterFaresOnTransfers(fareSegments)
      }

      segments.foreach {
        case (transitSegment, transitJourneyID) =>
          val segmentPattern =
            transitSegment.segmentPatterns.get(transitJourneyID.pattern)
          val tripPattern = response.getPatterns.asScala
            .find { tp =>
              tp.getTripPatternIdx == segmentPattern.patternIdx
            }
            .getOrElse(throw new RuntimeException())
          val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
          val route = transportNetwork.transitLayer.routes.get(tripPattern.getRouteIdx)
          val fs =
            fares.view
              .filter(_.patternIndex == segmentPattern.patternIdx)
              .map(_.fare.price)
          val fare = if (fs.nonEmpty) fs.min else 0.0
          val fromStop = tripPattern.getStops.get(segmentPattern.fromIndex)
          val toStop = tripPattern.getStops.get(segmentPattern.toIndex)
          val startTime = dates
            .toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time), hasTransit = true)
            .toInt
          val endTime = dates
            .toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time), hasTransit = true)
            .toInt
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
              0.0
            )
          )
          legsWithFares += LegWithFare(segmentLeg, fare)
          arrivalTime = dates
            .toBaseMidnightSeconds(
              segmentPattern.toArrivalTime.get(transitJourneyID.time),
              isTransit
            )
            .toInt
          if (transitSegment.middle != null) {
            val body = routingRequest.streetVehicles.find(_.mode == WALK).get
            legsWithFares += LegWithFare(
              BeamLeg(
                arrivalTime,
                mapLegMode(transitSegment.middle.mode),
                transitSegment.middle.duration,
                travelPath = buildStreetPath(
                  transitSegment.middle,
                  arrivalTime,
                  StreetMode.WALK,
                  vehicleTypes(body.vehicleTypeId)
                )
              ),
              0.0
            )
            arrivalTime = arrivalTime + transitSegment.middle.duration // in case of middle arrival time would update
          }
      }

      // egress would only be present if there is some transit, so its under transit presence check
      if (itinerary.connection.egress != null) {
        val egress = option.egress.get(itinerary.connection.egress)
        legsWithFares ++= buildStreetBasedLegs(egress, arrivalTime, vehicleType)
        if (isRouteForPerson && egress.mode != LegMode.WALK)
          legsWithFares += LegWithFare(
            dummyLeg(arrivalTime + egress.duration, legsWithFares.last.leg.travelPath.endPoint.loc),
            0.0
          )
      }
    }
    maybeUseVehicleOnEgress.foreach { legWithFare =>
      val departAt = legsWithFares.last.leg.endTime
      val updatedLeg = legWithFare.leg.updateStartTime(departAt)
      legsWithFares += LegWithFare(updatedLeg, legWithFare.fare)
    }
    if (maybeUseVehicleOnEgress.nonEmpty && isRouteForPerson) {
      legsWithFares += LegWithFare(
        dummyLeg(legsWithFares.last.leg.endTime, legsWithFares.last.leg.travelPath.endPoint.loc),
        0.0
      )
    }
    // TODO is it correct way to find first non-dummy leg
    val fistNonDummyLeg = legsWithFares.collectFirst {
      case legWithFare if legWithFare.leg.mode == BeamMode.WALK && legWithFare.leg.travelPath.linkIds.nonEmpty =>
        legWithFare.leg
    }

    val withUpdatedTimeAndCost = legsWithFares.map {
      case legWithFare =>
        val leg = legWithFare.leg
        val fare = legWithFare.fare
        val travelPath = leg.travelPath
        val TimeAndCost(timeOpt, costOpt) = travelTimeAndCost.overrideTravelTimeAndCostFor(
          travelPath.startPoint.loc,
          travelPath.endPoint.loc,
          leg.startTime,
          leg.mode
        )
        val updatedTravelPath = if (timeOpt.isDefined) {
          val newTravelTime = timeOpt.get
          val newLinkTravelTimes =
            TravelTimeUtils.scaleTravelTime(newTravelTime, travelPath.endPoint.time, travelPath.linkTravelTime)
          BeamPath(
            linkIds = travelPath.linkIds,
            linkTravelTime = newLinkTravelTimes,
            transitStops = travelPath.transitStops,
            startPoint = travelPath.startPoint,
            endPoint = travelPath.endPoint.copy(time = newTravelTime),
            distanceInM = travelPath.distanceInM
          )
        } else {
          travelPath
        }

        val newCost = costOpt
          .map { cost =>
            if (fistNonDummyLeg.contains(leg)) cost
            else 0.0
          }
          .getOrElse(fare)

        // Update travel path and cost
        LegWithFare(leg.copy(travelPath = updatedTravelPath), newCost)
    }

    TripWithFares(
      BeamTrip(withUpdatedTimeAndCost.map(_.leg).toVector),
      withUpdatedTimeAndCost.map(_.fare).zipWithIndex.map(_.swap).toMap
    )
  }
}

object R5RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 0.447 // 1 mile per hour

  def props(
    beamScenario: BeamScenario,
    transportNetwork: TransportNetwork,
    network: Network,
    networkHelper: NetworkHelper,
    scenario: Scenario,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator,
    transitVehicles: Vehicles,
    travelTimeAndCost: TravelTimeAndCost
  ) = Props(
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
        tollCalculator,
        travelTimeAndCost
      )
    )
  )

  case class TripWithFares(trip: BeamTrip, legFares: Map[Int, Double])

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
    val beelineDistanceInMeters = geo.distUTMInMeters(startUTM, endUTM)
    val bushwhackingTime = Math.round(beelineDistanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
    val path = BeamPath(
      Vector(),
      Vector(),
      None,
      SpaceTime(geo.utm2Wgs(startUTM), atTime),
      SpaceTime(geo.utm2Wgs(endUTM), atTime + bushwhackingTime.toInt),
      beelineDistanceInMeters
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
          unbecomeDriverOnCompletion = false
        )
      )
    )
  }

}
