package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor.Props
import beam.agentsim.agents.{InitializeTrigger, TransitDriverAgent}
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, StreetVehicle}
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.{RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode.{BUS, SUBWAY, TRANSIT, WALK}
import beam.router.Modes.{BeamMode, _}
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel._
import beam.router.RoutingWorker.HasProps
import beam.router.r5.R5RoutingWorker.{GRAPH_FILE, ProfileRequestToVehicles, transportNetwork}
import beam.router.{Modes, RoutingWorker, StreetPathTrajectoryResolver, TrajectoryByEdgeIdsResolver}
import R5RoutingWorker.stopForIndex
import beam.sim.BeamServices
import beam.utils.RefectionUtils
import com.conveyal.gtfs.model
import com.conveyal.gtfs.model.Stop
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.{RouteInfo, TransitLayer, TransportNetwork}
import com.vividsolutions.jts.geom.LineString
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable

class R5RoutingWorker(val beamServices: BeamServices) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
//  val localDateAsString: String = "2016-10-17"
//  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond
  //TODO make this actually come from beamConfig
//  val graphPathOutputsNeeded = beamServices.beamConfig.beam.outputs.writeGraphPathTraversals
  val graphPathOutputsNeeded = false
  //TODO parameterize the distance threshold here
  val distanceThresholdToIgnoreWalking = beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters // meters


  override def init: Unit = {
    loadMap
    overrideR5EdgeSearchRadius(2000)
  }

  private def loadMap = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir()
    }
    val networkFilePath = Paths.get(networkDir, GRAPH_FILE)
    val networkFile : File = networkFilePath.toFile
    if (exists(networkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${networkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(networkFile)
      stopForIndex = TransportNetwork.fromDirectory(networkDirPath.toFile).transitLayer.stopForIndex
    } else {
      log.debug(s"Network file [${networkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating network from: ${networkDirPath.toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile)
      stopForIndex = transportNetwork.transitLayer.stopForIndex
      transportNetwork.write(networkFile)
      transportNetwork = TransportNetwork.read(networkFile) // Needed because R5 closes DB on write
    }
    val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
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
      transitRouteTrips.map { transitTrip =>
        // First create a unique for this trip which will become the transit agent and vehicle ids
        val tripVehId = Id.create(transitTrip.tripId, classOf[Vehicle])
        val numStops = transitTrip.departures.length
        val passengerSchedule = PassengerSchedule()
        transitTrip.departures.zipWithIndex.foreach { case (departure, i) =>
          val nextStopIndex = if (i == numStops - 1) {
            tripPattern.stops(0)
          } else {
            tripPattern.stops(i + 1)
          }
          val duration = if(i == numStops-1){ 1L }else{ transitTrip.arrivals(i+1) - departure }
          val fromStop = stopForIndex.get(tripPattern.stops(i))
          val toStop = stopForIndex.get(nextStopIndex)
          val edgeIds = resolveTransitEdges(fromStop, toStop)
          //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
          //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
          val fromStopId = transportNetwork.transitLayer.stopIdForIndex.get(tripPattern.stops(i))
          val toStopId = transportNetwork.transitLayer.stopIdForIndex.get(nextStopIndex)
          val transitLeg = BeamPath(edgeIds, Option(TransitStopsInfo(fromStopId, toStopId)), new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer) )
          val theLeg = BeamLeg(departure.toLong, mode, duration, transitLeg)
          passengerSchedule.addLegs(Seq(theLeg))
          beamServices.transitVehiclesByBeamLeg += (theLeg -> tripVehId)
        }
        (tripVehId,route,passengerSchedule)
      }
    }
    val transitScheduleToCreate = transitData.filter(_._3.schedule.nonEmpty).sortBy(_._3.getStartLeg().startTime)
    transitScheduleToCreate.foreach{ case (tripVehId, route, passengerSchedule) =>
      createTransitVehicle(tripVehId, route, passengerSchedule)
    }
    log.info(s"Finished Transit initialization trips, ${transitData.length}")
  }

  private def resolveTransitEdges(stops: model.Stop*) = {
    val edgeIds: Vector[String] = stops.flatMap { stop =>
      val split = transportNetwork.streetLayer.findSplit(stop.stop_lat, stop.stop_lon, 25, StreetMode.CAR)
      Option(split).map(_.edge.toString)
    }.toVector.distinct
    edgeIds
  }

  private def transitVehicles = {
    beamServices.matsimServices.getScenario.getTransitVehicles
  }

  def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, passengerSchedule: PassengerSchedule) = {
    //TODO we need to use the correct vehicle based on the agency and/or route info, for now we hard code 1 == BUS/OTHER and 2 == TRAIN
    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create((if(mode==SUBWAY){ 2 }else{ 1 }).toString, classOf[VehicleType])
    val vehicleType = transitVehicles.getVehicleTypes.get(vehicleTypeId)
    mode match {
      case (BUS | SUBWAY) if vehicleType != null =>
        val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
        val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon)
        val initialMatsimAttributes = new Attributes()
        val transitVehProps = TransitVehicle.props(beamServices, matSimTransitVehicle.getId, TransitVehicleData(), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, initialMatsimAttributes)
        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
        beamServices.vehicles.put(transitVehId, matSimTransitVehicle)
        beamServices.vehicleRefs.put(transitVehId, transitVehRef)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0),transitVehRef)

        val vehicleIdAndRef = BeamVehicleIdAndRef(transitVehId, transitVehRef)
        val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
        val transitDriverAgentProps = TransitDriverAgent.props(beamServices, transitDriverId, vehicleIdAndRef, passengerSchedule)
        val transitDriver =  context.actorOf(transitDriverAgentProps, transitDriverId.toString)
        beamServices.agentRefs.put(transitDriverId.toString, transitDriver)
        beamServices.transitDriversByVehicle.put(transitVehId,transitDriverId)
        beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

      case _ =>
        log.error(mode + " is not supported yet")

    }
  }

  override def calcRoute(requestId: Id[RoutingRequest], routingRequestTripInfo: RoutingRequestTripInfo, person: Person): RoutingResponse = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val isRouteForPerson = routingRequestTripInfo.streetVehicles.filter(_.mode == WALK).size > 0

    val profileRequestToVehicles: ProfileRequestToVehicles = if(isRouteForPerson){
      buildRequestsForPerson(routingRequestTripInfo)
    }else{
      buildRequestsForNonPerson(routingRequestTripInfo)
    }
    val originalResponse: Vector[BeamTrip] = buildResponse(pointToPointQuery.getPlan(profileRequestToVehicles.originalProfile),isRouteForPerson)
    val walkModeToVehicle: Map[BeamMode, StreetVehicle] = if(isRouteForPerson){ Map(WALK -> profileRequestToVehicles.originalProfileModeToVehicle(WALK).head) }else{ Map() }

    var embodiedTrips: Vector[EmbodiedBeamTrip] = Vector()
    originalResponse.filter(_.accessMode == WALK).foreach { trip =>
      embodiedTrips = embodiedTrips :+ EmbodiedBeamTrip.embodyWithStreetVehicles(trip, walkModeToVehicle, walkModeToVehicle, beamServices)
    }

    profileRequestToVehicles.originalProfileModeToVehicle.keys.foreach{ mode =>
      val streetVehicles = profileRequestToVehicles.originalProfileModeToVehicle(mode)
      originalResponse.filter(_.accessMode == mode).foreach { trip =>
        streetVehicles.foreach { veh: StreetVehicle =>
          embodiedTrips = embodiedTrips :+ EmbodiedBeamTrip.embodyWithStreetVehicles(trip, walkModeToVehicle ++ Map(mode -> veh), walkModeToVehicle, beamServices)
        }
      }
    }

    //TODO: process the walkOnly and vehicleCentered profiles and their responses here...

    RoutingResponse(requestId, embodiedTrips)
  }

  protected def buildRequestsForNonPerson(routingRequestTripInfo: RoutingRequestTripInfo): ProfileRequestToVehicles = {
    val originalProfileModeToVehicle = new mutable.HashMap[BeamMode, mutable.Set[StreetVehicle]] with mutable.MultiMap[BeamMode, StreetVehicle]
    var walkOnlyProfiles: Vector[ProfileRequest] = Vector[ProfileRequest]()
    var vehicleAsOriginProfiles: Map[ProfileRequest,StreetVehicle] = Map[ProfileRequest,StreetVehicle]()

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
        originalProfileModeToVehicle.addBinding(beamMode,veh)
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
    profileRequest.maxWalkTime = 3*3600
    profileRequest.maxCarTime = 6*3600
    profileRequest.maxBikeTime = 3*3600
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate
    profileRequest.directModes = util.EnumSet.copyOf( uniqueLegModes.asJavaCollection )
    // We constrain these to be non-transit trips since they are by NonPersons who we assume don't board transit
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle, walkOnlyProfiles, vehicleAsOriginProfiles)
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
    var vehicleAsOriginProfiles: Map[ProfileRequest,StreetVehicle] = Map[ProfileRequest,StreetVehicle]()

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // First request is from requester's origin to destination, the street modes in addition to WALK depend on
    // whether StreetVehicles are within XXm of the origin
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val streetVehiclesAtRequesterOrigin: Vector[StreetVehicle] = routingRequestTripInfo.streetVehicles.filter(veh => beamServices.geo.distInMeters(veh.location.loc, routingRequestTripInfo.origin) <= distanceThresholdToIgnoreWalking  )
    val uniqueBeamModes: Vector[BeamMode] = streetVehiclesAtRequesterOrigin.map(_.mode).distinct
    val uniqueLegModes: Vector[LegMode] = uniqueBeamModes.map(_.r5Mode.get match { case Left(leg) => leg }).distinct
    uniqueBeamModes.foreach(beamMode =>
      streetVehiclesAtRequesterOrigin.filter(_.mode == beamMode).foreach(veh =>
        originalProfileModeToVehicle.addBinding(beamMode,veh)
      )
    )
    if(!uniqueBeamModes.contains(WALK))
      log.warning("R5RoutingWorker expects a HumanBodyVehicle to be included in StreetVehicle vector passed from RoutingRequest but none were found.")

    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.origin)
    val toPosTransformed = beamServices.geo.utm2Wgs(routingRequestTripInfo.destination)
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.maxWalkTime = 3*3600
    profileRequest.maxCarTime = 6*3600
    profileRequest.maxBikeTime = 3*3600
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    val time = routingRequestTripInfo.departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate
    profileRequest.directModes = util.EnumSet.copyOf( uniqueLegModes.asJavaCollection )
    val isTransit = routingRequestTripInfo.transitModes.nonEmpty
    if(isTransit){
      val transitModes : Vector[TransitModes] = routingRequestTripInfo.transitModes.map(_.r5Mode.get.right.get)
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
      val newFromPosTransformed = beamServices.geo.utm2Wgs(veh.location.loc)
      val newProfileRequest = profileRequest.clone()
      newProfileRequest.toLon = newFromPosTransformed.getX
      newProfileRequest.toLat = newFromPosTransformed.getY
      newProfileRequest.directModes = util.EnumSet.copyOf(Vector(LegMode.WALK).asJavaCollection)
      walkOnlyProfiles = walkOnlyProfiles :+ newProfileRequest

      // Vehicle to Destination
      val newProfileRequest2 = profileRequest.clone()
      newProfileRequest2.fromLon = newFromPosTransformed.getX
      newProfileRequest2.fromLat = newFromPosTransformed.getY
      newProfileRequest2.directModes = util.EnumSet.copyOf(Vector(veh.mode.r5Mode.get.left.get).asJavaCollection)
      vehicleAsOriginProfiles = vehicleAsOriginProfiles + (newProfileRequest2 -> veh)
    }

    ProfileRequestToVehicles(profileRequest, originalProfileModeToVehicle, walkOnlyProfiles, vehicleAsOriginProfiles)
  }

  def buildResponse(plan: ProfileResponse, forPerson: Boolean): Vector[BeamTrip] = {

    var trips = Vector[BeamTrip]()
    for(option <- plan.options.asScala) {
//      log.debug(s"Summary of trip is: $option")
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
      for(itinerary <- option.itinerary.asScala) {
        var legs = Vector[BeamLeg]()

        val access = option.access.get(itinerary.connection.access)

        // Using itinerary start as access leg's startTime
        val tripStartTime = toBaseMidnightSeconds(itinerary.startTime)
        val isTransit = itinerary.connection.transit != null && !itinerary.connection.transit.isEmpty
        legs = legs :+ BeamLeg(tripStartTime, mapLegMode(access.mode), access.duration, buildStreetPath(access, tripStartTime))

        //add a Dummy BeamLeg to the beginning and end of that trip BeamTrip using the dummyWalk
        if(forPerson && access.mode != LegMode.WALK) {
          legs = dummyWalk(tripStartTime) +: legs
          if(!isTransit) legs = legs :+ dummyWalk(tripStartTime + access.duration)
        }

        if(isTransit) {
          var arrivalTime: Long = Long.MinValue
          var isMiddle: Boolean = false
          /*
           Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
           assuming that: For each transit in option there is a TransitJourneyID in connection
           */
          for ((transitSegment, transitJourneyID) <- option.transit.asScala zip itinerary.connection.transit.asScala) {

            val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)

            val toStopId: String = transportNetwork.transitLayer.stopIdForIndex.get(segmentPattern.toIndex)
            // when this is the last SegmentPattern, we should use the toArrivalTime instead of the toDepartureTime
            val duration = ( if(option.transit.indexOf(transitSegment) < option.transit.size() - 1)
                              segmentPattern.toDepartureTime
                            else
                              segmentPattern.toArrivalTime ).get(transitJourneyID.time).toEpochSecond -
              segmentPattern.fromDepartureTime.get(transitJourneyID.time).toEpochSecond

            legs = legs :+ new BeamLeg(toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time)),
              mapTransitMode(transitSegment.mode),
              duration,
              buildPath(transitSegment, transitJourneyID))

            arrivalTime = toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time))
            if(transitSegment.middle != null) {
              isMiddle = true
              legs = legs :+ BeamLeg(arrivalTime, mapLegMode(transitSegment.middle.mode), transitSegment.middle.duration,
                buildStreetPath(transitSegment.middle, arrivalTime))
              arrivalTime = arrivalTime + transitSegment.middle.duration // in case of middle arrival time would update
            }
          }

          // egress would only be present if there is some transit, so its under transit presence check
          if(itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            //start time would be the arival time of last stop and 5 second alighting
            legs = legs :+ BeamLeg(arrivalTime, mapLegMode(egress.mode), egress.duration, buildStreetPath(egress, arrivalTime))
            if(forPerson && egress.mode != LegMode.WALK) legs :+ dummyWalk(arrivalTime + egress.duration)
          }
        }

        trips = trips :+ BeamTrip(legs, mapLegMode(access.mode))
      }
    }
    trips
  }

  // TODO Need to figure out vehicle id for access, egress, middle, transit and specify as argument of StreetPath
  private def buildStreetPath(segment: StreetSegment, tripStartTime: Long): BeamPath = {
    var activeLinkIds = Vector[String]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
//      if(graphPathOutputsNeeded) {
//        activeCoords = activeCoords :+ toCoord(edge.geometry)
//      }
    }
    BeamPath(activeLinkIds, None, new StreetPathTrajectoryResolver(transportNetwork.streetLayer, tripStartTime, segment.duration))
  }

  private def buildPath(segment: TransitSegment, transitJourneyID: TransitJourneyID): BeamPath = {
    val linkIds = if (segment.middle != null) {
      segment.middle.streetEdges.asScala.map(_.edgeId.toString).toVector
    } else {
      val intStopId = transportNetwork.transitLayer.indexForStopId.get(segment.from.stopId)
      val fromStop = stopForIndex.get(intStopId)
      val toIntStopId = transportNetwork.transitLayer.indexForStopId.get(segment.to.stopId)
      val toStop = stopForIndex.get(toIntStopId)
      resolveTransitEdges(fromStop, toStop)
    }
    BeamPath(linkIds, Option(TransitStopsInfo(segment.from.stopId, segment.to.stopId)), new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer))
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
          activeCoords = activeCoords :+ toCoord(edge.getGeometry)
          activeTimes = activeTimes :+ state.getDurationSeconds.toLong
        }
      }
    }
    BeamStreetPath(activeLinkIds, activeCoords, activeTimes)
  }*/

  private def toBaseMidnightSeconds(time: ZonedDateTime): Long = {
    val baseDate = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate)
    ChronoUnit.SECONDS.between(baseDate, time)
  }

  private def toCoord(geometry: LineString): Coord = {
    new Coord(geometry.getCoordinate.x, geometry.getCoordinate.y, geometry.getCoordinate.z)
  }

  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    RefectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)
}

object R5RoutingWorker extends HasProps {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = _
  var stopForIndex: util.List[Stop] = _

  override def props(beamServices: BeamServices) = Props(classOf[R5RoutingWorker], beamServices)

  case class ProfileRequestToVehicles(originalProfile: ProfileRequest,
                                      originalProfileModeToVehicle: mutable.Map[BeamMode,mutable.Set[StreetVehicle]],
                                      walkOnlyProfiles: Vector[ProfileRequest],
                                      vehicleAsOriginProfiles: Map[ProfileRequest,StreetVehicle])
}