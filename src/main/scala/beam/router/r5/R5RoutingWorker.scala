package beam.router.r5

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, StreetVehicle}
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.{RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM, WALK}
import beam.router.Modes.{BeamMode, _}
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel._
import beam.router.RoutingWorker.HasProps
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.r5.NetworkCoordinator.{beamPathBuilder, _}
import beam.router.r5.R5RoutingWorker.{ProfileRequestToVehicles, TripsWithFares}
import beam.router.{Modes, RoutingWorker, TrajectoryByEdgeIdsResolver}
import beam.sim.BeamServices
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.common.JsonUtilities
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.ProfileRequest
import com.conveyal.r5.transit.{RouteInfo, TransitLayer}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class R5RoutingWorker(val beamServices: BeamServices, val fareCalculator: ActorRef, val workerId: Int) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
  //  val localDateAsString: String = "2016-10-17"
  //  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond
  //TODO make this actually come from beamConfig
  //  val graphPathOutputsNeeded = beamServices.beamConfig.beam.outputs.writeGraphPathTraversals
  val graphPathOutputsNeeded = false
  val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters // meters
  var hasWarnedAboutLegPair = Set[Tuple2[Int, Int]]()

  implicit val timeout = Timeout(5 seconds)

  /*
   * Plan of action:
   * Each TripSchedule within each TripPattern represents a transit vehicle trip and will spawn a transitDriverAgent and a vehicle
   * The arrivals/departures within the TripSchedules are vectors of the same length as the "stops" field in the TripPattern
   * The stop IDs will be used to extract the Coordinate of the stop from the transitLayer (don't see exactly how yet)
   * Also should hold onto the route and trip IDs and use route to lookup the transit agency which ultimately should
   * be used to decide what type of vehicle to assign
   *
   */
  def initTransit(): Unit = {
    val transitCache = mutable.Map[(Int, Int), BeamPath]()

    val size = transportNetwork.transitLayer.tripPatterns.size()
    val workerNumber = beamServices.beamConfig.beam.routing.workerNumber
    val patternsPerWorker = size / workerNumber
    val patternsStartIndex = patternsPerWorker * workerId
    //XXX: last worker takes rest of schedule
    val patternsEndIndex = if (workerId == workerNumber - 1) {
      size
    } else {
      patternsStartIndex + patternsPerWorker
    }
    log.info(s" ${transportNetwork.transitLayer.tripPatterns.size()} trips founded, Start Transit initialization of [$patternsStartIndex, $patternsEndIndex) slice")
    val transitTrips = transportNetwork.transitLayer.tripPatterns.subList(patternsStartIndex, patternsEndIndex).asScala.toArray
    val transitData = transitTrips.flatMap { tripPattern =>
      //      log.debug(tripPattern.toString)
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitRouteTrips = tripPattern.tripSchedules.asScala
      transitRouteTrips.filter(_.getNStops > 0).map { transitTrip =>
        // First create a unique for this trip which will become the transit agent and vehicle ids
        val tripVehId = Id.create(transitTrip.tripId, classOf[Vehicle])
        val numStops = transitTrip.departures.length
        val passengerSchedule = PassengerSchedule()

        if (numStops > 1) {
          var stopStopDepartTuple = (-1, -1, 0L)
          var previousBeamLeg: Option[BeamLeg] = None
          val travelStops = transitTrip.departures.zipWithIndex.sliding(2)
          travelStops.foreach { case Array((departureTimeFrom, from), (depatureTimeTo, to)) =>
            val duration = transitTrip.arrivals(to) - departureTimeFrom
            //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
            //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
            val fromStopIdx = tripPattern.stops(from)
            val toStopIdx = tripPattern.stops(to)
            val fromStopId = tripPattern.stops(from)
            val toStopId = tripPattern.stops(to)
            val stopsInfo = TransitStopsInfo(fromStopId, toStopId)
            if(tripVehId.toString.equals("SM:43|10748241:T1|15:00") && departureTimeFrom.toLong == 1500L){
              val i =0
            }
            val transitPath = if (isOnStreetTransit(mode)) {
              transitCache.get((fromStopIdx,toStopIdx)).fold{
                val bp = beamPathBuilder.routeTransitPathThroughStreets(departureTimeFrom.toLong, fromStopIdx, toStopIdx, stopsInfo, duration)
                transitCache += ((fromStopIdx,toStopIdx)->bp)
              bp}
              {x =>
                beamPathBuilder.createFromExistingWithUpdatedTimes(x,departureTimeFrom,duration)
              }
            } else {
              val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx, toStopIdx)
              BeamPath(edgeIds, Option(stopsInfo), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTimeFrom.toLong, duration))
            }
            val theLeg = BeamLeg(departureTimeFrom.toLong, mode, duration, transitPath)
            passengerSchedule.addLegs(Seq(theLeg))
            beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)

            previousBeamLeg.foreach { prevLeg =>
              beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(prevLeg, Some(theLeg)))
            }
            previousBeamLeg = Some(theLeg)
            val previousTransitStops: TransitStopsInfo = previousBeamLeg.get.travelPath.transitStops match {
              case Some(stops) =>
                stops
              case None =>
                TransitStopsInfo(-1, -1)
            }
            stopStopDepartTuple = (previousTransitStops.fromStopId, previousTransitStops.toStopId, previousBeamLeg.get.startTime)
          }
          beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(previousBeamLeg.get, None))
        } else {
          log.warning(s"Transit trip  ${transitTrip.tripId} has only one stop ")
          val departureStart = transitTrip.departures(0)
          val fromStopIdx = tripPattern.stops(0)
          //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
          //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
          val duration = 1L
          val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx)
          val stopsInfo = TransitStopsInfo(fromStopIdx, fromStopIdx)
          val transitPath = BeamPath(edgeIds, Option(stopsInfo),
            new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureStart.toLong, duration))
          val theLeg = BeamLeg(departureStart.toLong, mode, duration, transitPath)
          passengerSchedule.addLegs(Seq(theLeg))
          beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)
        }

        (tripVehId, route, passengerSchedule)
      }
    }
    val transitScheduleToCreate = transitData.filter(_._3.schedule.nonEmpty).sortBy(_._3.getStartLeg().startTime)
    transitScheduleToCreate.foreach { case (tripVehId, route, passengerSchedule) =>
      createTransitVehicle(tripVehId, route, passengerSchedule)
    }

    log.info(s"Finished Transit initialization trips, ${transitData.length}")
  }

  def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, passengerSchedule: PassengerSchedule) = {

    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

    val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)){
      transitVehicles.getVehicleTypes.get(vehicleTypeId);
    } else {
      log.info(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
      transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]));
    }

    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
        val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
        matSimTransitVehicle.getType.setDescription(mode.value)
        val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon)
        val transitVehProps = TransitVehicle.props(beamServices, matSimTransitVehicle.getId, TransitVehicleData(), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
        beamServices.vehicles += (transitVehId -> matSimTransitVehicle)
        beamServices.vehicleRefs += (transitVehId -> transitVehRef)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitVehRef)

        val vehicleIdAndRef = BeamVehicleIdAndRef(transitVehId, transitVehRef)
        val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
        val transitDriverAgentProps = TransitDriverAgent.props(beamServices, transitDriverId, vehicleIdAndRef, passengerSchedule)
        val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
        beamServices.agentRefs += (transitDriverId.toString -> transitDriver)
        beamServices.transitDriversByVehicle += (transitVehId -> transitDriverId)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

      case _ =>
        log.error(mode + " is not supported yet")
    }
  }

  override def calcRoute(requestId: Id[RoutingRequest], routingRequestTripInfo: RoutingRequestTripInfo): RoutingResponse = {
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val isRouteForPerson = routingRequestTripInfo.streetVehicles.exists(_.mode == WALK)

    val profileRequestWithVehicles: ProfileRequestToVehicles = if (isRouteForPerson) {
      buildRequestsForPerson(routingRequestTripInfo)
    } else {
      buildRequestsForNonPerson(routingRequestTripInfo)
    }
    val profileResponse = pointToPointQuery.getPlan(profileRequestWithVehicles.profileRequest)
    val tripsWithFares = buildResponse(profileResponse, isRouteForPerson)
    val walkModeToVehicle: Map[BeamMode, StreetVehicle] = if (isRouteForPerson) Map(WALK -> profileRequestWithVehicles.modeToVehicles(WALK).head) else Map()

    var embodiedTrips: Vector[EmbodiedBeamTrip] = Vector()
    tripsWithFares.trips.zipWithIndex.filter(_._1.accessMode == WALK).foreach { trip =>
      embodiedTrips :+= EmbodiedBeamTrip.embodyWithStreetVehicles(trip._1, walkModeToVehicle, walkModeToVehicle, tripsWithFares.tripFares(trip._2), beamServices)
    }

    profileRequestWithVehicles.modeToVehicles.keys.foreach { mode =>
      val streetVehicles = profileRequestWithVehicles.modeToVehicles(mode)
      tripsWithFares.trips.zipWithIndex.filter(_._1.accessMode == mode).foreach { trip =>
        streetVehicles.foreach { veh: StreetVehicle =>
          embodiedTrips :+= EmbodiedBeamTrip.embodyWithStreetVehicles(trip._1, walkModeToVehicle ++ Map(mode -> veh), walkModeToVehicle, tripsWithFares.tripFares(trip._2), beamServices)
        }
      }
    }
    if(embodiedTrips.isEmpty) {
      log.warning("No route found. {}", JsonUtilities.objectMapper.writeValueAsString(profileRequestWithVehicles.profileRequest))
      embodiedTrips :+= EmbodiedBeamTrip(BeamTrip(Vector(BeamLeg(routingRequestTripInfo.departureTime.atTime, WALK, profileRequestWithVehicles.profileRequest.streetTime * 60))))
    }

    RoutingResponse(requestId, embodiedTrips)
  }

  protected def buildRequestsForNonPerson(routingRequestTripInfo: RoutingRequestTripInfo): ProfileRequestToVehicles = {
    val originalProfileModeToVehicle = new mutable.HashMap[BeamMode, mutable.Set[StreetVehicle]] with mutable.MultiMap[BeamMode, StreetVehicle]

    // From requester's origin to destination, the street modes must be within XXm of origin because this agent can't walk
    val streetVehiclesAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) <= distanceThresholdToIgnoreWalking)
    if (streetVehiclesAtRequesterOrigin.isEmpty) {
      log.error(s"A routing request for a Non Person (which therefore cannot walk) was submitted with no StreetVehicle within ${distanceThresholdToIgnoreWalking} m of the requested origin.")
    }
    val uniqueBeamModes: Vector[BeamMode] = streetVehiclesAtRequesterOrigin.map(_.mode).distinct
    val uniqueLegModes: Vector[LegMode] = uniqueBeamModes.map(_.r5Mode.get match { case Left(leg) => leg }).distinct
    uniqueBeamModes.foreach(beamMode =>
      streetVehiclesAtRequesterOrigin.filter(_.mode == beamMode).foreach(veh =>
        originalProfileModeToVehicle.addBinding(beamMode, veh)
      )
    )

    val profileRequest = new ProfileRequest()
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.origin)
    val toPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.destination)
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.maxWalkTime = 2 * 60
    profileRequest.maxCarTime = 3 * 60
    profileRequest.streetTime = 0
    profileRequest.maxBikeTime = 3*60
    profileRequest.maxTripDurationMinutes = 3 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5.departureWindow)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(uniqueLegModes.asJavaCollection)

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle)
  }

  /*
     * buildRequests
     *
     * Here we build the Vector of routing requests to send to R5. There could be 1-3 origins associated with the
     * location of the requester and her CAR and BIKE if those personal vehicles are sufficiently far from her location
     * (otherwise we ignore the difference).
     */
  protected def buildRequestsForPerson(routingRequestTripInfo: RoutingRequestTripInfo): ProfileRequestToVehicles = {

    val originalProfileModeToVehicle = new mutable.HashMap[BeamMode, mutable.Set[StreetVehicle]] with mutable.MultiMap[BeamMode, StreetVehicle]
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // First request is from requester's origin to destination, the street modes in addition to WALK depend on
    // whether StreetVehicles are within XXm of the origin
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val streetVehiclesAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) <= distanceThresholdToIgnoreWalking)
    val uniqueBeamModes: Vector[BeamMode] = streetVehiclesAtRequesterOrigin.map(_.mode).distinct
    uniqueBeamModes.foreach(beamMode =>
      streetVehiclesAtRequesterOrigin.filter(_.mode == beamMode).foreach(veh =>
        originalProfileModeToVehicle.addBinding(beamMode, veh)
      )
    )
    if (!uniqueBeamModes.contains(WALK))
      log.warning("R5RoutingWorker expects a HumanBodyVehicle to be included in StreetVehicle vector passed from RoutingRequest but none were found.")

    val uniqueLegModes: Vector[LegMode] = uniqueBeamModes.map(_.r5Mode.get match { case Left(leg) => leg }).distinct
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed =  beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,beamServices.geo.utm2Wgs(routingRequestTripInfo.origin),10E3)
    val toPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,beamServices.geo.utm2Wgs(routingRequestTripInfo.destination),10E3)
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.maxWalkTime = 3 * 60
    profileRequest.maxCarTime = 4 * 60
    profileRequest.maxBikeTime = 4 * 60
    profileRequest.streetTime = 2 * 60

    profileRequest.maxTripDurationMinutes = 4 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5.departureWindow)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(uniqueLegModes.asJavaCollection)
    val isTransit = routingRequestTripInfo.transitModes.nonEmpty
    if (isTransit) {
      val transitModes: Vector[TransitModes] = routingRequestTripInfo.transitModes.map(_.r5Mode.get.right.get)
      profileRequest.transitModes = util.EnumSet.copyOf(transitModes.asJavaCollection)
      profileRequest.accessModes = util.EnumSet.copyOf(uniqueLegModes.asJavaCollection)
      profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    }

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle)
  }

  def buildResponse(plan: ProfileResponse, forPerson: Boolean): TripsWithFares = {

    var trips = Vector[BeamTrip]()
    var tripFares = Vector[Map[Int, Double]]()
    plan.options.asScala.foreach(option => {
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
      option.itinerary.asScala.foreach(itinerary => {
        var legs = Vector[BeamLeg]()
        var legFares = Map[Int, Double]()

        val access = option.access.get(itinerary.connection.access)

        // Using itinerary start as access leg's startTime
        val tripStartTime = beamServices.dates.toBaseMidnightSeconds(itinerary.startTime, transportNetwork.transitLayer.routes.size() == 0)
        val isTransit = itinerary.connection.transit != null && !itinerary.connection.transit.isEmpty
        legs = legs :+ BeamLeg(tripStartTime, mapLegMode(access.mode), access.duration, travelPath = beamPathBuilder.buildStreetPath(access, tripStartTime))

        //add a Dummy BeamLeg to the beginning and end of that trip BeamTrip using the dummyWalk
        if (forPerson && access.mode != LegMode.WALK) {
          legs = dummyWalk(tripStartTime) +: legs
          if (!isTransit) legs = legs :+ dummyWalk(tripStartTime + access.duration)
        }

        if (isTransit) {
          var arrivalTime: Long = Long.MinValue
          var isMiddle: Boolean = false
          /*
           Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
           assuming that: For each transit in option there is a TransitJourneyID in connection
           */
          val segments = option.transit.asScala zip itinerary.connection.transit.asScala
          val fares = filterTransferFares(getFareSegments(segments.toVector))

          segments.foreach { case (transitSegment, transitJourneyID) =>

            val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)

            val fs = fares.filter(_.patternIndex == transitJourneyID.pattern).map(_.fare.price)
            val fare = if (fs.nonEmpty) fs.min else 0.0

            // when this is the last SegmentPattern, we should use the toArrivalTime instead of the toDepartureTime
            val duration = (if (option.transit.indexOf(transitSegment) < option.transit.size() - 1)
              segmentPattern.toDepartureTime
            else
              segmentPattern.toArrivalTime).get(transitJourneyID.time).toEpochSecond -
              segmentPattern.fromDepartureTime.get(transitJourneyID.time).toEpochSecond

            val segmentLegs = buildPath(beamServices.dates.toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time), isTransit),
              mapTransitMode(transitSegment.mode),
              duration,
              transitSegment,
              transitJourneyID)

            legFares += legs.size -> fare
            legs = legs ++ segmentLegs
            arrivalTime = beamServices.dates.toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time), isTransit)
            if (transitSegment.middle != null) {
              isMiddle = true
              legs = legs :+ BeamLeg(arrivalTime, mapLegMode(transitSegment.middle.mode), transitSegment.middle.duration, travelPath = beamPathBuilder.buildStreetPath(transitSegment.middle, arrivalTime))
              arrivalTime = arrivalTime + transitSegment.middle.duration // in case of middle arrival time would update
            }
          }

          // egress would only be present if there is some transit, so its under transit presence check
          if (itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            //start time would be the arrival time of last stop and 5 second alighting
            legs = legs :+ BeamLeg(arrivalTime, mapLegMode(egress.mode), egress.duration, beamPathBuilder.buildStreetPath(egress, arrivalTime))
            if (forPerson && egress.mode != LegMode.WALK) legs :+ dummyWalk(arrivalTime + egress.duration)
          }
        }

        trips = trips :+ BeamTrip(legs, mapLegMode(access.mode))
        tripFares = tripFares :+ legFares
      })
    })
    TripsWithFares(trips, tripFares)
  }

  private def buildPath(departureTime: Long, mode: BeamMode, totalDuration: Long, transitSegment: TransitSegment, transitJourneyID: TransitJourneyID): Vector[BeamLeg] = {
    var legs: Vector[BeamLeg] = Vector()
    val segmentPattern: SegmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
    val beamVehicleId = Id.createVehicleId(segmentPattern.tripIds.get(transitJourneyID.time))
    val tripPattern = transportNetwork.transitLayer.tripPatterns.get(transitSegment.segmentPatterns.get(0).patternIdx)
    val allStopInds = tripPattern.stops.map(transportNetwork.transitLayer.stopIdForIndex.get(_)).toVector
    val stopsInTrip = tripPattern.stops.toVector.slice(allStopInds.indexOf(transitSegment.from.stopId), allStopInds.indexOf(transitSegment.to.stopId) + 1)

    if (stopsInTrip.size == 1) {
      log.debug("Access and egress point the same on trip. No transit needed.")
      legs
    } else {
      var workingDepature = departureTime
      stopsInTrip.sliding(2).foreach { stopPair =>
        val legPair = beamServices.transitLegsByStopAndDeparture.get((stopPair(0), stopPair(1), workingDepature))
        legPair match {
          case Some(lp) =>
            legs = legs :+ lp.leg
            lp.nextLeg match {
              case Some(theNextLeg) =>
                workingDepature = theNextLeg.startTime
              case None =>
                if(!hasWarnedAboutLegPair.contains(Tuple2(stopPair(0),stopPair(1)))){
                  log.warning(s"Leg pair ${stopPair(0)} to ${stopPair(1)} at ${workingDepature} not found in beamServices.transitLegsByStopAndDeparture")
                  hasWarnedAboutLegPair = hasWarnedAboutLegPair + Tuple2(stopPair(0),stopPair(1))
                }
            }
          case None =>
        }
      }
      legs
    }
  }

  private def transitVehicles = {
    beamServices.matsimServices.getScenario.getTransitVehicles
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments
    * @return a collection of FareSegments for an itinerary.
    */
  def getFareSegments(segments: Vector[(TransitSegment, TransitJourneyID)]): Vector[BeamFareSegment] = {
    segments.groupBy(s => getRoute(s._1, s._2).agency_id).flatMap(t => {
      val pattern = getPattern(t._2.head._1, t._2.head._2)
      val route = getRoute(pattern)
      val agencyId = route.agency_id
      val routeId = route.route_id

      val fromId = getStopId(t._2.head._1.from)
      val toId = getStopId(t._2.last._1.to)

      val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)
      val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime.get(t._2.last._2.time)
      val duration = ChronoUnit.SECONDS.between(fromTime, toTime)


      val containsIds = t._2.flatMap(s => Vector(getStopId(s._1.from), getStopId(s._1.to))).toSet

      var rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds).map(f => BeamFareSegment(f, t._2.head._2.pattern, duration))

      if (rules.isEmpty)
        rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

      rules
    }).toVector
  }

  def getFareSegments(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID, fromTime: ZonedDateTime): Vector[BeamFareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration = ChronoUnit.SECONDS.between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(f => BeamFareSegment(f, transitJourneyID.pattern, duration))
    if (fr.nonEmpty)
      fr = Vector(fr.minBy(_.fare.price))
    fr
  }

  def getFareSegments(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Vector[BeamFareSegment] = {
    val response = Await.result(ask(fareCalculator, FareCalculator.GetFareSegmentsRequest(agencyId, routeId, fromId, toId, containsIds)), timeout.duration).asInstanceOf[FareCalculator.GetFareSegmentsResponse]
    response.fareSegments
  }

  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes.get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

}

object R5RoutingWorker extends HasProps {
  override def props(beamServices: BeamServices, fareCalculator: ActorRef, workerId: Int) = Props(classOf[R5RoutingWorker], beamServices, fareCalculator, workerId)

  case class ProfileRequestToVehicles(profileRequest: ProfileRequest,
                                      modeToVehicles: mutable.Map[BeamMode, mutable.Set[StreetVehicle]])

  case class TripsWithFares(trips: Vector[BeamTrip], tripFares: Vector[Map[Int, Double]])

}