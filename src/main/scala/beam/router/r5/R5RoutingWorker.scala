package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor.Props
import beam.agentsim.agents.PersonAgent
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingResponse
import beam.router.Modes.BeamMode.WALK
import beam.router.Modes._
import beam.router.RoutingModel.BeamLeg._
import beam.router.BeamRouter.{Location, RoutingRequest, TripInfo, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.router.RoutingWorker
import beam.router.RoutingWorker.HasProps
import beam.router.r5.R5RoutingWorker.{GRAPH_FILE, transportNetwork}
import beam.sim.BeamServices
import beam.utils.GeoUtils
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.ProfileRequest
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.LineString
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.facilities.Facility
import org.opentripplanner.routing.vertextype.TransitStop

import scala.collection.JavaConverters._

class R5RoutingWorker(val beamServices: BeamServices) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
//  val localDateAsString: String = "2016-10-17"
//  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond
  //TODO make this actually come from beamConfig
//  val graphPathOutputsNeeded = beamServices.beamConfig.beam.outputs.writeGraphPathTraversals
  val graphPathOutputsNeeded = false

  override def init: Unit = loadMap

  def loadMap = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir();
    }
    val networkFilePath = Paths.get(networkDir, GRAPH_FILE)
    val networkFile : File = networkFilePath.toFile
    if (exists(networkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${networkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(networkFile)
    } else {
      log.debug(s"Network file [${networkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating network from: ${networkDirPath.toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile)
      transportNetwork.write(networkFile);
    }
  }

  override def calcRoute(requestId: Id[RoutingRequest], params: TripInfo, person: Person): RoutingResponse = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val plan: ProfileResponse = pointToPointQuery.getPlan(buildRequest(params.from, params.destination, params.departureTime, params.accessMode))
    val alternatives = buildResponse(plan)
    RoutingResponse(requestId, alternatives)
  }

  protected def buildRequest(fromFacility: Location, toFacility: Location, departureTime: BeamTime, modes: Vector[BeamMode]) : ProfileRequest = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromPosTransformed = GeoUtils.transform.Utm2Wgs(fromFacility)
    val toPosTransformed = GeoUtils.transform.Utm2Wgs(toFacility)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4

    val time = departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate

    val legModes : Vector[LegMode] = modes.filter(isR5LegMode).map(_.r5Mode.get.left.get)
    profileRequest.directModes = util.EnumSet.copyOf( legModes.asJavaCollection )

    val isTransit = legModes.size < modes.size

    if(isTransit){
      val transitModes : Vector[TransitModes] = modes.filter(isR5TransitMode).map(_.r5Mode.get.right.get)
      profileRequest.transitModes = util.EnumSet.copyOf(transitModes.asJavaCollection)
      profileRequest.accessModes = profileRequest.directModes
      profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    }

    profileRequest
  }

  def buildResponse(plan: ProfileResponse) = {

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
        legs = legs :+ BeamLeg(tripStartTime, mapLegMode(access.mode), access.duration, buildStreetPath(access))

        //add a Dummy BeamLeg to the beginning and end of that trip BeamTrip using the dummyWalk
        if(access.mode != LegMode.WALK) {
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
            //for first iteration in an itinerary, arivalTime would be null, add Waiting and boarding legs
            if(arrivalTime == Long.MinValue || isMiddle) {
              val accessEndTime = if(!isMiddle) tripStartTime + access.duration else arrivalTime
              isMiddle = false
              // possible wait time is difference in access time end time and departure time, additional five seconds for boarding
              val possibleWaitTime = toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time)) - accessEndTime - boardingTime
              if(possibleWaitTime > 0) {
                // Waiting and default 5 sec Boarding logs
                legs = legs :+ waiting(accessEndTime, possibleWaitTime) :+ boarding(accessEndTime + possibleWaitTime, boardingTime)
              } else {
                // in case of negative possibleWaitTime boarding duration would be less then 5 sec and dummy wait to make legs consistent
                legs = legs :+ waiting(accessEndTime, 0) :+ boarding(accessEndTime, boardingTime + possibleWaitTime)
              }
            }

            val toStopId: String = transportNetwork.transitLayer.stopIdForIndex.get(segmentPattern.toIndex)
            // when this is the last SegmentPattern, we should use the toArrivalTime instead of the toDepartureTime
            val duration = ( if(option.transit.indexOf(transitSegment) < option.transit.size() - 1)
                              segmentPattern.toDepartureTime.get(transitJourneyID.time).toEpochSecond
                            else
                              segmentPattern.toArrivalTime.get(transitJourneyID.time).toEpochSecond ) -
              segmentPattern.fromDepartureTime.get(transitJourneyID.time).toEpochSecond

            legs = legs :+ new BeamLeg(toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time)),
              mapTransitMode(transitSegment.mode),
              duration,
