package beam.playground.routing

import java.io.File
import java.nio.file.{Files, Paths}
import java.util

import beam.utils.Collections.ifPresentThenForEach
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._


/**
  * Created by Zeeshan Bilal on 6/9/2017.
  */
object RouterApp extends App{
  private val LOG = LoggerFactory.getLogger(Some.getClass)
  private val GRAPH_FILE = "network.dat"
  private val OSM_FILE = "osm.mapdb"

  private var transportNetwork: TransportNetwork = null


  // Loading graph
  init(args)
  //calculate Route
  logProfileResponse(calcRoute2)


  private def convertIntToTimeFormat(timeInSeconds: Int) = { //        long longVal = timeInSeconds.longValue();
    val hours = timeInSeconds / 3600
    var remainder = timeInSeconds % 3600
    val mins = remainder / 60
    remainder = remainder % 60
    val secs = remainder

    hours + ":" + mins + ":" + secs
//    String.format("%02d:%02d:%02d", hours, mins, secs)
    //        long hours = TimeUnit.SECONDS.toHours(timeInSeconds);
    //        long remainMinute = timeInSeconds - TimeUnit.HOURS.toMinutes(hours);
    //        long remainSeconds = timeInSeconds - TimeUnit.MINUTES.toSeconds(remainMinute);
    //        String result = String.format("%02d", hours) + ":" + String.format("%02d", remainMinute)+ ":" + String.format("%02d", remainSeconds);
    //        return result;
  }




  @throws[Exception]
  private def init(parms: Array[String]) = {
    var networkDir = ""
    if (parms != null && parms.length > 0) { // first preference, command line arguments also allow to override configuration for a run.
      networkDir = parms(0)
    }
    else { //TODO: second preference, configuration file - need to itegrate with @BeamConfig after discussion with @Colin
      //last preference, if nither of the above are defined then go with some default burned option
      networkDir = Paths.get(System.getProperty("user.home"), "beam", "network").toString
    }
    loadGraph(networkDir)
  }

  @throws[Exception]
  private def loadGraph(networkDir: String) = {
    var networkFile: File = null
    var mapdbFile: File = null
    if (Files.exists(Paths.get(networkDir))) {
      val networkPath = Paths.get(networkDir, GRAPH_FILE)
      if (Files.isReadable(networkPath)) networkFile = networkPath.toFile
      val osmPath = Paths.get(networkDir, OSM_FILE)
      if (Files.isReadable(osmPath)) mapdbFile = osmPath.toFile
    }
    if (networkFile == null) {
      LOG.error("Fail to build transport network, {} not available.", GRAPH_FILE)
      false
    }
    transportNetwork = TransportNetwork.read(networkFile)
    // Optional used to get street names:
    if (mapdbFile == null) LOG.warn("OSM read action ignored, {} not available.", OSM_FILE)
    else transportNetwork.readOSM(mapdbFile)
    true
  }

  def buildRequest(isTransit: Boolean): ProfileRequest = {
    val profileRequest = new ProfileRequest
    // Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    profileRequest.fromLat = 45.547716775429045
    profileRequest.fromLon = -122.68020629882812
    profileRequest.toLat = 45.554628830194815
    profileRequest.toLon = -122.66613006591795
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    // TODO: time need to get from request
    profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00")
    if (isTransit) profileRequest.transitModes = util.EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS, TransitModes.SUBWAY, TransitModes.RAIL)
    profileRequest.accessModes = util.EnumSet.of(LegMode.WALK)
    profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    profileRequest.directModes = util.EnumSet.of(LegMode.WALK, LegMode.BICYCLE)
    profileRequest
  }

  def calcRoute: Long = {
    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    val profileRequest = buildRequest(true)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = StreetMode.WALK
    // TODO use target pruning instead of a distance limit
    streetRouter.distanceLimitMeters = 100000
    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)
    streetRouter.route()
    // Gets lowest weight state for end coordinate split
    val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
