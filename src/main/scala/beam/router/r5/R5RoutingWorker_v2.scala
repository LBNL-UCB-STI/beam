package beam.router.r5

import java.time.temporal.ChronoUnit
import java.time.{ZoneId, ZoneOffset, ZonedDateTime}
import java.util
import java.util.concurrent.Executors
import java.util.{Collections, UUID}

import akka.actor.Status.Success
import akka.actor._
import akka.pattern._
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.TransitVehicle
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM, WALK}
import beam.router.Modes._
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel.{EmbodiedBeamTrip, _}
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.osm.TollCalculator
import beam.router.r5.profile.BeamMcRaptorSuboptimalPathProfileRouter
import beam.router.{Modes, RoutingModel}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.metrics.{Metrics, MetricsSupport}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.reflection.ReflectionUtils
import beam.utils.{DateUtils, FileUtils, LoggingUtil}
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile._
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.{RouteInfo, TransitLayer}
import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.inject.Injector
import com.typesafe.config.Config
import kamon.Kamon
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.ControlerI
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class R5RoutingWorker_v2(val typesafeConfig: Config) extends Actor with ActorLogging with MetricsSupport with BeamHelper {
  val beamConfig = BeamConfig(typesafeConfig)
  val outputDirectory = FileUtils.getConfigOutputFile(beamConfig.beam.outputs.baseOutputDirectory,
    beamConfig.beam.agentsim.simulationName, beamConfig.beam.outputs.addTimestampToOutputDirectory)

  val matsimConfig = new MatSimBeamConfigBuilder(typesafeConfig).buildMatSamConf()
  matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

  ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)

  LoggingUtil.createFileLogger(outputDirectory)
  matsimConfig.controler.setOutputDirectory(outputDirectory)
  matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)

  val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
  val networkCoordinator = new NetworkCoordinator(beamConfig, scenario.getTransitVehicles)
  networkCoordinator.loadNetwork()
  scenario.setNetwork(networkCoordinator.network)

  val network = networkCoordinator.network
  val transportNetwork = networkCoordinator.transportNetwork

  val beamServices: BeamServices = new BeamServices {
    override lazy val controler: ControlerI = ???
    override var beamConfig: BeamConfig = BeamConfig(typesafeConfig)
    override lazy val registry: ActorRef = throw new Exception("KKK")
    override lazy val geo: GeoUtils = new GeoUtilsImpl(this)
    override var modeChoiceCalculatorFactory: HouseholdActor.AttributesOfIndividual => ModeChoiceCalculator = _
    override val dates: DateUtils = DateUtils(ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate))

    override var beamRouter: ActorRef = null
    override val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap[Id[Person], ActorRef]()
    override val vehicles: TrieMap[Id[Vehicle], BeamVehicle] = TrieMap[Id[Vehicle], BeamVehicle]()

    override def startNewIteration: Unit = throw new Exception("KKK")

    override protected def injector: Injector = throw new Exception("KKK")
  }


  private var numStopsNotFound = 0

  val fareCalculator = new FareCalculator(beamConfig.beam.routing.r5.directory)
  val tollCalculator = new TollCalculator(beamConfig.beam.routing.r5.directory)

  val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters // meters
  val BUSHWHACKING_SPEED_IN_METERS_PER_SECOND = 0.447 // 1 mile per hour

  var maybeTravelTime: Option[TravelTime] = None
  var transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])] = Map()

  val cache = CacheBuilder.newBuilder().recordStats().maximumSize(1000).build(new CacheLoader[R5Request, ProfileResponse] {
    override def load(key: R5Request) = {
      getPlanFromR5(key)
    }
  })

  val numOfThreads = if (Runtime.getRuntime().availableProcessors() <= 2)  {
    1
  }
  else {
    Runtime.getRuntime().availableProcessors() - 2
  }

  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(numOfThreads))

  val tickTask = context.system.scheduler.schedule(2.seconds, 10.seconds, self, "tick")(context.dispatcher)

  var msgs: Long = 0
  var firstMsgTime: Option[ZonedDateTime] = None

  log.info("R5RoutingWorker_v2[{}] `{}` is ready", hashCode(), self.path)
  log.info("Num of avaiable processors: {}. Will use: {}", Runtime.getRuntime().availableProcessors(), numOfThreads)

  def getNameAndHashCode: String = s"R5RoutingWorker_v2[${hashCode()}], Path: `${self.path}`"



  override final def receive: Receive = {
    case "tick"   => {
      if (firstMsgTime.isDefined) {
        val seconds = ChronoUnit.SECONDS.between(firstMsgTime.get, ZonedDateTime.now(ZoneOffset.UTC))
        if (seconds > 0) {
          val rate = msgs.toDouble / seconds
          log.info(s"Receiving $rate per seconds of RoutingRequest")
        }
      }
    }
    case InitTransit_v2(scheduler, id) =>
      val start = System.currentTimeMillis()
      transitSchedule = initTransit(scheduler)
      val stop = System.currentTimeMillis()
      log.info(s"{} TransitInited[$id] in ${stop - start} ms. transitSchedule[{}] keys: {}", getNameAndHashCode,
        transitSchedule.hashCode(), transitSchedule.keys.size)
      sender() ! Success("inited")

    case request: RoutingRequest =>
      Kamon.counter("receiving-routing-requests")
      msgs += 1
      if (firstMsgTime.isEmpty)
        firstMsgTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
      val withReceivedAt = request.copy(receivedAt = Some(ZonedDateTime.now(ZoneOffset.UTC)))
      val eventualResponse =  Future {
          val start = System.currentTimeMillis()
          val res = calcRoute(withReceivedAt)
          val stop = System.currentTimeMillis()
          res.copy(routeCalcTimeMs = stop - start)
      }
      eventualResponse.failed.foreach(log.error(_, ""))
      eventualResponse.pipeTo(sender)

    case UpdateTravelTime(travelTime) =>
      log.info(s"{} UpdateTravelTime", getNameAndHashCode)
      maybeTravelTime = Some(travelTime)
      cache.invalidateAll()
    case EmbodyWithCurrentTravelTime(leg: BeamLeg, vehicleId: Id[Vehicle], createdAt: ZonedDateTime) =>
      val now = ZonedDateTime.now(ZoneOffset.UTC)
      val travelTime = (time: Long, linkId: Int) => maybeTravelTime match {
        case Some(matsimTravelTime) =>
          matsimTravelTime.getLinkTravelTime(network.getLinks.get(Id.createLinkId(linkId)), time.toDouble, null, null).toLong
        case None =>
          val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
          (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, StreetMode.valueOf(leg.mode.r5Mode.get.left.get.toString))).toLong
      }
      val duration = RoutingModel.traverseStreetLeg(leg, vehicleId, travelTime).map(e => e.getTime).max - leg.startTime

      val id = UUID.randomUUID()
      // TODO FIX ME!
      sender ! RoutingResponse(Vector(EmbodiedBeamTrip(Vector(EmbodiedBeamLeg(leg.copy(duration = duration.toLong), vehicleId, true, None, BigDecimal.valueOf(0), true)))),
        requestCreatedAt = createdAt, requestReceivedAt = now, createdAt = ZonedDateTime.now(ZoneOffset.UTC),
        id = id,
        requestId = UUID.fromString("00000000-0000-0000-0000-000000000000")
      )
  }



  case class R5Request(from: Coord, to: Coord, time: WindowTime, directMode: LegMode, accessMode: LegMode, transitModes: Seq[TransitModes], egressMode: LegMode)

  def getPlanFromR5(request: R5Request): ProfileResponse = {
    val maxStreetTime = 2 * 60
    // If we already have observed travel times, probably from the previous iteration,
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
    profileRequest.fromTime = request.time.fromTime
    profileRequest.toTime = request.time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.of(request.directMode)
    profileRequest.suboptimalMinutes = 0
    if (request.transitModes.nonEmpty) {
      profileRequest.transitModes = util.EnumSet.copyOf(request.transitModes.asJavaCollection)
      profileRequest.accessModes = util.EnumSet.of(request.accessMode)
      profileRequest.egressModes = util.EnumSet.of(request.egressMode)
    }
    log.debug(profileRequest.toString)
    val result = try {
      getPlan(profileRequest)
    } catch {
      case e: IllegalStateException =>
        new ProfileResponse
      case e: ArrayIndexOutOfBoundsException =>
        new ProfileResponse
    }
    log.debug(s"# options found = ${result.options.size()}")
    result
  }


  def calcRoute(routingRequest: RoutingRequest): RoutingResponse = {
    log.debug(routingRequest.toString)

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
       * Note that we don't use the R5 egress concept to accomplish the mainRouteToVehicle pattern because
       * we want to fix the location of the vehicle, not make it dynamic (this may change when we enable TNC's with transit).
       * Also note that in both cases, these patterns are only the result of human travelers, we assume AI is fixed to
       * a vehicle and therefore only needs the simplest of routes.
       *
       * For the mainRouteFromVehicle pattern, the traveler is using a vehicle within the context of a
       * trip that could be multimodal (e.g. drive to transit) or unimodal (drive only). We don't assume the vehicle is
       * co-located with the person, so this first block of code determines the distance from the vehicle to the person and based
       * on a threshold, optionally routes a WALK leg to the vehicle and adjusts the main route location & time accordingly.
       *
       */
      val mainRouteFromVehicle = routingRequest.streetVehiclesAsAccess && isRouteForPerson && vehicle.mode != WALK
      val maybeWalkToVehicle: Option[BeamLeg] = if (mainRouteFromVehicle) {
        val time = routingRequest.departureTime match {
          case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5.departureWindow)
          case time: WindowTime => time
        }
        if (beamServices.geo.distInMeters(vehicle.location.loc, routingRequest.origin) > distanceThresholdToIgnoreWalking) {
          val from = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(routingRequest.origin), 10E3)
          val to = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(vehicle.location.loc), 10E3)
          val directMode = LegMode.WALK
          val accessMode = LegMode.WALK
          val egressMode = LegMode.WALK
          val transitModes = Nil
          val profileResponse = latency("walkToVehicleRoute-router-time", Metrics.RegularLevel) {
            cache.get(R5Request(from, to, time, directMode, accessMode, transitModes, egressMode))
          }
          if (profileResponse.options.isEmpty) {
            return Nil // Cannot walk to vehicle, so no options from this vehicle.
          }
          val travelTime = profileResponse.options.get(0).itinerary.get(0).duration
          val streetSegment = profileResponse.options.get(0).access.get(0)
          Some(BeamLeg(time.atTime, mapLegMode(LegMode.WALK), travelTime, travelPath = buildStreetPath(streetSegment, time.atTime)))
        } else {
          Some(dummyWalk(time.atTime))
        }
      } else {
        None
      }
      /*
       * For the mainRouteToVehicle pattern (see above), we look for RequestTripInfo.streetVehiclesAsAccess == false, and then we
       * route separately from the vehicle to the destination with an estimate of the start time and adjust the timing of this route
       * after finding the main route from origin to vehicle.
       */
      val mainRouteToVehicle = !routingRequest.streetVehiclesAsAccess && isRouteForPerson && vehicle.mode != WALK
      val maybeUseVehicleOnEgress: Option[BeamLeg] = if (mainRouteToVehicle) {
        // assume 13 mph / 5.8 m/s as average PT speed: http://cityobservatory.org/urban-buses-are-slowing-down/
        val estimateDurationToGetToVeh: Int = math.round(beamServices.geo.distInMeters(routingRequest.origin, vehicle.location.loc) / 5.8).intValue()
        val time = routingRequest.departureTime match {
          case time: DiscreteTime => WindowTime(time.atTime + estimateDurationToGetToVeh, beamServices.beamConfig.beam.routing.r5.departureWindow)
          case time: WindowTime => time.copy(time.atTime + estimateDurationToGetToVeh)
        }
        val from = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(vehicle.location.loc), 10E3)
        val to = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(routingRequest.destination), 10E3)
        val directMode = vehicle.mode.r5Mode.get.left.get
        val accessMode = vehicle.mode.r5Mode.get.left.get
        val egressMode = LegMode.WALK
        val transitModes = Nil
        val profileResponse = latency("vehicleOnEgressRoute-router-time", Metrics.RegularLevel) {
          cache.get(R5Request(from, to, time, directMode, accessMode, transitModes, egressMode))
        }
        if (!profileResponse.options.isEmpty) {
          val travelTime = profileResponse.options.get(0).itinerary.get(0).duration
          val streetSegment = profileResponse.options.get(0).access.get(0)
          Some(BeamLeg(time.atTime, vehicle.mode, travelTime, travelPath = buildStreetPath(streetSegment, time.atTime)))
        } else {
          return Nil // Cannot go to destination with this vehicle, so no options from this vehicle.
        }
      } else {
        None
      }

      val theOrigin = if (mainRouteToVehicle) {
        routingRequest.origin
      } else {
        vehicle.location.loc
      }
      val theDestination = if (mainRouteToVehicle) {
        vehicle.location.loc
      } else {
        routingRequest.destination
      }
      val from = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(theOrigin), 10E3)
      val to = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, beamServices.geo.utm2Wgs(theDestination), 10E3)
      val directMode = if (mainRouteToVehicle) {
        LegMode.WALK
      } else {
        vehicle.mode.r5Mode.get.left.get
      }
      val accessMode = directMode
      val egressMode = LegMode.WALK
      val walkToVehicleDuration = maybeWalkToVehicle.map(leg => leg.duration).getOrElse(0l).toInt
      val time = routingRequest.departureTime match {
        case time: DiscreteTime => WindowTime(time.atTime + walkToVehicleDuration, beamServices.beamConfig.beam.routing.r5.departureWindow)
        case time: WindowTime => WindowTime(time.atTime + walkToVehicleDuration, 0)
      }
      val transitModes: Seq[TransitModes] = routingRequest.transitModes.map(_.r5Mode.get.right.get)
      val latencyTag = (if (transitModes.isEmpty) "mainVehicleToDestinationRoute" else "mainTransitRoute") + "-router-time"
      val profileResponse: ProfileResponse = latency(latencyTag, Metrics.RegularLevel) {
        cache.get(R5Request(from, to, time, directMode, accessMode, transitModes, egressMode))
      }
      val tripsWithFares = profileResponse.options.asScala.flatMap(option => {
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
        option.itinerary.asScala.filter { itin =>
          val startTime = beamServices.dates.toBaseMidnightSeconds(itin.startTime, transportNetwork.transitLayer.routes.size() == 0)
          //TODO make a more sensible window not just 30 minutes
          startTime >= time.fromTime && startTime <= time.fromTime + 1800
        }.map(itinerary => {
          val legsWithFares = mutable.ArrayBuffer.empty[(BeamLeg, Double)]
          maybeWalkToVehicle.foreach { x => legsWithFares +=((x, 0.0)) }

          val access = option.access.get(itinerary.connection.access)
          val toll = if (access.mode == LegMode.CAR) {
            val osm = access.streetEdges.asScala.map(e => transportNetwork.streetLayer.edgeStore.getCursor(e.edgeId).getOSMID)
            tollCalculator.calcToll(osm)
          } else 0.0
          // Using itinerary start as access leg's startTime
          val tripStartTime = beamServices.dates.toBaseMidnightSeconds(itinerary.startTime, transportNetwork.transitLayer.routes.size() == 0)
          val isTransit = itinerary.connection.transit != null && !itinerary.connection.transit.isEmpty
          //        legFares += legs.size -> toll
          legsWithFares += ((BeamLeg(tripStartTime, mapLegMode(access.mode), access.duration, travelPath = buildStreetPath(access, tripStartTime)), toll))

          //add a Dummy walk BeamLeg to the end of that trip
          if (isRouteForPerson && access.mode != LegMode.WALK) {
            if (!isTransit) legsWithFares += ((dummyWalk(tripStartTime + access.duration), 0.0))
          }

          if (isTransit) {
            var arrivalTime: Long = Long.MinValue
            /*
             Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
             assuming that: For each transit in option there is a TransitJourneyID in connection
             */
            val segments = option.transit.asScala zip itinerary.connection.transit.asScala
            val fares = latency("fare-transit-time", Metrics.VerboseLevel) {
              val fareSegments = getFareSegments(segments.toVector)
              filterFaresOnTransfers(fareSegments)
            }

            segments.foreach { case (transitSegment, transitJourneyID) =>
              val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
              //              val tripPattern = transportNetwork.transitLayer.tripPatterns.get(segmentPattern.patternIdx)
              val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
              //              val trip = tripPattern.tripSchedules.asScala.find(_.tripId == tripId).get
              val fs = fares.filter(_.patternIndex == segmentPattern.patternIdx).map(_.fare.price)
              val fare = if (fs.nonEmpty) fs.min else 0.0
              val segmentLegs = transitSchedule(Id.createVehicleId(tripId))._2.slice(segmentPattern.fromIndex, segmentPattern.toIndex)
              legsWithFares ++= segmentLegs.zipWithIndex.map(beamLeg => (beamLeg._1, if (beamLeg._2 == 0) fare else 0.0))
              arrivalTime = beamServices.dates.toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time), isTransit)
              if (transitSegment.middle != null) {
                legsWithFares += ((BeamLeg(arrivalTime, mapLegMode(transitSegment.middle.mode), transitSegment.middle.duration, travelPath = buildStreetPath(transitSegment.middle, arrivalTime)), 0.0))
                arrivalTime = arrivalTime + transitSegment.middle.duration // in case of middle arrival time would update
              }
            }

            // egress would only be present if there is some transit, so its under transit presence check
            if (itinerary.connection.egress != null) {
              val egress = option.egress.get(itinerary.connection.egress)
              //start time would be the arrival time of last stop and 5 second alighting
              legsWithFares += ((BeamLeg(arrivalTime, mapLegMode(egress.mode), egress.duration, buildStreetPath(egress, arrivalTime)), 0.0))
              if (isRouteForPerson && egress.mode != LegMode.WALK) legsWithFares += ((dummyWalk(arrivalTime + egress.duration), 0.0))
            }
          }
          maybeUseVehicleOnEgress.foreach { leg =>
            val departAt = legsWithFares.last._1.endTime
            legsWithFares += ((leg.copy(startTime = departAt), 0.0))
            legsWithFares += ((dummyWalk(departAt + leg.duration), 0.0))
          }
          TripWithFares(BeamTrip(legsWithFares.map(_._1), mapLegMode(access.mode)), legsWithFares.map(_._2).zipWithIndex.map(_.swap).toMap)
        })
      })

      tripsWithFares.map(tripWithFares => {
        val embodiedLegs: Seq[EmbodiedBeamLeg] = for ((beamLeg, index) <- tripWithFares.trip.legs.zipWithIndex) yield {
          val cost = tripWithFares.legFares.getOrElse(index, 0.0) // FIXME this value is never used.
          if (Modes.isR5TransitMode(beamLeg.mode)) {
            EmbodiedBeamLeg(beamLeg, beamLeg.travelPath.transitStops.get.vehicleId, false, None, cost, false)
          } else {
            val unbecomeDriverAtComplete = Modes.isR5LegMode(beamLeg.mode) && (beamLeg.mode != WALK || beamLeg == tripWithFares.trip.legs.last)
            if (beamLeg.mode == WALK) {
              val body = routingRequest.streetVehicles.find(_.mode == WALK).get
              EmbodiedBeamLeg(beamLeg, body.id, body.asDriver, None, 0.0, unbecomeDriverAtComplete)
            } else {
              EmbodiedBeamLeg(beamLeg, vehicle.id, vehicle.asDriver, None, cost, unbecomeDriverAtComplete)
            }
          }
        }
        EmbodiedBeamTrip(embodiedLegs)
      })

    }

    val embodiedTrips = routingRequest.streetVehicles.flatMap(vehicle => tripsForVehicle(vehicle))

    if (!embodiedTrips.exists(_.tripClassifier == WALK)) {
      log.debug("No walk route found. {}", routingRequest)
      val maybeBody = routingRequest.streetVehicles.find(_.mode == WALK)
      if (maybeBody.isDefined) {
        log.debug("Adding dummy walk route with maximum street time.")
        val origin = new Coord(routingRequest.origin.getX, routingRequest.origin.getY)
        val dest = new Coord(routingRequest.destination.getX, routingRequest.destination.getY)
        val beelineDistanceInMeters = beamServices.geo.distInMeters(origin, dest)
        val bushwhackingTime = Math.round(beelineDistanceInMeters / BUSHWHACKING_SPEED_IN_METERS_PER_SECOND)
        val dummyTrip = EmbodiedBeamTrip(
          Vector(
            EmbodiedBeamLeg(
              BeamLeg(
                routingRequest.departureTime.atTime,
                WALK,
                bushwhackingTime,
                BeamPath(
                  Vector(),
                  None,
                  SpaceTime(origin, routingRequest.departureTime.atTime),
                  SpaceTime(dest, routingRequest.departureTime.atTime + bushwhackingTime),
                  beelineDistanceInMeters)
              ),
              maybeBody.get.id, maybeBody.get.asDriver, None, 0, unbecomeDriverOnCompletion = false)
          )
        )
        RoutingResponse(embodiedTrips :+ dummyTrip,
          requestCreatedAt = routingRequest.createdAt,
          requestReceivedAt = routingRequest.receivedAt.get,
          createdAt = ZonedDateTime.now(ZoneOffset.UTC),
          id = UUID.randomUUID(),
          requestId = routingRequest.id)
      } else {
        log.debug("Not adding a dummy walk route since agent has no body.")
        RoutingResponse(embodiedTrips, requestCreatedAt = routingRequest.createdAt,
          requestReceivedAt = routingRequest.receivedAt.get,
          createdAt = ZonedDateTime.now(ZoneOffset.UTC),
          id = UUID.randomUUID(),
          requestId = routingRequest.id)
      }
    } else {
      RoutingResponse(embodiedTrips, requestCreatedAt = routingRequest.createdAt,
        requestReceivedAt = routingRequest.receivedAt.get,
        createdAt = ZonedDateTime.now(ZoneOffset.UTC), id = UUID.randomUUID(), requestId = routingRequest.id)
    }
  }

  private def buildStreetPath(segment: StreetSegment, tripStartTime: Long): BeamPath = {
    var activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      if (!network.getLinks.containsKey(Id.createLinkId(edge.edgeId.longValue()))) {
        throw new RuntimeException("Link not found: " + edge.edgeId)
      }
      activeLinkIds += edge.edgeId.intValue()
    }
    BeamPath(
      activeLinkIds,
      None,
      SpaceTime(segment.geometry.getStartPoint.getX, segment.geometry.getStartPoint.getY, tripStartTime),
      SpaceTime(segment.geometry.getEndPoint.getX, segment.geometry.getEndPoint.getY, tripStartTime + segment.duration),
      segment.distance.toDouble / 1000)
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments
    * @return a collection of FareSegments for an itinerary.
    */
  private def getFareSegments(segments: Seq[(TransitSegment, TransitJourneyID)]): Seq[BeamFareSegment] = {
    segments.groupBy(s => getRoute(s._1, s._2).agency_id).flatMap(t => {
      val pattern = getPattern(t._2.head._1, t._2.head._2)
      val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)

      var rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

      if (rules.isEmpty) {
        val route = getRoute(pattern)
        val agencyId = route.agency_id
        val routeId = route.route_id

        val fromId = getStopId(t._2.head._1.from)
        val toId = getStopId(t._2.last._1.to)

        val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime.get(t._2.last._2.time)
        val duration = ChronoUnit.SECONDS.between(fromTime, toTime)

        val containsIds = t._2.flatMap(s => Seq(getStopId(s._1.from), getStopId(s._1.to))).toSet

        rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds).map(f => BeamFareSegment(f, pattern.patternIdx, duration))
      }
      rules
    }).toSeq
  }

  private def getFareSegments(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID, fromTime: ZonedDateTime): Vector[BeamFareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration = ChronoUnit.SECONDS.between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(f => BeamFareSegment(f, pattern.patternIdx, duration))
    if (fr.nonEmpty && fr.forall(_.patternIndex == fr.head.patternIndex))
      fr = Vector(fr.minBy(_.fare.price))
    fr
  }

  private def getFareSegments(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Vector[BeamFareSegment] =
    fareCalculator.getFareSegments(agencyId, routeId, fromId, toId, containsIds)

  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes.get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

  def getTimezone: ZoneId = this.transportNetwork.getTimeZone

  private def travelTimeCalculator(startTime: Int): TravelTimeCalculator = maybeTravelTime match {
    case Some(travelTime) => (edge: EdgeStore#Edge, durationSeconds: Int, streetMode: StreetMode, req: ProfileRequest) => {
      if (streetMode != StreetMode.CAR || edge.getOSMID < 0) {
        // An R5 internal edge, probably connecting transit to the street network. We don't have those in the
        // MATSim network.
        (edge.getLengthM / edge.calculateSpeed(req, streetMode)).toFloat
      } else {
        travelTime.getLinkTravelTime(network.getLinks.get(Id.createLinkId(edge.getEdgeIndex)), startTime + durationSeconds, null, null).asInstanceOf[Float]
      }
    }
    case None => new EdgeStore.DefaultTravelTimeCalculator
  }

  private val turnCostCalculator: TurnCostCalculator = new TurnCostCalculator(transportNetwork.streetLayer, true) {
    override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
  }

  //Does point to point routing with data from request
  def getPlan(request: ProfileRequest): ProfileResponse = {
    val startRouting = System.currentTimeMillis
    request.zoneId = transportNetwork.getTimeZone
    //Do the query and return result
    val profileResponse = new ProfileResponse
    val option = new ProfileOption
    request.reverseSearch = false
    //For direct modes

    for (mode <- request.directModes.asScala) {
      val streetRouter = new StreetRouter(transportNetwork.streetLayer, travelTimeCalculator(request.fromTime), turnCostCalculator)
      var streetPath: StreetPath = null
      streetRouter.profileRequest = request
      streetRouter.streetMode = StreetMode.valueOf(mode.toString)
      streetRouter.timeLimitSeconds = request.streetTime * 60
      if (streetRouter.setOrigin(request.fromLat, request.fromLon)) {
        if (streetRouter.setDestination(request.toLat, request.toLon)) {
          latency("route-transit-time", Metrics.VerboseLevel) {
            streetRouter.route() //latency 1
          }
          val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
          if (lastState != null) {
            streetPath = new StreetPath(lastState, transportNetwork, false)
            val streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer)
            option.addDirect(streetSegment, request.getFromTimeDateZD)
          } else {
            log.debug("Direct mode {} last state wasn't found", mode)
          }
        } else {
          log.debug("Direct mode {} destination wasn't found!", mode)
        }
      } else {
        log.debug("Direct mode {} origin wasn't found!", mode)
      }
    }
    option.summary = option.generateSummary
    profileResponse.addOption(option)
    if (request.hasTransit) {
      val accessRouter = findAccessPaths(request)
      val egressRouter = findEgressPaths(request)
      import scala.collection.JavaConverters._
      //latency 2nd step
      val router = new BeamMcRaptorSuboptimalPathProfileRouter(transportNetwork, request, accessRouter.mapValues(_.getReachedStops).asJava, egressRouter.mapValues(_.getReachedStops).asJava)
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
            if (c == 0) c = Integer.compare(o1.alightStops(1), o2.alightStops(1))
            if (c == 0) c = Integer.compare(o1.alightTimes(1), o2.alightTimes(1))
          }
          c
        }

        foo(o1, o2)
      })
      log.debug("Usefull paths:{}", usefullpathList.size)

      for (path <- usefullpathList.asScala) {
        profileResponse.addTransitPath(accessRouter.asJava, egressRouter.asJava, path, transportNetwork, request.getFromTimeDateZD)
      }
      latency("transfer-transit-time", Metrics.VerboseLevel) {
        profileResponse.generateStreetTransfers(transportNetwork, request)
      } // latency possible candidate
    }
    profileResponse.recomputeStats(request)
    log.debug("Returned {} options", profileResponse.getOptions.size)
    log.debug("Took {} ms", System.currentTimeMillis - startRouting)
    profileResponse
  }

  /**
    * Finds all egress paths from to coordinate to end stop and adds routers to egressRouter
    *
    * @param request
    */
  private def findEgressPaths(request: ProfileRequest) = {
    val egressRouter = mutable.Map[LegMode, StreetRouter]()
    //For egress
    //TODO: this must be reverse search
    request.reverseSearch = true

    for (mode <- request.egressModes.asScala) {
      val streetRouter = new StreetRouter(transportNetwork.streetLayer, travelTimeCalculator(request.fromTime), turnCostCalculator)
      streetRouter.transitStopSearch = true
      streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
      streetRouter.streetMode = StreetMode.valueOf(mode.toString)
      streetRouter.profileRequest = request
      streetRouter.timeLimitSeconds = request.getTimeLimit(mode)
      if (streetRouter.setOrigin(request.toLat, request.toLon)) {
        streetRouter.route()
        val stops = streetRouter.getReachedStops
        egressRouter.put(mode, streetRouter)
        log.debug("Added {} edgres stops for mode {}", stops.size, mode)
      }
      else log.debug("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode)
    }
    egressRouter
  }

  /**
    * Finds access paths from from coordinate in request and adds all routers with paths to accessRouter map
    *
    * @param request
    */
  private def findAccessPaths(request: ProfileRequest) = {
    request.reverseSearch = false
    // Routes all access modes
    val accessRouter = mutable.Map[LegMode, StreetRouter]()

    for (mode <- request.accessModes.asScala) {
      var streetRouter = new StreetRouter(transportNetwork.streetLayer, travelTimeCalculator(request.fromTime), turnCostCalculator)
      streetRouter.profileRequest = request
      streetRouter.streetMode = StreetMode.valueOf(mode.toString)
      //Gets correct maxCar/Bike/Walk time in seconds for access leg based on mode since it depends on the mode
      streetRouter.timeLimitSeconds = request.getTimeLimit(mode)
      streetRouter.transitStopSearch = true
      streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS
      if (streetRouter.setOrigin(request.fromLat, request.fromLon)) {
        streetRouter.route()
        //Searching for access paths
        accessRouter.put(mode, streetRouter)
      }
      else log.debug("MODE:{}, Edge near the origin coordinate wasn't found. Routing didn't start!", mode)
    }
    accessRouter
  }

  private def initTransit(scheduler: ActorRef) = {
    val config = beamServices.beamConfig.beam.routing
    val transitVehicles = scenario.getTransitVehicles
    def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, legs: Seq[BeamLeg]): Unit = {

      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

      val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)) {
        transitVehicles.getVehicleTypes.get(vehicleTypeId)
      } else {
        log.debug(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
        transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]))
      }

      mode match {
        case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
          val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
          matSimTransitVehicle.getType.setDescription(mode.value)
          val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain
            .AverageMilesPerGallon)
          //        val transitVehProps = TransitVehicle.props(services, matSimTransitVehicle.getId, TransitVehicleData
          // (), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
          //        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
          val vehicle: BeamVehicle = new BeamVehicle(Powertrain.PowertrainFromMilesPerGallon(consumption),
            matSimTransitVehicle, None, TransitVehicle, None, None)
          beamServices.vehicles += (transitVehId -> vehicle)

        case _ =>
          log.error(mode + " is not supported yet")
      }
    }

    val activeServicesToday = transportNetwork.transitLayer.getActiveServicesForDate(beamServices.dates.localBaseDate)
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetPath]]()
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toStream
    val transitData = transitTrips.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths = tripPattern.stops.indices.sliding(2).map { case IndexedSeq(fromStopIdx, toStopIdx) =>
        val fromStop = tripPattern.stops(fromStopIdx)
        val toStop = tripPattern.stops(toStopIdx)
        if (config.transitOnStreetNetwork && isOnStreetTransit(mode)) {
          stopToStopStreetSegmentCache.getOrElseUpdate((fromStop, toStop), routeTransitPathThroughStreets(fromStop, toStop)) match {
            case Some(streetSeg) =>
              val edges = streetSeg.getEdges.asScala
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edges.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edges.map(_.intValue()),
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + streetSeg.getDuration),
                  streetSeg.getDistance.toDouble / 1000)
            case None =>
              val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
              val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
              val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
                BeamPath(
                  edgeIds,
                  Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
                  SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
                  SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
                  beamServices.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                    new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
                )
          }
        } else {
          val edgeIds = resolveFirstLastTransitEdges(fromStop, toStop)
          val startEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.head)
          val endEdge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIds.last)
          (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) =>
            BeamPath(
              edgeIds,
              Option(TransitStopsInfo(fromStop, vehicleId, toStop)),
              SpaceTime(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY, departureTime),
              SpaceTime(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY, departureTime + duration),
              beamServices.geo.distLatLon2Meters(new Coord(startEdge.getGeometry.getStartPoint.getX, startEdge.getGeometry.getStartPoint.getY),
                new Coord(endEdge.getGeometry.getEndPoint.getX, endEdge.getGeometry.getEndPoint.getY))
            )
        }
      }.toSeq
      tripPattern.tripSchedules.asScala
        .filter(tripSchedule => activeServicesToday.get(tripSchedule.serviceCode))
        .map { tripSchedule =>
          // First create a unique for this trip which will become the transit agent and vehicle ids
          val tripVehId = Id.create(tripSchedule.tripId, classOf[Vehicle])
          val legs: ArrayBuffer[BeamLeg] = new ArrayBuffer()
          tripSchedule.departures.zipWithIndex.sliding(2).foreach { case Array((departureTimeFrom, from), (departureTimeTo, to)) =>
            val duration = tripSchedule.arrivals(to) - departureTimeFrom
            legs += BeamLeg(departureTimeFrom.toLong, mode, duration, transitPaths(from)(departureTimeFrom.toLong, duration, tripVehId))
          }
          (tripVehId, (route, legs))
        }
    }
    val transitScheduleToCreate = transitData.toMap
    transitScheduleToCreate.foreach { case (tripVehId, (route, legs)) =>
      createTransitVehicle(tripVehId, route, legs)
    }
    log.info(s"Finished Transit initialization trips, ${transitData.length}")
    transitScheduleToCreate
  }
  private def routeTransitPathThroughStreets(fromStopIdx: Int, toStopIdx: Int) = {

    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
    val fromPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(fromVertex.getLon, fromVertex.getLat), 100E3, StreetMode.WALK)
    val toPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer, new Coord(toVertex.getLon, toVertex.getLat), 100E3, StreetMode.WALK)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    val time = WindowTime(0, beamServices.beamConfig.beam.routing.r5.departureWindow)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null

    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = StreetMode.valueOf("CAR")
    streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
    if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
      if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
        streetRouter.route()
        val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
        if (lastState != null) {
          Some(new StreetPath(lastState, transportNetwork, false))
        } else {
          None
        }
      } else {
        None
      }
    } else {
      None
    }
  }

  private def resolveFirstLastTransitEdges(stopIdxs: Int*):Seq[Int] = {
    val edgeIds: Seq[Int] = stopIdxs.map { stopIdx =>
      if (transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0) {
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer
          .streetVertexForStop.get(stopIdx))
        val split = transportNetwork.streetLayer.findSplit(stopVertex.getLat, stopVertex.getLon, 10000, StreetMode.CAR)
        if (split != null) {
          split.edge
        } else {
          limitedWarn(stopIdx)
          createDummyEdgeFromVertex(stopVertex)
        }
      } else {
        limitedWarn(stopIdx)
        createDummyEdge
      }
    }.distinct
    edgeIds
  }
  private def limitedWarn(stopIdx: Int): Unit = {
    if (numStopsNotFound < 5) {
      log.warning(s"Stop $stopIdx not linked to street network.")
      numStopsNotFound = numStopsNotFound + 1
    } else if (numStopsNotFound == 5) {
      log.warning(s"Stop $stopIdx not linked to street network. Further warnings messages will be suppressed")
      numStopsNotFound = numStopsNotFound + 1
    }
  }

  private def createDummyEdge(): Int = {
    val fromVert = transportNetwork.streetLayer.vertexStore.addVertex(38, -122)
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(38.001, -122.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(fromVert, toVert, 1000, -1).getEdgeIndex
  }

  private def createDummyEdgeFromVertex(stopVertex: VertexStore#Vertex): Int = {
    val toVert = transportNetwork.streetLayer.vertexStore.addVertex(stopVertex.getLat + 0.001, stopVertex.getLon + 0.001)
    transportNetwork.streetLayer.edgeStore.addStreetPair(stopVertex.index, toVert, 1000, -1).getEdgeIndex
  }
}


