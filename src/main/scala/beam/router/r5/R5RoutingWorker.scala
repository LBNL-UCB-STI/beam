package beam.router.r5

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.router.BeamRouter.{RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode.WALK
import beam.router.Modes.{BeamMode, _}
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel._
import beam.router.RoutingWorker
import beam.router.RoutingWorker.HasProps
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.r5.NetworkCoordinator.{beamPathBuilder, _}
import beam.router.r5.R5RoutingWorker.{ProfileRequestToVehicles, TripsWithFares}
import beam.sim.BeamServices
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.common.JsonUtilities
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.ProfileRequest
import org.matsim.api.core.v01.Id

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