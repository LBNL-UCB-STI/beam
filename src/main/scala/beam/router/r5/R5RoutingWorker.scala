package beam.router.r5

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import beam.router.BeamRouter.{RoutingRequest, RoutingRequestTripInfo, RoutingResponse}
import beam.router.Modes.BeamMode.WALK
import beam.router.Modes.{BeamMode, _}
import beam.router.RoutingModel.BeamLeg._
import beam.router.RoutingModel.{EmbodiedBeamTrip, _}
import beam.router.{Modes, RoutingWorker}
import beam.router.RoutingWorker.HasProps
import beam.router.gtfs.FareCalculator
import beam.router.gtfs.FareCalculator._
import beam.router.r5.NetworkCoordinator.{beamPathBuilder, _}
import beam.router.r5.R5RoutingWorker.TripWithFares
import beam.sim.BeamServices
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.common.JsonUtilities
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.ProfileRequest
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
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
    val maxStreetTime = 2 * 60

    def getPlanFromR5(from: Coord, to: Coord, time: WindowTime, directMode: LegMode, accessMode: LegMode, transitModes: Vector[TransitModes], egressMode: LegMode) = {
      val pointToPointQuery = new PointToPointQuery(transportNetwork)
      val profileRequest = new ProfileRequest()
      profileRequest.fromLon = from.getX
      profileRequest.fromLat = from.getY
      profileRequest.toLon = to.getX
      profileRequest.toLat = to.getY
      profileRequest.maxWalkTime = 3 * 60
      profileRequest.maxCarTime = 4 * 60
      profileRequest.maxBikeTime = 4 * 60
      profileRequest.streetTime = maxStreetTime
      profileRequest.maxTripDurationMinutes = 4 * 60
      profileRequest.wheelchair = false
      profileRequest.bikeTrafficStress = 4
      profileRequest.zoneId = transportNetwork.getTimeZone
      profileRequest.fromTime = time.fromTime
      profileRequest.toTime = time.toTime
      profileRequest.date = beamServices.dates.localBaseDate
      profileRequest.directModes = util.EnumSet.of(directMode)
      if (transitModes.nonEmpty) {
        profileRequest.transitModes = util.EnumSet.copyOf(transitModes.asJavaCollection)
        profileRequest.accessModes = util.EnumSet.of(accessMode)
        profileRequest.egressModes = util.EnumSet.of(egressMode)
      }
      pointToPointQuery.getPlan(profileRequest)
    }

    // Route from all street vehicles (including body, if available) to destination.
    // TODO: Route from origin to street vehicle.
    val embodiedTrips = routingRequestTripInfo.streetVehicles.flatMap(vehicle => {
      if (beamServices.geo.distInMeters(vehicle.location.loc, routingRequestTripInfo.origin) > distanceThresholdToIgnoreWalking) {
        log.warning("Vehicle {} for agent {} out of range, and multi-stage routing not implemented yet. Agent is effectively teleporting.", vehicle.id, routingRequestTripInfo.personId)
      }

      val from = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,beamServices.geo.utm2Wgs(vehicle.location.loc),10E3)
      val to = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,beamServices.geo.utm2Wgs(routingRequestTripInfo.destination),10E3)
      val directMode = vehicle.mode.r5Mode.get.left.get
      val accessMode = vehicle.mode.r5Mode.get.left.get
      val egressMode = LegMode.WALK
      val time = routingRequestTripInfo.departureTime match {
        case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5.departureWindow)
        case time: WindowTime => time
      }
      val transitModes: Vector[TransitModes] = routingRequestTripInfo.transitModes.map(_.r5Mode.get.right.get)
      val profileResponse: ProfileResponse = getPlanFromR5(from, to, time, directMode, accessMode, transitModes, egressMode)
      val isRouteForPerson = routingRequestTripInfo.streetVehicles.exists(_.mode == WALK)
      val tripsWithFares = extractTripsAndCalculateFares(profileResponse, isRouteForPerson)
      tripsWithFares.map(tripWithFares => {
        val embodiedLegs: Vector[EmbodiedBeamLeg] = for((beamLeg, index) <- tripWithFares.trip.legs.zipWithIndex) yield {
          val cost = tripWithFares.legFares.getOrElse(index, 0.0) // FIXME this value is never used.
          if (Modes.isR5TransitMode(beamLeg.mode)) {
            if(beamServices.transitVehiclesByBeamLeg.contains(beamLeg)) {
              EmbodiedBeamLeg(beamLeg, beamServices.transitVehiclesByBeamLeg(beamLeg), false, None, 0.0, false)
            }else{
              //FIXME
              log.error("Router swallowed a transit leg because it couldn't find something.")
              EmbodiedBeamLeg.empty
            }
          } else {
            val unbecomeDriverAtComplete = Modes.isR5LegMode(beamLeg.mode) && (beamLeg.mode != WALK || beamLeg == tripWithFares.trip.legs.last)
            if (beamLeg.mode == WALK) {
              val body = routingRequestTripInfo.streetVehicles.find(_.mode == WALK).get
              EmbodiedBeamLeg(beamLeg, body.id, body.asDriver, None, 0.0, unbecomeDriverAtComplete)
            } else {
              EmbodiedBeamLeg(beamLeg, vehicle.id, vehicle.asDriver, None, 0.0, unbecomeDriverAtComplete)
            }
          }
        }
        EmbodiedBeamTrip(embodiedLegs)
      })
    })

    if(!embodiedTrips.exists(_.tripClassifier == WALK)) {
      log.warning("No walk route found. {}",
        JsonUtilities.objectMapper.writeValueAsString(routingRequestTripInfo))
      val maybeBody = routingRequestTripInfo.streetVehicles.find(_.mode == WALK)
      if (maybeBody.isDefined) {
        log.warning("Adding dummy walk route with maximum street time.")
        val dummyTrip = EmbodiedBeamTrip(
          Vector(
              EmbodiedBeamLeg(BeamLeg(routingRequestTripInfo.departureTime.atTime, WALK, maxStreetTime * 60))
          )
        )
        RoutingResponse(requestId, embodiedTrips :+ dummyTrip)
      } else {
        log.warning("Not adding a dummy walk route since agent has no body.")
        RoutingResponse(requestId, embodiedTrips)
      }
    } else {
      RoutingResponse(requestId, embodiedTrips)
    }
  }

  def extractTripsAndCalculateFares(plan: ProfileResponse, forPerson: Boolean): Seq[TripWithFares] = {
    plan.options.asScala.flatMap(option => {
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
      option.itinerary.asScala.map(itinerary => {
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
        TripWithFares(BeamTrip(legs, mapLegMode(access.mode)), legFares)

      })
    })
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

  case class TripWithFares(trip: BeamTrip, legFares: Map[Int, Double])

}