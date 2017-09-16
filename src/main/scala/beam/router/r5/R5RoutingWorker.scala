package beam.router.r5

import java.io.File
import java.nio.file.{Files, Paths}
import java.nio.file.Files.exists
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor.Props
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, StreetVehicle}
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.{RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM, TRANSIT, WALK}
import beam.router.Modes.{BeamMode, _}
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel._
import beam.router.RoutingWorker.HasProps
import beam.router.gtfs.FareCalculator
import beam.router.r5.NetworkCoordinator._
import beam.router.r5.R5RoutingWorker.{ProfileRequestToVehicles, TripFareTuple}
import beam.router.{Modes, RoutingWorker, TrajectoryByEdgeIdsResolver}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils._
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.opentripplanner.routing.vertextype.TransitStop

import scala.collection.JavaConverters._
import scala.collection.mutable

class R5RoutingWorker(val beamServices: BeamServices) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
  //  val localDateAsString: String = "2016-10-17"
  //  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond
  //TODO make this actually come from beamConfig
  //  val graphPathOutputsNeeded = beamServices.beamConfig.beam.outputs.writeGraphPathTraversals
  val graphPathOutputsNeeded = false
  val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters // meters

  override def init: Unit = {
  }

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
    //    transportNetwork.transitLayer.routes.listIterator().asScala.foreach{ routeInfo =>
    //      log.debug(routeInfo.toString)
    //    }
    log.info(s"Start Transit initialization  ${ transportNetwork.transitLayer.tripPatterns.size()} trips founded")
    val transitTrips  = transportNetwork.transitLayer.tripPatterns.listIterator().asScala.toArray
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
          var stopStopDepartTuple: Tuple3[String,String,Long] = ("","",0L)
          var previousBeamLeg: Option[BeamLeg] = None
          val travelStops = transitTrip.departures.zipWithIndex.sliding(2)
          travelStops.foreach { case Array((departureFrom, from), (departureTo, to)) =>
            val duration = transitTrip.arrivals(to) - departureFrom
            //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
            //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
            val fromStopIdx = tripPattern.stops(from)
            val toStopIdx = tripPattern.stops(to)
            val fromStopId = tripPattern.stops(from).toString
            val toStopId = tripPattern.stops(to).toString
            val stopsInfo = TransitStopsInfo(fromStopId, toStopId)
            val transitPath = if (isOnStreetTransit(mode)) {
              beamPathBuilder.routeTransitPathThroughStreets(departureFrom.toLong, fromStopIdx, toStopIdx, stopsInfo)
            } else {
              val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx, toStopIdx)
              BeamPath(edgeIds, Option(stopsInfo), new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureFrom.toLong, duration) )
            }
            val theLeg = BeamLeg(departureFrom.toLong, mode, duration, transitPath)
            passengerSchedule.addLegs(Seq(theLeg))
            beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)

            previousBeamLeg match {
              case Some(prevLeg) =>
                beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(prevLeg,Some(theLeg)))
              case None =>
            }
            previousBeamLeg = Some(theLeg)
            stopStopDepartTuple = (previousBeamLeg.get.travelPath.transitStops.get.fromStopId, previousBeamLeg.get.travelPath.transitStops.get.toStopId, previousBeamLeg.get.startTime)