//    val streetPath = new StreetPath(lastState, transportNetwork)
//    val totalDistance = 0
//    var stateIdx = 0
//    // TODO: this can be improved since end and start vertices are the same
//    // in all the edges.
//
//    for (state <- streetPath.getStates.asScala) {
//      val edgeIdx = state.backEdge
//      if (edgeIdx != -1) {
//        val edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx)
//        LOG.info("{} - EdgeIndex [{}]", stateIdx, edgeIdx)
//        LOG.info("\t Lat/Long [{}]", edge.getGeometry)
//        LOG.info("\tweight [{}]", state.weight)
//        LOG.info("\tduration sec [{}:{}]", state.getDurationSeconds / 60, state.getDurationSeconds % 60)
//        LOG.info("\tdistance [{}]", state.distance / 1000)
//      }
//    }
//    totalDistance
    0L
  }

  def calcRoute2: ProfileResponse = {
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    // Gets a response:
    val profileResponse = pointToPointQuery.getPlan(buildRequest(false))
    profileResponse
  }

  private def logProfileResponse(profileResponse: ProfileResponse) = {
    LOG.info("{} OPTIONS returned in the profileResponse", profileResponse.getOptions.size)
    ifPresentThenForEach(profileResponse.getOptions, (option: ProfileOption) => {
      def foo(option: ProfileOption) = {
        LOG.info("*****OPTION START*****")
        LOG.info("Option start with summary: {}", option.summary)
        val stats = option.stats
        LOG.info("Average: {}", stats.avg)
        LOG.info("MIN: {}", stats.min)
        LOG.info("MAX: {}", stats.max)
        LOG.info("NUM: {}", stats.num)
        ifPresentThenForEach(option.itinerary, (iten: Itinerary) => {
          def foo(iten: Itinerary) = {
            LOG.info("\t*****ITINERARY START*****")
            LOG.info("\tTotal Distance is: {}", iten.distance)
            LOG.info("\tTotal Duration is: {}", convertIntToTimeFormat(iten.duration))
            LOG.info("\tStart Time is: {}", iten.startTime)
            LOG.info("\tEnd Time is: {}", iten.endTime)
            LOG.info("\tTotal Waiting Time is: {}", convertIntToTimeFormat(iten.waitingTime))
            LOG.info("\tTotal Transit Time is: {}", convertIntToTimeFormat(iten.transitTime))
            LOG.info("\tTotal Walk Time is: {}", convertIntToTimeFormat(iten.walkTime))
            val conn = iten.connection
            ifPresentThenForEach(conn.transit, (transit: TransitJourneyID) => {
              def foo(transit: TransitJourneyID) = {
                LOG.info("\t\t*****TRANSIT START*****")
                LOG.info("\t\tTransit Time: {}", convertIntToTimeFormat(transit.time))
                LOG.info("\t\tTransit Pattern: {}", transit.pattern)
                LOG.info("\t\t*****TRANSIT END*****")
              }

              foo(transit)
            })
            LOG.info("P2P Connection Access: {}", conn.access)
            LOG.info("P2P Connection Egress: {}", conn.egress)
            LOG.info("\t*****ITINERARY END*****")
          }

          foo(iten)
        })
        LOG.info("\t*****TRANSIT SEGMENTS START*****")
        logTransitSegment(option.transit)
        LOG.info("\t*****TRANSIT SEGMENTS END*****")
        LOG.info("\t*****ACCESS START*****")
        logStreetSegment(option.access)
        LOG.info("\t*****ACCESS END*****")
        LOG.info("\t*****EGRESS START*****")
        logStreetSegment(option.egress)
        LOG.info("\t*****EGRESS END*****")
        LOG.info("*****OPTION END*****")
      }

      foo(option)
    })
    LOG.info("{} PATTERNS returned in the profileResponse", profileResponse.getPatterns.size)
  }

  def logStreetSegment(sSegments: util.List[StreetSegment]): Unit = {
    ifPresentThenForEach(sSegments, (segment: StreetSegment) => {
      def foo(segment: StreetSegment) = {
        LOG.info("\t*****SEGMENT START*****")
        LOG.info("\tAccess MODE: {}", segment.mode)
        LOG.info("\tAccess Distance: {}", segment.distance)
        LOG.info("\tTotal Edge Distance: {}", segment.streetEdges.parallelStream.mapToInt((edge: StreetEdgeInfo) => edge.distance).sum)
        LOG.info("\tAccess Elevation: {}", segment.elevation)
        LOG.info("\tAccess Duration: {}", convertIntToTimeFormat(segment.duration))
        val geom = segment.geometry
        LOG.info("\tSegment Area: {}", geom.getArea)
        LOG.info("\tCoordinates: {}", geom.getCoordinate)
        LOG.info("\tBoundary Dimensions are: {}", geom.getBoundaryDimension)
        LOG.info("\tSegment Starting Point: {}", geom.getStartPoint)
        LOG.info("\tEnd Point is: {}", geom.getEndPoint)
        LOG.info("\tGeometry Dimensions: {}", geom.getDimension)
        LOG.info("\tGeometry Type: {}", geom.getGeometryType)
        LOG.info("\tSegment Length: {}", geom.getLength)
        LOG.info("\tSegment Num Points: {}", geom.getNumPoints)
        val coordinate = geom.getCoordinate
        LOG.info("\tCoordinate-X: {}", coordinate.x)
        LOG.info("\tCoordinate-Y: {}", coordinate.y)
        LOG.info("\tCoordinate-Z: {}", coordinate.z)
        LOG.info("\tTotal Edges are: {}", segment.streetEdges.size)
        ifPresentThenForEach(segment.streetEdges, (edge: StreetEdgeInfo) => {
          def foo(edge: StreetEdgeInfo) = {
            LOG.info("\t\t*****EDGE START*****")
            LOG.info("\t\tStreet Name: {}", edge.streetName)
            LOG.info("\t\tMode: {}", edge.mode)
            LOG.info("\t\tDistance: {}", edge.distance)
            LOG.info("\t\tEdge Id: {}", edge.edgeId)
            LOG.info("\t\t*****Edge END*****")
          }

          foo(edge)
        })
        LOG.info("\t*****SEGMENT END*****")
      }

      foo(segment)
    })
  }

  def logTransitSegment(tSegments: util.List[TransitSegment]): Unit = {
    ifPresentThenForEach(tSegments, (segment: TransitSegment) => {
      def foo(segment: TransitSegment) = {
        LOG.info("\t*****SEGMENT START*****")
        LOG.info("\tTransit MODE: {}", segment.mode)
        LOG.info("\tFrom Name: {}", segment.fromName)
        LOG.info("\tTo Name: {}", segment.toName)
        LOG.info("\t*****From Stop*****")
        logStop(segment.from)
        LOG.info("\t*****To Stop*****")
        logStop(segment.to)
        LOG.info("\tTotal Routes are: {}", segment.getRoutes.size)
        ifPresentThenForEach(segment.getRoutes, (route: Route) => {
          def foo(route: Route) = {
            LOG.info("\t\t*****ROUTE START*****")
            LOG.info("\t\tRoute Long Name: {}", route.longName)
            LOG.info("\t\tRoute Short Name: {}", route.shortName)
            LOG.info("\t\tMode: {}", route.mode)
            LOG.info("\t\tAgency Name: {}", route.agencyName)
            LOG.info("\t\tRoute Id: {}", route.id)
            LOG.info("\t\tRoute Idx: {}", route.routeIdx)
            LOG.info("\t\tDesc: {}", route.description)
            LOG.info("\t\t*****ROUTE END*****")
          }

          foo(route)
        })

        LOG.info("\tTotal Segment Patterns are: {}", segment.segmentPatterns.size)
        ifPresentThenForEach(segment.segmentPatterns, (pattern: SegmentPattern) => {
          def foo(pattern: SegmentPattern) = {
            LOG.info("\t\t*****Segment Pattern START*****")
            LOG.info("\t\tPattern Id: {}", pattern.patternId)
            LOG.info("\t\tPattern Index: {}", pattern.patternIdx)
            LOG.info("\t\tRoute Index: {}", pattern.routeIndex)
            LOG.info("\t\tFrom Index: {}", pattern.fromIndex)
            LOG.info("\t\tFrom Arrival Time: {}", pattern.fromArrivalTime)
            LOG.info("\t\tFrom Departure Time: {}", pattern.fromDepartureTime)
            LOG.info("\t\tTo Index: {}", pattern.toIndex)
            LOG.info("\t\tTo Arrival Time: {}", pattern.toArrivalTime)
            LOG.info("\t\tTo Departure Time: {}", pattern.toDepartureTime)
            LOG.info("\t\tReal Time: {}", pattern.realTime)
            LOG.info("\t\tTrip Count: {}", pattern.nTrips)
            LOG.info("\t\t*****Segment Pattern END*****")
          }

          foo(pattern)
        })

        LOG.info("\t*****SEGMENT END*****")
      }

      foo(segment)
    })
  }

  def logStop(stop: Stop): Unit = {
    LOG.info("\tStop ZoneId: {}", stop.zoneId)
    LOG.info("\tStop Id : {}", stop.stopId)
    LOG.info("\tSStop Code: {}", stop.code)
    LOG.info("\tStop Mode: {}", stop.mode)
    LOG.info("\tStop Name: {}", stop.name)
    LOG.info("\tLat: {}", stop.lat)
    LOG.info("\tLon: {}", stop.lon)
    LOG.info("\tWheelchair Boarding: {}", stop.wheelchairBoarding)
  }
}
