package beam.router.r5

import java.time.temporal.ChronoUnit
import java.time.{ZoneId, ZoneOffset, ZonedDateTime}
import java.util
import java.util.concurrent.{ExecutorService, Executors}

import akka.actor._
import akka.pattern._
import beam.agentsim.agents.choice.mode.{DrivingCostDefaults, ModeIncentive, PtFares}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.Modes._
import beam.router._
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.model.BeamLeg._
import beam.router.model.RoutingModel.LinksTimesDistances
import beam.router.model.{EmbodiedBeamTrip, RoutingModel, _}
import beam.router.osm.TollCalculator
import beam.router.r5.R5RoutingWorker.{R5Request, TripWithFares}
import beam.router.r5.profile.BeamMcRaptorSuboptimalPathProfileRouter
import beam.sim.BeamServices
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.sim.population.AttributesOfIndividual
import beam.utils._
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.{RouteInfo, TransportNetwork}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.config.Config
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.controler.ControlerI
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class WorkerParameters(
  beamServices: BeamServices,
  transportNetwork: TransportNetwork,
  network: Network,
  scenario: Scenario,
  fareCalculator: FareCalculator,
  tollCalculator: TollCalculator,
  transitVehicles: Vehicles,
  travelTimeAndCost: TravelTimeAndCost,
  transitMap: Map[Id[BeamVehicle], (RouteInfo, Seq[BeamLeg])]
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
      val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
      LoggingUtil.createFileLogger(outputDirectory)
      matsimConfig.controler.setOutputDirectory(outputDirectory)
      matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
      networkCoordinator.init()
      scenario.setNetwork(networkCoordinator.network)
      val network = networkCoordinator.network
      val transportNetwork = networkCoordinator.transportNetwork
      val beamServices: BeamServices = new BeamServices {
        override lazy val controler: ControlerI = ???
        override val beamConfig: BeamConfig = BeamConfig(config)
        override lazy val geo: GeoUtils = new GeoUtilsImpl(this)
        override var modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator = _
        override val dates: DateUtils = DateUtils(
          ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
          ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
        )
        override var beamRouter: ActorRef = _
        override val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap()
        override val agencyAndRouteByVehicleIds: TrieMap[Id[Vehicle], (String, String)] = TrieMap()
        override var personHouseholds: Map[Id[Person], Household] = Map()
        val fuelTypePrices: Map[FuelType, Double] =
          BeamServices.readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile).toMap

        // TODO Fix me once `TrieMap` is removed
        val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType] =
          TrieMap(
            BeamServices
              .readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypePrices)
              .toSeq: _*
          )

        // TODO Fix me once `TrieMap` is removed
        val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
          TrieMap(
            BeamServices
              .readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes)
              .toSeq: _*
          )

        override val modeIncentives: ModeIncentive =
          ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.file)
        override val ptFares: PtFares = PtFares(beamConfig.beam.agentsim.agents.ptFare.file)
        override def startNewIteration(): Unit = throw new Exception("???")
        override def matsimServices_=(x$1: org.matsim.core.controler.MatsimServices): Unit = ???
        override val rideHailTransitModes: List[BeamMode] = BeamMode.massTransitModes
        override val tazTreeMap: beam.agentsim.infrastructure.TAZTreeMap =
          beam.sim.BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.file)

        override def matsimServices: org.matsim.core.controler.MatsimServices = ???

        override def networkHelper: NetworkHelper = ???
      }

      val defaultTravelTimeByLink = (time: Int, linkId: Int, mode: StreetMode) => {
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
        val tt = (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, mode)).round
        tt.toInt
      }
      val initializer =
        new TransitInitializer(beamServices, transportNetwork, scenario.getTransitVehicles, defaultTravelTimeByLink)
      val transits = initializer.initMap

      val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
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
      BeamRouter.checkForConsistentTimeZoneOffsets(beamServices, transportNetwork)
      WorkerParameters(
        beamServices,
        transportNetwork,
        network,
        scenario,
        fareCalculator,
        tollCalculator,
        scenario.getTransitVehicles,
        travelTimeAndCost,
        transits
      )
    })
  }

  val WorkerParameters(
    beamServices,
    transportNetwork,
    network,
    scenario,
    fareCalculator,
    tollCalculator,
    transitVehicles,
    travelTimeAndCost,
    transitMap
  ) = workerParams

  private val distanceThresholdToIgnoreWalking =
    beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters // meters

  val numOfThreads: Int =
    if (Runtime.getRuntime.availableProcessors() <= 2) 1
    else Runtime.getRuntime.availableProcessors() - 2
  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    numOfThreads,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("r5-routing-worker-%d").build()
  )
  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  val tickTask: Cancellable =
    context.system.scheduler.schedule(2.seconds, 10.seconds, self, "tick")(context.dispatcher)
  var msgs: Long = 0
  var firstMsgTime: Option[ZonedDateTime] = None
  log.info("R5RoutingWorker_v2[{}] `{}` is ready", hashCode(), self.path)
  log.info(
    "Num of available processors: {}. Will use: {}",
    Runtime.getRuntime.availableProcessors(),
    numOfThreads
  )

  def getNameAndHashCode: String = s"R5RoutingWorker_v2[${hashCode()}], Path: `${self.path}`"

  var workAssigner: ActorRef = context.parent

  private var maybeTravelTime: Option[TravelTime] = None

  private val links = network.getLinks

  private val linkIdMap: HashMap[Int, Link] = {
    val start = System.currentTimeMillis()
    val pairs = links.asScala.map { case (k, v) => k.toString.toInt -> v }.toSeq
    val map = HashMap(pairs: _*)
    val end = System.currentTimeMillis()
    log.info("linkIdMap is built in {} ms", end - start)
    map
  }

  private var transitSchedule: Map[Id[BeamVehicle], (RouteInfo, Seq[BeamLeg])] = transitMap

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
            if (beamServices.beamConfig.beam.outputs.displayPerformanceTimings) {
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

    case TransitInited(newTransitSchedule) =>
      transitSchedule = newTransitSchedule
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
      eventualResponse.onComplete {
        case scala.util.Failure(ex) =>
          log.error(ex, "calcRoute failed")
        case _ =>
      }
      eventualResponse pipeTo sender
      askForMoreWork()

    case UpdateTravelTimeLocal(travelTime) =>
      maybeTravelTime = Some(travelTime)
      log.info(s"{} UpdateTravelTimeLocal. Set new travel time", getNameAndHashCode)
      cache.invalidateAll()
      askForMoreWork()

    case UpdateTravelTimeRemote(map) =>
      val travelTimeCalc =
        TravelTimeCalculatorHelper.CreateTravelTimeCalculator(beamServices.beamConfig.beam.agentsim.timeBinSize, map)
      maybeTravelTime = Some(travelTimeCalc)
      log.info(
        s"{} UpdateTravelTimeRemote. Set new travel time from map with size {}",
        getNameAndHashCode,
        map.keySet().size()
      )
      cache.invalidateAll()
      askForMoreWork

    case EmbodyWithCurrentTravelTime(
        leg: BeamLeg,
        vehicleId: Id[Vehicle],
        vehicleTypeId: Id[BeamVehicleType],
        embodyRequestId: Int,
        mustParkAtEnd: Boolean,
        destinationForSplitting: Option[Coord]
        ) =>
      val travelTime = (time: Int, linkId: Int) =>
        maybeTravelTime match {
          case Some(matsimTravelTime) =>
            getTravelTime(time, linkId, matsimTravelTime).toInt
          case None =>
            val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
            (edge.getLengthM / edge.calculateSpeed(
              new ProfileRequest,
              StreetMode.valueOf(leg.mode.r5Mode.get.left.get.toString)
            )).toInt
      }
      val linkEvents = RoutingModel.traverseStreetLeg(leg, vehicleId, travelTime)
      val linkTimes = linkEvents
        .drop(1)
        .grouped(2)
        .map(pair => Math.round(pair.last.getTime - pair.head.getTime).toInt)
        .toIndexedSeq :+ 0
      val duration = linkEvents
        .maxBy(e => e.getTime)
        .getTime - leg.startTime

      val finalLegs = if (mustParkAtEnd) {
        val legPair = splitLegForParking(
          leg.copy(duration = duration.toInt, travelPath = leg.travelPath.copy(linkTravelTime = linkTimes)),
          destinationForSplitting
        )
        val fuelAndTollCostPerLeg = legPair.map { beamLeg =>
          val fuelCost = DrivingCostDefaults.estimateFuelCost(beamLeg, vehicleTypeId, beamServices)
          val toll = if (beamLeg.mode == CAR) {
            val osm = beamLeg.travelPath.linkIds.toVector.map { e =>
              transportNetwork.streetLayer.edgeStore
                .getCursor(e)
                .getOSMID
            }
            tollCalculator.calcTollByOsmIds(osm) + tollCalculator.calcTollByLinkIds(beamLeg.travelPath)
          } else 0.0
          fuelCost + toll
        }
        val embodiedPair = Vector(
          EmbodiedBeamLeg(
            legPair.head,
            vehicleId,
            vehicleTypeId,
            asDriver = true,
            fuelAndTollCostPerLeg.head,
            unbecomeDriverOnCompletion = false
          ),
          EmbodiedBeamLeg(
            legPair.last,
            vehicleId,
            vehicleTypeId,
            asDriver = true,
            fuelAndTollCostPerLeg.last,
            unbecomeDriverOnCompletion = true
          )
        )
        if (legPair.size == 1) {
          Vector(embodiedPair.head)
        } else {
          embodiedPair
        }
      } else {
        Vector(
          EmbodiedBeamLeg(
            leg.copy(duration = duration.toInt),
            vehicleId,
            vehicleTypeId,
            asDriver = true,
            0,
            unbecomeDriverOnCompletion = true
          )
        )
      }

      sender ! RoutingResponse(
        Vector(
          EmbodiedBeamTrip(
            finalLegs
          )
        ),
        embodyRequestId
      )
      askForMoreWork()
  }

  private def askForMoreWork(): Unit =
    if (workAssigner != null) workAssigner ! GimmeWork //Master will retry if it hasn't heard

  def updateLegWithCurrentTravelTime(leg: BeamLeg): BeamLeg = {
    val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
      leg.travelPath.linkIds,
      leg.startTime,
      travelTimeByLinkCalculator,
      toR5StreetMode(leg.mode),
      transportNetwork.streetLayer
    )
    val updatedTravelPath = buildStreetPath(linksTimesAndDistances, leg.startTime)
    leg.copy(travelPath = updatedTravelPath, duration = updatedTravelPath.duration)
  }

  def lengthOfLink(linkId: Int): Double = {
    val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
    edge.getLengthM
  }

  private def getPlanUsingCache(request: R5Request) = {
    var plan = latencyIfNonNull("cache-router-time", Metrics.VerboseLevel) {
      cache.getIfPresent(request)
    }
    if (plan == null) {
      val planWithTime = measure(cache.get(request))
      plan = planWithTime._1

      var nt = ""
      if (request.transitModes.isEmpty) nt = "non"

      record(s"noncache-${nt}transit-router-time", Metrics.VerboseLevel, planWithTime._2)
      record("noncache-router-time", Metrics.VerboseLevel, planWithTime._2)
    }
    plan
  }

  def getPlanFromR5(request: R5Request): ProfileResponse = {
    countOccurrence("r5-plans-count")
    val maxStreetTime = 2 * 60
    // If we already have observed travel times, probably from the pre
    // let R5 use those. Otherwise, let R5 use its own travel time estimates.
    val profileRequest = new ProfileRequest()
    profileRequest.fromLon = request.from.getX
    profileRequest.fromLat = request.from.getY
    profileRequest.toLon = request.to.getX
    profileRequest.toLat = request.to.getY
    profileRequest.maxWalkTime = 3 * 60
    profileRequest.maxCarTime = 4 * 60
    profileRequest.maxBikeTime = 4 * 60
    profileRequest.streetTime = maxStreetTime
    profileRequest.maxTripDurationMinutes = 4 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    profileRequest.fromTime = request.time
    profileRequest.toTime = request.time + 61 // Important to allow 61 seconds for transit schedules to be considered!
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = if (request.directMode == null) {
      util.EnumSet.noneOf(classOf[LegMode])
    } else {
      util.EnumSet.of(request.directMode)
    }
    profileRequest.suboptimalMinutes = 0
    if (request.transitModes.nonEmpty) {
      profileRequest.transitModes = util.EnumSet.copyOf(request.transitModes.asJavaCollection)
      profileRequest.accessModes = util.EnumSet.of(request.accessMode)
      profileRequest.egressModes = util.EnumSet.of(request.egressMode)
    }
    //    log.debug(profileRequest.toString)
    val result = try {
      getPlan(profileRequest, request.timeValueOfMoney)
    } catch {
      case _: IllegalStateException =>
        new ProfileResponse
      case _: ArrayIndexOutOfBoundsException =>
        new ProfileResponse
    }
    //    log.debug(s"# options found = ${result.options.size()}")
    result
  }

  def calcRoute(routingRequest: RoutingRequest): RoutingResponse = {
    //    log.debug(routingRequest.toString)

    // For each street vehicle (including body, if available): Route from origin to street vehicle, from street vehicle to destination.
    val isRouteForPerson = routingRequest.streetVehicles.exists(_.mode == WALK)

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
      val mainRouteFromVehicle = routingRequest.streetVehiclesUseIntermodalUse == Access && isRouteForPerson && vehicle.mode != WALK
      val mainRouteToVehicle = routingRequest.streetVehiclesUseIntermodalUse == Egress && isRouteForPerson && vehicle.mode != WALK
      val mainRouteRideHailTransit = routingRequest.streetVehiclesUseIntermodalUse == AccessAndEgress && isRouteForPerson && vehicle.mode != WALK

      val maybeWalkToVehicle: Option[BeamLeg] = if (mainRouteFromVehicle) {
        if (beamServices.geo.distUTMInMeters(vehicle.locationUTM.loc, routingRequest.originUTM) > distanceThresholdToIgnoreWalking) {
          val from = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(routingRequest.originUTM),
            10E3
          )
          val to = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(vehicle.locationUTM.loc),
            10E3
          )
          val directMode = LegMode.WALK
          val accessMode = LegMode.WALK
          val egressMode = LegMode.WALK
          val transitModes = Nil
          val profileResponse =
            latency("walkToVehicleRoute-router-time", Metrics.RegularLevel) {
              cache.get(
                R5Request(
                  from,
                  to,
                  routingRequest.departureTime,
                  directMode,
                  accessMode,
                  transitModes,
                  egressMode,
                  routingRequest.timeValueOfMoney
                )
              )
            }
          if (profileResponse.options.isEmpty) {
            Some(R5RoutingWorker.createBushwackingBeamLeg(routingRequest.departureTime, from, to, beamServices))
          } else {
            val streetSegment = profileResponse.options.get(0).access.get(0)
            val theTravelPath = buildStreetPath(streetSegment, routingRequest.departureTime, StreetMode.WALK)
            Some(
              BeamLeg(
                routingRequest.departureTime,
                mapLegMode(LegMode.WALK),
                theTravelPath.duration,
                travelPath = theTravelPath
              )
            )
          }
        } else {
          Some(dummyWalk(routingRequest.departureTime))
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
            .round(beamServices.geo.distUTMInMeters(routingRequest.originUTM, vehicle.locationUTM.loc) / 5.8)
            .intValue()
          val time = routingRequest.departureTime + estimateDurationToGetToVeh
          val from = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(vehicle.locationUTM.loc),
            10E3
          )
          val to = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(routingRequest.destinationUTM),
            10E3
          )
          val directMode = vehicle.mode.r5Mode.get.left.get
          val accessMode = vehicle.mode.r5Mode.get.left.get
          val egressMode = LegMode.WALK
          val transitModes = Nil
          val profileResponse =
            latency("vehicleOnEgressRoute-router-time", Metrics.RegularLevel) {
              cache.get(
                R5Request(
                  from,
                  to,
                  time,
                  directMode,
                  accessMode,
                  transitModes,
                  egressMode,
                  routingRequest.timeValueOfMoney
                )
              )
            }
          if (!profileResponse.options.isEmpty) {
            val streetSegment = profileResponse.options.get(0).access.get(0)
            buildStreetBasedLegs(streetSegment, time, routingRequest.mustParkAtEnd)
          } else {
            throw DestinationUnreachableException // Cannot go to destination with this vehicle, so no options from this vehicle.
          }
        } else {
          Vector()
        }
      }

      maybeUseVehicleOnEgressTry match {
        case Success(maybeUseVehicleOnEgress) => {
          val theOrigin = if (mainRouteToVehicle || mainRouteRideHailTransit) {
            routingRequest.originUTM
          } else {
            vehicle.locationUTM.loc
          }
          val theDestination = if (mainRouteToVehicle) {
            vehicle.locationUTM.loc
          } else {
            routingRequest.destinationUTM
          }
          val from = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(theOrigin),
            10E3
          )
          val to = beamServices.geo.snapToR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(theDestination),
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
          val time = routingRequest.departureTime + walkToVehicleDuration
          val transitModes: IndexedSeq[TransitModes] =
            routingRequest.transitModes.map(_.r5Mode.get.right.get)
          val latencyTag = (if (transitModes.isEmpty)
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
                  transitModes,
                  egressMode,
                  routingRequest.timeValueOfMoney
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
                val startTime = beamServices.dates.toBaseMidnightSeconds(
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
                  option,
                  itinerary,
                  routingRequest
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
                val age = routingRequest.attributesOfIndividual.flatMap(_.age)
                val ids = beamServices.agencyAndRouteByVehicleIds.get(
                  beamLeg.travelPath.transitStops.fold(vehicle.id)(_.vehicleId)
                )
                cost =
                  ids.fold(cost)(id => beamServices.ptFares.getPtFare(Some(id._1), Some(id._2), age).getOrElse(cost))

                if (Modes.isR5TransitMode(beamLeg.mode)) {
                  EmbodiedBeamLeg(
                    beamLeg,
                    beamLeg.travelPath.transitStops.get.vehicleId,
                    BeamVehicleType.defaultTransitBeamVehicleType.id,
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
                    val body = routingRequest.streetVehicles.find(_.mode == WALK).get
                    EmbodiedBeamLeg(beamLeg, body.id, body.vehicleTypeId, body.asDriver, 0.0, unbecomeDriverAtComplete)
                  } else {
                    if (beamLeg.mode == CAR) {
                      cost = cost + DrivingCostDefaults.estimateFuelCost(beamLeg, vehicle.vehicleTypeId, beamServices)
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
        }
        case Failure(e) if e == DestinationUnreachableException => Nil
        case Failure(e)                                         => throw e
      }
    }

    val embodiedTrips =
      routingRequest.streetVehicles.flatMap(vehicle => tripsForVehicle(vehicle))

    if (!embodiedTrips.exists(_.tripClassifier == WALK)) {
      //      log.debug("No walk route found. {}", routingRequest)
      val maybeBody = routingRequest.streetVehicles.find(_.mode == WALK)
      if (maybeBody.isDefined) {
        //        log.debug("Adding dummy walk route with maximum street time.")
        val dummyTrip = R5RoutingWorker.createBushwackingTrip(
          beamServices.geo.utm2Wgs(new Coord(routingRequest.originUTM.getX, routingRequest.originUTM.getY)),
          beamServices.geo.utm2Wgs(new Coord(routingRequest.destinationUTM.getX, routingRequest.destinationUTM.getY)),
          routingRequest.departureTime,
          maybeBody.get,
          beamServices
        )
        RoutingResponse(
          embodiedTrips :+ dummyTrip,
          routingRequest.requestId
        )
      } else {
        //        log.debug("Not adding a dummy walk route since agent has no body.")
        RoutingResponse(
          embodiedTrips,
          routingRequest.requestId
        )
      }
    } else {
      RoutingResponse(embodiedTrips, routingRequest.requestId)
    }
  }

  def buildStreetBasedLegs(r5Leg: StreetSegment, tripStartTime: Int, mustParkAtEnd: Boolean): Vector[LegWithFare] = {
    val theTravelPath = buildStreetPath(r5Leg, tripStartTime, toR5StreetMode(r5Leg.mode))
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
    val splitLegs = if (mustParkAtEnd && r5Leg.mode == LegMode.CAR) {
      splitLegForParking(theLeg, None)
    } else {
      Vector(theLeg)
    }
    // assign toll to first part of the split
    Vector(LegWithFare(splitLegs.head, toll)) ++ splitLegs.tail.map(leg => LegWithFare(leg, 0.0))
  }

  def splitLegForParking(leg: BeamLeg, destinationForSplitting: Option[Coord]): IndexedSeq[BeamLeg] = {
    val theLinkIds = leg.travelPath.linkIds
    if (theLinkIds.length <= 1) {
      Vector(leg)
    } else {
      val originWithinSplittingDistance = destinationForSplitting match {
        case Some(destForSplitting) =>
          beamServices.geo
            .distLatLon2Meters(destForSplitting, leg.travelPath.startPoint.loc) < beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
        case None =>
          leg.travelPath.distanceInM < beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
      }
      if (originWithinSplittingDistance) {
        val firstLeg = updateLegWithCurrentTravelTime(leg.updateLinks(Vector(theLinkIds.head)))
        val secondLeg = updateLegWithCurrentTravelTime(
          leg
            .updateLinks(theLinkIds)
            .copy(startTime = firstLeg.startTime + firstLeg.duration)
        )
        Vector(firstLeg, secondLeg)
      } else {
        val indexFromEnd = destinationForSplitting match {
          case Some(destForSplitting) =>
            Math.min(
              Math.max(
                theLinkIds.reverse.indexWhere(
                  link =>
                    beamServices.geo
                      .distLatLon2Meters(R5RoutingWorker.linkIdToCoord(link, transportNetwork), destForSplitting) >
                    beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
                ),
                1
              ),
              theLinkIds.length - 1
            )
          case None =>
            Math.min(
              Math.max(
                theLinkIds.reverse
                  .map(lengthOfLink)
                  .scanLeft(0.0)(_ + _)
                  .indexWhere(
                    _ > beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
                  ),
                1
              ),
              theLinkIds.length - 1
            )
        }

        val indexFromBeg = theLinkIds.length - indexFromEnd
        val firstLeg = updateLegWithCurrentTravelTime(
          leg.updateLinks(theLinkIds.take(indexFromBeg))
        )
        val secondLeg = updateLegWithCurrentTravelTime(
          leg
            .updateLinks(theLinkIds.takeRight(indexFromEnd + 1))
            .copy(startTime = firstLeg.startTime + firstLeg.duration)
        )
        Vector(firstLeg, secondLeg)
      }
    }
  }

  private def buildStreetPath(
    segment: StreetSegment,
    tripStartTime: Int,
    mode: StreetMode
  ): BeamPath = {
    var activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      if (!links.containsKey(Id.createLinkId(edge.edgeId.longValue()))) {
        throw new RuntimeException("Link not found: " + edge.edgeId)
      }
      activeLinkIds += edge.edgeId.intValue()
    }
    val linksTimesDistances = RoutingModel.linksToTimeAndDistance(
      activeLinkIds,
      tripStartTime,
      travelTimeByLinkCalculator,
      mode,
      transportNetwork.streetLayer
    )
    val duration = linksTimesDistances.travelTimes.tail.sum // note we exclude the first link to keep with MATSim convention
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
        tripStartTime + segment.duration
      ),
      segment.distance.toDouble / 1000
    )
  }

  private def buildStreetPath(
    linksTimesDistances: LinksTimesDistances,
    tripStartTime: Int
  ): BeamPath = {
    val startLoc = beamServices.geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesDistances.linkIds.head)
    val endLoc = beamServices.geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesDistances.linkIds.last)
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

  def getTimezone: ZoneId = this.transportNetwork.getTimeZone

  private def travelTimeCalculator(startTime: Int): TravelTimeCalculator =
    maybeTravelTime match {
      case Some(travelTime) =>
        (edge: EdgeStore#Edge, durationSeconds: Int, streetMode: StreetMode, req: ProfileRequest) =>
          {
            if (streetMode != StreetMode.CAR || edge.getOSMID < 0) {
              // An R5 internal edge, probably connecting transit to the street network. We don't have those in the
              // MATSim network.
              (edge.getLengthM / edge.calculateSpeed(req, streetMode)).toFloat
            } else {
              getTravelTime(startTime + durationSeconds, edge.getEdgeIndex, travelTime).toFloat
            }
          }
      case None => new EdgeStore.DefaultTravelTimeCalculator
    }

  private def travelTimeByLinkCalculator(time: Int, linkId: Int, mode: StreetMode): Int = {
    maybeTravelTime match {
      case Some(matsimTravelTime) if mode == StreetMode.CAR =>
        getTravelTime(time, linkId, matsimTravelTime).round.toInt

      case _ =>
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
        //        (new EdgeStore.DefaultTravelTimeCalculator).getTravelTimeMilliseconds(edge,)
        val tt = (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, mode)).round
        tt.toInt
    }
  }

  private def getTravelTime(time: Int, linkId: Int, travelTime: TravelTime): Double = {
    val link = linkIdMap(linkId)
    val tt = travelTime.getLinkTravelTime(link, time, null, null)
    val travelSpeed = link.getLength / tt
    if (travelSpeed < beamServices.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond) {
      link.getLength / beamServices.beamConfig.beam.physsim.quick_fix_minCarSpeedInMetersPerSecond
    } else {
      tt
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

  //Does point to point routing with data from request
  def getPlan(request: ProfileRequest, timeValueOfMoney: Double): ProfileResponse = {
    val startRouting = System.currentTimeMillis
    request.zoneId = transportNetwork.getTimeZone
    //Do the query and return result
    val profileResponse = new ProfileResponse
    val option = new ProfileOption
    request.reverseSearch = false
    //For direct modes

    for (mode <- request.directModes.asScala) {
      val streetRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator(request.fromTime),
        turnCostCalculator,
        travelCostCalculator(timeValueOfMoney, request.fromTime)
      )
      var streetPath: StreetPath = null
      streetRouter.profileRequest = request
      streetRouter.streetMode = toR5StreetMode(mode)
      streetRouter.timeLimitSeconds = request.streetTime * 60
      if (streetRouter.setOrigin(request.fromLat, request.fromLon)) {
        if (streetRouter.setDestination(request.toLat, request.toLon)) {
          latency("route-transit-time", Metrics.VerboseLevel) {
            streetRouter.route() //latency 1
          }
          val lastState =
            streetRouter.getState(streetRouter.getDestinationSplit)
          if (lastState != null) {
            streetPath = new StreetPath(lastState, transportNetwork, false)
            val streetSegment =
              new StreetSegment(streetPath, mode, transportNetwork.streetLayer)
            option.addDirect(streetSegment, request.getFromTimeDateZD)
          } else {
            //            log.debug("Direct mode {} last state wasn't found", mode)
          }
        } else {
          //          log.debug("Direct mode {} destination wasn't found!", mode)
        }
      } else {
        //        log.debug("Direct mode {} origin wasn't found!", mode)
      }
    }
    option.summary = option.generateSummary
    profileResponse.addOption(option)
    if (request.hasTransit) {
      val accessRouter = findAccessPaths(request, timeValueOfMoney)
      val egressRouter = findEgressPaths(request, timeValueOfMoney)
      import scala.collection.JavaConverters._
      //latency 2nd step
      val router = new BeamMcRaptorSuboptimalPathProfileRouter(
        transportNetwork,
        request,
        accessRouter.mapValues(_.getReachedStops).asJava,
        egressRouter.mapValues(_.getReachedStops).asJava
      )
      router.NUMBER_OF_SEARCHES = beamServices.beamConfig.beam.routing.r5.numberOfSamples
      val usefullpathList = new util.ArrayList[PathWithTimes]
      // getPaths actually returns a set, which is important so that things are deduplicated. However we need a list
      // so we can sort it below.
      latency("getpath-transit-time", Metrics.VerboseLevel) {
        usefullpathList.addAll(router.getPaths) //latency of get paths
      }
      //This sort is necessary only for text debug output so it will be disabled when it is finished
      /**
        * Orders first no transfers then one transfers 2 etc
        * - then orders according to first trip:
        *   - board stop
        *   - alight stop
        *   - alight time
        * - same for one transfer trip
        */
      usefullpathList.sort((o1: PathWithTimes, o2: PathWithTimes) => {
        def foo(o1: PathWithTimes, o2: PathWithTimes) = {
          var c = 0
          c = Integer.compare(o1.patterns.length, o2.patterns.length)
          if (c == 0) c = Integer.compare(o1.boardStops(0), o2.boardStops(0))
          if (c == 0) c = Integer.compare(o1.alightStops(0), o2.alightStops(0))
          if (c == 0) c = Integer.compare(o1.alightTimes(0), o2.alightTimes(0))
          if (c == 0 && o1.patterns.length == 2) {
            c = Integer.compare(o1.boardStops(1), o2.boardStops(1))
            if (c == 0)
              c = Integer.compare(o1.alightStops(1), o2.alightStops(1))
            if (c == 0)
              c = Integer.compare(o1.alightTimes(1), o2.alightTimes(1))
          }
          c
        }

        foo(o1, o2)
      })
      //      log.debug("Usefull paths:{}", usefullpathList.size)

      for (path <- usefullpathList.asScala) {
        profileResponse.addTransitPath(
          accessRouter.asJava,
          egressRouter.asJava,
          path,
          transportNetwork,
          request.getFromTimeDateZD
        )
      }
      latency("transfer-transit-time", Metrics.VerboseLevel) {
        profileResponse.generateStreetTransfers(transportNetwork, request)
      } // latency possible candidate
    }
    profileResponse.recomputeStats(request)
    //    log.debug("Returned {} options", profileResponse.getOptions.size)
    //    log.debug("Took {} ms", System.currentTimeMillis - startRouting)
    profileResponse
  }

  /**
    * Finds all egress paths from to coordinate to end stop and adds routers to egressRouter
    *
    * @param request ProfileRequest
    */
  private def findEgressPaths(request: ProfileRequest, timeValueOfMoney: Double) = {
    val egressRouter = mutable.Map[LegMode, StreetRouter]()
    //For egress
    //TODO: this must be reverse search
    request.reverseSearch = true

    for (mode <- request.egressModes.asScala) {
      val streetRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator(request.fromTime),
        turnCostCalculator,
        travelCostCalculator(timeValueOfMoney, request.fromTime)
      )
      streetRouter.transitStopSearch = true
      streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
      streetRouter.streetMode = toR5StreetMode(mode)
      streetRouter.profileRequest = request
      streetRouter.timeLimitSeconds = request.getTimeLimit(mode)
      if (streetRouter.setOrigin(request.toLat, request.toLon)) {
        streetRouter.route()
        val stops = streetRouter.getReachedStops
        egressRouter.put(mode, streetRouter)
        log.debug("Added {} edgres stops for mode {}", stops.size, mode)
      } else
        log.debug(
          "MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!",
          mode
        )
    }
    egressRouter
  }

  def toBeamTrip(
    isRouteForPerson: Boolean,
    maybeWalkToVehicle: Option[BeamLeg],
    maybeUseVehicleOnEgress: Seq[LegWithFare],
    option: ProfileOption,
    itinerary: Itinerary,
    routingRequest: RoutingRequest
  ): TripWithFares = {
    // Using itinerary start as access leg's startTime
    val tripStartTime = beamServices.dates
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
    legsWithFares ++= buildStreetBasedLegs(access, tripStartTime, routingRequest.mustParkAtEnd)

    // Optionally add a Dummy walk BeamLeg to the end of that trip
    if (isRouteForPerson && access.mode != LegMode.WALK) {
      if (!isTransit)
        legsWithFares += LegWithFare(dummyWalk(legsWithFares.last.leg.endTime), 0.0)
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
          //              val tripPattern = transportNetwork.transitLayer.tripPatterns.get(segmentPattern.patternIdx)
          val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
          //              val trip = tripPattern.tripSchedules.asScala.find(_.tripId == tripId).get
          val fs =
            fares.view
              .filter(_.patternIndex == segmentPattern.patternIdx)
              .map(_.fare.price)
          val fare = if (fs.nonEmpty) fs.min else 0.0
          val segmentLegs =
            transitSchedule(Id.createVehicleId(tripId))._2
              .slice(segmentPattern.fromIndex, segmentPattern.toIndex)
          legsWithFares ++= segmentLegs.zipWithIndex
            .map(beamLeg => LegWithFare(beamLeg._1, if (beamLeg._2 == 0) fare else 0.0))
          arrivalTime = beamServices.dates
            .toBaseMidnightSeconds(
              segmentPattern.toArrivalTime.get(transitJourneyID.time),
              isTransit
            )
            .toInt
          if (transitSegment.middle != null) {
            legsWithFares += LegWithFare(
              BeamLeg(
                arrivalTime,
                mapLegMode(transitSegment.middle.mode),
                transitSegment.middle.duration,
                travelPath = buildStreetPath(
                  transitSegment.middle,
                  arrivalTime,
                  toR5StreetMode(transitSegment.middle.mode)
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
        legsWithFares ++= buildStreetBasedLegs(egress, arrivalTime, routingRequest.mustParkAtEnd)
        if (isRouteForPerson && egress.mode != LegMode.WALK)
          legsWithFares += LegWithFare(dummyWalk(arrivalTime + egress.duration), 0.0)
      }
    }
    maybeUseVehicleOnEgress.foreach { legWithFare =>
      val departAt = legsWithFares.last.leg.endTime
      val updatedLeg = legWithFare.leg.updateStartTime(departAt)
      legsWithFares += LegWithFare(updatedLeg, legWithFare.fare)
    }
    if (maybeUseVehicleOnEgress.nonEmpty && isRouteForPerson) {
      legsWithFares += LegWithFare(dummyWalk(legsWithFares.last.leg.endTime), 0.0)
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

  /**
    * Finds access paths from from coordinate in request and adds all routers with paths to accessRouter map
    *
    * @param request ProfileRequest
    */
  private def findAccessPaths(request: ProfileRequest, timeValueOfMoney: Double) = {
    request.reverseSearch = false
    // Routes all access modes
    val accessRouter = mutable.Map[LegMode, StreetRouter]()

    for (mode <- request.accessModes.asScala) {
      val streetRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator(request.fromTime),
        turnCostCalculator,
        travelCostCalculator(timeValueOfMoney, request.fromTime)
      )
      streetRouter.profileRequest = request
      streetRouter.streetMode = toR5StreetMode(mode)
      //Gets correct maxCar/Bike/Walk time in seconds for access leg based on mode since it depends on the mode
      streetRouter.timeLimitSeconds = request.getTimeLimit(mode)
      streetRouter.transitStopSearch = true
      streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
      if (streetRouter.setOrigin(request.fromLat, request.fromLon)) {
        streetRouter.route()
        //Searching for access paths
        accessRouter.put(mode, streetRouter)
      } else
        log.debug(
          "MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!",
          mode
        )
    }
    accessRouter
  }

}

object R5RoutingWorker {
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 0.447 // 1 mile per hour

  def props(
    beamServices: BeamServices,
    transportNetwork: TransportNetwork,
    network: Network,
    scenario: Scenario,
    fareCalculator: FareCalculator,
    tollCalculator: TollCalculator,
    transitVehicles: Vehicles,
    travelTimeAndCost: TravelTimeAndCost
  ) = Props(
    new R5RoutingWorker(
      WorkerParameters(
        beamServices,
        transportNetwork,
        network,
        scenario,
        fareCalculator,
        tollCalculator,
        transitVehicles,
        travelTimeAndCost,
        Map.empty
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
    transitModes: Seq[TransitModes],
    egressMode: LegMode,
    timeValueOfMoney: Double
  )

  def linkIdToCoord(id: Int, transportNetwork: TransportNetwork): Coord = {
    val edge = transportNetwork.streetLayer.edgeStore.getCursor(id)
    new Coord(
      (edge.getGeometry.getEndPoint.getX + edge.getGeometry.getStartPoint.getX) / 2.0,
      (edge.getGeometry.getEndPoint.getY + edge.getGeometry.getStartPoint.getY) / 2.0
    )
  }

  def createBushwackingBeamLeg(atTime: Int, start: Location, end: Location, beamServices: BeamServices): BeamLeg = {
    val beelineDistanceInMeters = beamServices.geo.distUTMInMeters(start, end)
    val bushwhackingTime = Math.round(beelineDistanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
    createBushwackingBeamLeg(atTime, bushwhackingTime.toInt, start, end, beelineDistanceInMeters)
  }

  def createBushwackingBeamLeg(
    atTime: Int,
    duration: Int,
    start: Location,
    end: Location,
    distance: Double
  ): BeamLeg = {
    val path = BeamPath(Vector(), Vector(), None, SpaceTime(start, atTime), SpaceTime(end, atTime + duration), distance)
    BeamLeg(atTime, WALK, duration, path)
  }

  def createBushwackingTrip(
    origin: Location,
    dest: Location,
    atTime: Int,
    body: StreetVehicle,
    beamServices: BeamServices
  ): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      Vector(
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(atTime, origin, dest, beamServices),
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