//            if(stopStopDepartTuple._1.eq("0") && stopStopDepartTuple._2.eq("23")){
            if(stopStopDepartTuple._1.equals("0") || stopStopDepartTuple._2.equals("0")){
              val i = 0
            }
          }
          beamServices.transitLegsByStopAndDeparture += (stopStopDepartTuple -> BeamLegWithNext(previousBeamLeg.get,None))
        } else {
          log.warning(s"Transit trip  ${transitTrip.tripId} has only one stop ")
          val departureStart = transitTrip.departures(0)
          val fromStopIdx = tripPattern.stops(0)
          //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
          //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
          val fromStopId = fromStopIdx.toString
          val duration = 1L
          val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx)
          val stopsInfo = TransitStopsInfo(fromStopId, fromStopId)
          val transitPath = BeamPath(edgeIds, Option(stopsInfo),
            new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureStart.toLong, duration) )
          val theLeg = BeamLeg(departureStart.toLong, mode, duration, transitPath)
          passengerSchedule.addLegs(Seq(theLeg))
          beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)
        }

        (tripVehId, route, passengerSchedule)
      }
    }
    val transitScheduleToCreate = transitData.filter(_._3.schedule.nonEmpty).sortBy(_._3.getStartLeg().startTime)
    transitScheduleToCreate.foreach{ case (tripVehId, route, passengerSchedule) =>
      createTransitVehicle(tripVehId, route, passengerSchedule)
    }

    log.info(s"Finished Transit initialization trips, ${transitData.length}")
  }

  def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, passengerSchedule: PassengerSchedule) = {

    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create(mode.toString.toLowerCase, classOf[VehicleType])
    val vehicleType = transitVehicles.getVehicleTypes.get(vehicleTypeId)
    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR| RAIL| FERRY) if vehicleType != null =>
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

  override def calcRoute(requestId: Id[RoutingRequest], routingRequestTripInfo: RoutingRequestTripInfo, person: Person): RoutingResponse = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val isRouteForPerson = routingRequestTripInfo.streetVehicles.exists(_.mode == WALK)

    val profileRequestToVehicles: ProfileRequestToVehicles = if(isRouteForPerson){
      buildRequestsForPerson(routingRequestTripInfo)
    } else {
      buildRequestsForNonPerson(routingRequestTripInfo)
    }
    val originalResponse = buildResponse(pointToPointQuery.getPlan(profileRequestToVehicles.originalProfile), isRouteForPerson)
    val walkModeToVehicle: Map[BeamMode, StreetVehicle] = if (isRouteForPerson) Map(WALK -> profileRequestToVehicles.originalProfileModeToVehicle(WALK).head) else Map()

    var embodiedTrips: Vector[EmbodiedBeamTrip] = Vector()
    originalResponse.trips.zipWithIndex.filter(_._1.accessMode == WALK).foreach { trip =>
      embodiedTrips = embodiedTrips :+ EmbodiedBeamTrip.embodyWithStreetVehicles(trip._1, walkModeToVehicle, walkModeToVehicle, originalResponse.tripFares(trip._2), beamServices)
    }

    profileRequestToVehicles.originalProfileModeToVehicle.keys.foreach { mode =>
      val streetVehicles = profileRequestToVehicles.originalProfileModeToVehicle(mode)
      originalResponse.trips.zipWithIndex.filter(_._1.accessMode == mode).foreach { trip =>
        streetVehicles.foreach { veh: StreetVehicle =>
          embodiedTrips = embodiedTrips :+ EmbodiedBeamTrip.embodyWithStreetVehicles(trip._1, walkModeToVehicle ++ Map(mode -> veh), walkModeToVehicle, originalResponse.tripFares(trip._2), beamServices)
        }
      }
    }

    //TODO: process the walkOnly and vehicleCentered profiles and their responses here...

    RoutingResponse(requestId, embodiedTrips)
  }

  protected def buildRequestsForNonPerson(routingRequestTripInfo: RoutingRequestTripInfo): ProfileRequestToVehicles = {
    val originalProfileModeToVehicle = new mutable.HashMap[BeamMode, mutable.Set[StreetVehicle]] with mutable.MultiMap[BeamMode, StreetVehicle]
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // From requester's origin to destination, the street modes must be within XXm of origin because this agent can't walk
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val streetVehiclesAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) <= distanceThresholdToIgnoreWalking  )
    if(streetVehiclesAtRequesterOrigin.isEmpty){
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
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.origin)
    val toPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.destination)
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
//    profileRequest.maxWalkTime = 2*60
    profileRequest.maxCarTime = 3*60
//    profileRequest.maxBikeTime = 3*60
    profileRequest.maxTripDurationMinutes=3*60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