//              Right(buildPath(transitSegment, transitJourneyID)))
              buildPath(transitSegment, transitJourneyID))

            arrivalTime = toBaseMidnightSeconds(segmentPattern.toArrivalTime.get(transitJourneyID.time))
            if(transitSegment.middle != null) {
              isMiddle = true
              legs = legs :+ alighting(arrivalTime, alightingTime) :+ // Alighting leg from last transit
                BeamLeg(arrivalTime + alightingTime, mapLegMode(transitSegment.middle.mode), transitSegment.middle.duration, buildStreetPath(transitSegment.middle))
                arrivalTime = arrivalTime + alightingTime + transitSegment.middle.duration // in case of middle arrival time would update
            }
          }
          // Alighting leg with 5 sec duration
          legs = legs :+ alighting(arrivalTime, alightingTime)

          // egress would only be present if there is some transit, so its under transit presence check
          if(itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            //start time would be the arival time of last stop and 5 second alighting
            legs = legs :+ BeamLeg(arrivalTime + alightingTime, mapLegMode(egress.mode), egress.duration, buildStreetPath(egress))
            if(egress.mode != WALK) legs :+ dummyWalk(arrivalTime + alightingTime + egress.duration)
          }
        }

        trips = trips :+ BeamTrip(legs)
      }
    }
    trips
  }

  private def boardingTime = {
    5
  }

  private def alightingTime = {
    5
  }
  // TODO Need to figure out vehicle id for access, egress, middle, transit and specify as argument of StreetPath
  private def buildStreetPath(segment: StreetSegment): BeamStreetPath = {
    var activeLinkIds = Vector[String]()
    var spaceTime = Vector[SpaceTime]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
//      if(graphPathOutputsNeeded) {
//        activeCoords = activeCoords :+ toCoord(edge.geometry)
//      }
      //TODO: time need to be extrected and provided as last argument of SpaceTime
      spaceTime = spaceTime :+ SpaceTime(edge.geometry.getCoordinate.x, edge.geometry.getCoordinate.y, -1)
    }

    BeamStreetPath(activeLinkIds, trajectory = Some(spaceTime))
  }

  def createStopId(stopId: String): Id[TransitStop] = {
    Id.create(stopId, classOf[TransitStop])
  }

  private def buildPath(segment: TransitSegment, transitJourneyID: TransitJourneyID): BeamTransitSegment = {
    val segmentPattern: SegmentPattern = segment.segmentPatterns.get(transitJourneyID.pattern)
    val beamVehicleId = Id.createVehicleId(segmentPattern.tripIds.get(transitJourneyID.time))
    val departureTime = toBaseMidnightSeconds(segmentPattern.fromDepartureTime.get(transitJourneyID.time))

    BeamTransitSegment(beamVehicleId, createStopId(segment.from.stopId), createStopId(segment.to.stopId), departureTime)
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
}

object R5RoutingWorker extends HasProps {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = null

  override def props(beamServices: BeamServices) = Props(classOf[R5RoutingWorker], beamServices)
}