//      ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate
    profileRequest.directModes = util.EnumSet.copyOf( uniqueLegModes.asJavaCollection )
    // We constrain these to be non-transit trips since they are by NonPersons who we assume don't board transit
    profileRequest.accessModes = profileRequest.directModes

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle, Vector(), Map())
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
    var walkOnlyProfiles: Vector[ProfileRequest] = Vector[ProfileRequest]()
    var vehicleAsOriginProfiles: Map[ProfileRequest, StreetVehicle] = Map[ProfileRequest, StreetVehicle]()

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // First request is from requester's origin to destination, the street modes in addition to WALK depend on
    // whether StreetVehicles are within XXm of the origin
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val streetVehiclesAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) <= distanceThresholdToIgnoreWalking  )
    val uniqueBeamModes: Vector[BeamMode] = streetVehiclesAtRequesterOrigin.map(_.mode).distinct
    val uniqueLegModes: Vector[LegMode] = uniqueBeamModes.map(_.r5Mode.get match { case Left(leg) => leg }).distinct
    uniqueBeamModes.foreach(beamMode =>
      streetVehiclesAtRequesterOrigin.filter(_.mode == beamMode).foreach(veh =>
        originalProfileModeToVehicle.addBinding(beamMode, veh)
      )
    )
    if (!uniqueBeamModes.contains(WALK))
      log.warning("R5RoutingWorker expects a HumanBodyVehicle to be included in StreetVehicle vector passed from RoutingRequest but none were found.")

    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.origin)
    val toPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.destination)
    val split = transportNetwork.streetLayer.findSplit(fromPosTransformed.getX,fromPosTransformed.getY,1000,StreetMode.WALK)
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.maxWalkTime = 60
    profileRequest.maxCarTime = 3*60
    profileRequest.maxBikeTime = 3*60
    profileRequest.maxTripDurationMinutes=3*60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf( uniqueLegModes.asJavaCollection )
    val isTransit = routingRequestTripInfo.transitModes.nonEmpty
    if (isTransit) {
      val transitModes: Vector[TransitModes] = routingRequestTripInfo.transitModes.map(_.r5Mode.get.right.get)
      profileRequest.transitModes = util.EnumSet.copyOf(transitModes.asJavaCollection)
      profileRequest.accessModes = profileRequest.directModes
      profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The next requests are for walk only trips to vehicles and simultaneously the vehicle to destination
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //TODO can we configure the walkOnly trips so that only one alternative is returned by R5 or do we need to deal with that in post?
    val streetVehiclesNotAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) > distanceThresholdToIgnoreWalking )
    streetVehiclesNotAtRequesterOrigin.foreach{ veh =>
      // Walking to Vehicle
      val newFromPosTransformed = veh.location.loc.toWgs
      val newProfileRequest = profileRequest.clone()
      newProfileRequest.toLon = newFromPosTransformed.getX
      newProfileRequest.toLat = newFromPosTransformed.getY
      newProfileRequest.directModes = util.EnumSet.copyOf(Vector(LegMode.WALK).asJavaCollection)
      walkOnlyProfiles = walkOnlyProfiles :+ newProfileRequest

      // Vehicle to Destination
      val vehToDestination = profileRequest.clone()
      vehToDestination.fromLon = newFromPosTransformed.getX
      vehToDestination.fromLat = newFromPosTransformed.getY
      vehToDestination.directModes = util.EnumSet.copyOf(Vector(veh.mode.r5Mode.get.left.get).asJavaCollection)
      vehicleAsOriginProfiles = vehicleAsOriginProfiles + (vehToDestination -> veh)
    }

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle, walkOnlyProfiles, vehicleAsOriginProfiles)
  }


  private def createProfileRequest(routingRequestTripInfo: RoutingRequestTripInfo) = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromLocation = beamServices.geo.utm2Wgs(routingRequestTripInfo.origin)
    val toLocation = beamServices.geo.utm2Wgs(routingRequestTripInfo.destination)
    profileRequest.fromLon = fromLocation.getX
    profileRequest.fromLat = fromLocation.getY
    profileRequest.toLon = toLocation.getX
    profileRequest.toLat = toLocation.getY
    // Max times for walk, bike or car may not required, as R5 already has reasonable defaults, see ProfileRequest
    //    profileRequest.maxWalkTime = 3*60
    //    profileRequest.maxCarTime = 6*60
    //    profileRequest.maxBikeTime = 3*60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest
  }


  def buildResponse(plan: ProfileResponse, forPerson: Boolean): TripFareTuple = {

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
        val tripStartTime = beamServices.dates.toBaseMidnightSeconds(itinerary.startTime,transportNetwork.transitLayer.routes.size()==0)
        val isTransit = itinerary.connection.transit != null && !itinerary.connection.transit.isEmpty
        legs = legs :+ BeamLeg(tripStartTime, mapLegMode(access.mode), access.duration, travelPath = beamPathBuilder.buildStreetPath(access,tripStartTime))

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
          val fares = FareCalculator.filterTransferFares(FareCalculator.getFareSegments(segments.toVector))

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
            arrivalTime = beamServices.dates.toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time),isTransit)
            if (transitSegment.middle != null) {
              isMiddle = true
              legs = legs :+ BeamLeg(arrivalTime, mapLegMode(transitSegment.middle.mode), transitSegment.middle.duration, travelPath = beamPathBuilder.buildStreetPath(transitSegment.middle,arrivalTime))
              arrivalTime = arrivalTime + transitSegment.middle.duration // in case of middle arrival time would update
            }
          }

          // egress would only be present if there is some transit, so its under transit presence check
          if (itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            //start time would be the arrival time of last stop and 5 second alighting
            legs = legs :+ BeamLeg(arrivalTime, mapLegMode(egress.mode), egress.duration, beamPathBuilder.buildStreetPath(egress,arrivalTime))
            if (forPerson && egress.mode != LegMode.WALK) legs :+ dummyWalk(arrivalTime + egress.duration)
          }
        }

        trips = trips :+ BeamTrip(legs, mapLegMode(access.mode))
        tripFares = tripFares :+ legFares
      })
    })
    TripFareTuple(trips, tripFares)
  }

  /*
  private def buildStreetPath(segment: StreetSegment): BeamStreetPath = {
    var activeLinkIds = Vector[String]()
    var spaceTime = Vector[SpaceTime]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
      //      if(graphPathOutputsNeeded) {
      //        activeCoords = activeCoords :+ GeoUtils.toCoord(edge.geometry)
      //      }
      //TODO: time need to be extrected and provided as last argument of SpaceTime
      spaceTime = spaceTime :+ SpaceTime(edge.geometry.getCoordinate.x, edge.geometry.getCoordinate.y, -1)
    }

    BeamStreetPath(activeLinkIds, trajectory = Some(spaceTime))
  }
  */

  private def buildPath(departureTime: Long, mode: BeamMode, totalDuration: Long, transitSegment: TransitSegment, transitJourneyID: TransitJourneyID): Vector[BeamLeg] = {
    var legs: Vector[BeamLeg] = Vector()
    val segmentPattern: SegmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
    val beamVehicleId = Id.createVehicleId(segmentPattern.tripIds.get(transitJourneyID.time))
    val tripPattern = transportNetwork.transitLayer.tripPatterns.get(transitSegment.segmentPatterns.get(0).patternIdx)
    val allStopInds = tripPattern.stops.map(transportNetwork.transitLayer.stopIdForIndex.get(_)).toVector
    val stopsInTrip = tripPattern.stops.map(_.toString).toVector.slice(allStopInds.indexOf(transitSegment.from.stopId), allStopInds.indexOf(transitSegment.to.stopId)+1)

    var workingDepature = departureTime
    if(stopsInTrip.size==1){
      log.debug("Access and egress point the same on trip. No transit needed.")
      legs
    }else {
      stopsInTrip.sliding(2).foreach { stopPair =>
        val legPair = beamServices.transitLegsByStopAndDeparture.get((stopPair(0), stopPair(1), workingDepature))
        legPair match {
          case Some(lp) =>
            legs = legs :+ lp.leg
            lp.nextLeg match {
              case Some(theNextLeg) =>
                workingDepature = theNextLeg.startTime
              case None =>
                log.warning(s"Leg pair ${stopPair(0)} to ${stopPair(1)} at ${workingDepature} not found in beamServices.transitLegsByStopAndDeparture")
            }
          case None =>
        }
      }
      legs
    }
  }

  def createStopId(stopId: String): Id[TransitStop] = {
    Id.create(stopId, classOf[TransitStop])
  }

  /*
    private def buildPath(profileRequest: ProfileRequest, streetMode: StreetMode): BeamStreetPath = {
      val streetRouter = new StreetRouter(transportNetwork.streetLayer)
      streetRouter.profileRequest = profileRequest
      streetRouter.streetMode = streetMode

      // TODO use target pruning instead of a distance limit
      streetRouter.distanceLimitMeters = 100000

      streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
      streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)

      streetRouter.route

      //Gets lowest weight state for end coordinate split
      val lastState = streetRouter.getState(streetRouter.getDestinationSplit())
      val streetPath = new StreetPath(lastState, transportNetwork)

      var activeLinkIds = Vector[String]()
      //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
      var activeCoords = Vector[Coord]()
      var activeTimes = Vector[Long]()

      for (state <- streetPath.getStates.asScala) {
        val edgeIdx = state.backEdge
        if (edgeIdx != -1) {
          val edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx)
          activeLinkIds = activeLinkIds :+ edgeIdx.toString
          if(graphPathOutputsNeeded){
            activeCoords = activeCoords :+ GeoUtils.toCoord(edge.getGeometry)
            activeTimes = activeTimes :+ state.getDurationSeconds.toLong
          }
        }
      }
      BeamStreetPath(activeLinkIds, activeCoords, activeTimes)
    }*/

  private def transitVehicles = {
    beamServices.matsimServices.getScenario.getTransitVehicles
  }
}

object R5RoutingWorker extends HasProps {
  override def props(beamServices: BeamServices) = Props(classOf[R5RoutingWorker], beamServices)

  case class ProfileRequestToVehicles(originalProfile: ProfileRequest,
                                      originalProfileModeToVehicle: mutable.Map[BeamMode, mutable.Set[StreetVehicle]],
                                      walkOnlyProfiles: Vector[ProfileRequest],
                                      vehicleAsOriginProfiles: Map[ProfileRequest, StreetVehicle])

  case class TripFareTuple(trips: Vector[BeamTrip], tripFares: Vector[Map[Int, Double]])

}