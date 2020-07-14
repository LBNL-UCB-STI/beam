package beam.router.graphhopper

//import java.io.File

import beam.utils.ProfilingUtils
import com.conveyal.osmlib.{OSM, OSMEntity}
import com.conveyal.r5.transit.TransportNetwork
import com.graphhopper.config.CHProfile
import com.graphhopper.{GHRequest, GHResponse, GraphHopper}
//import com.graphhopper.reader.dem.MultiSourceElevationProvider
import com.graphhopper.config.Profile
import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ch.{CHPreparationHandler, PrepareContractionHierarchies}
import com.graphhopper.routing.util.{BikeFlagEncoder, CarFlagEncoder, EncodingManager, FootFlagEncoder}
import com.graphhopper.routing.weighting.{FastestWeighting, PriorityWeighting, TurnCostProvider}
import com.graphhopper.storage.{CHConfig, DAType, GHDirectory, GraphHopperStorage}
import com.graphhopper.util.{PMap, PointList}
import com.graphhopper.util.shapes.GHPoint
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

import GraphHopperRouteResolver._

class GraphHopperRouteResolver(val ghLocation: String) extends LazyLogging {
  //private val gh: GraphHopper = {
  //  ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
  //    val tempGh = new GraphHopper()
  //    val elevationTempFolder = new File(ghLocation + "/elevation_provider")
  //    if (!elevationTempFolder.exists()) {
  //      elevationTempFolder.mkdir()
  //      logger.info(s"elevationTempFolder does not exist, created it on path: ${elevationTempFolder.getAbsolutePath}")
  //    }
  //    tempGh.setElevationProvider(new MultiSourceElevationProvider(elevationTempFolder.getAbsolutePath))
  //    tempGh.setGraphHopperLocation(ghLocation)
  //    tempGh.importOrLoad()
  //    tempGh
  //  }
  //}

  private val gh: GraphHopper = {
    ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
      val tempGh = new GraphHopper()
      tempGh.setGraphHopperLocation(ghLocation)
      tempGh.setProfiles(fastestCarProfile)
      tempGh.getCHPreparationHandler.setCHProfiles(new CHProfile(fastestCarProfile.getName))
      tempGh.importOrLoad()
      tempGh
    }
  }

  def route(originWGS: Coord, destWGS: Coord): GHResponse = {
    val request: GHRequest =
      new GHRequest(
        new GHPoint(originWGS.getY, originWGS.getX),
        new GHPoint(destWGS.getY, destWGS.getX)
      )
    request
      .setProfile(fastestCarProfile.getName)
      .setAlgorithm("")
      .setLocale("en")
      .setPointHints(java.util.Collections.emptyList())
      .getHints
      .putObject("calc_points", true)
      .putObject("instructions", true)
      .putObject("way_point_max_distance", 1)
    gh.route(request)
  }
}

object GraphHopperRouteResolver {

  val fastestCarProfile: Profile = {
    val profile = new Profile("fastest_car")
    profile.setVehicle("car")
    profile.setWeighting("fastest")
    profile.setTurnCosts(false)
  }

  def createGHLocationFromR5(
    transportNetwork: TransportNetwork,
    osm: OSM,
    ghLocation: String
  ): Unit = {
    val carFlagEncoderParams = new PMap
    carFlagEncoderParams.putObject("turn_costs", false)
    val carFlagEncoder = new CarFlagEncoder(carFlagEncoderParams)
    val bikeFlagEncoder = new BikeFlagEncoder
    val footFlagEncoder = new FootFlagEncoder
    val emBuilder: EncodingManager.Builder = new EncodingManager.Builder
    emBuilder.add(carFlagEncoder)
    emBuilder.add(bikeFlagEncoder)
    emBuilder.add(footFlagEncoder)
    val encodingManager = emBuilder.build
    val fastestCar = new FastestWeighting(carFlagEncoder)
    val fastestFoot = new FastestWeighting(footFlagEncoder)
    val bestBike = new PriorityWeighting(bikeFlagEncoder, new PMap, TurnCostProvider.NO_TURN_COST_PROVIDER)
    val fastest_car = CHConfig.nodeBased("fastest_car", fastestCar)
    val best_bike = CHConfig.nodeBased("best_bike", bestBike)
    val fastest_foot = CHConfig.nodeBased("fastest_foot", fastestFoot)
    val ghDirectory = new GHDirectory(ghLocation, DAType.RAM_STORE)
    val graphHopperStorage = new GraphHopperStorage(ghDirectory, encodingManager, false)
    graphHopperStorage.addCHGraph(fastest_car)
    graphHopperStorage.addCHGraph(best_bike)
    graphHopperStorage.addCHGraph(fastest_foot)
    graphHopperStorage.create(1000)
    val vertex = transportNetwork.streetLayer.vertexStore.getCursor
    for (v <- 0 until transportNetwork.streetLayer.vertexStore.getVertexCount) {
      vertex.seek(v)
      graphHopperStorage.getNodeAccess.setNode(v, vertex.getLat, vertex.getLon)
    }
    val forwardEdge = transportNetwork.streetLayer.edgeStore.getCursor
    val backwardEdge = transportNetwork.streetLayer.edgeStore.getCursor
    val relationFlags = encodingManager.createRelationFlags
    for (e <- 0 until transportNetwork.streetLayer.edgeStore.nEdges by 2) {
      forwardEdge.seek(e)
      backwardEdge.seek(e + 1)
      val ghEdge = graphHopperStorage.edge(forwardEdge.getFromVertex, forwardEdge.getToVertex)
      val way = new ReaderWay(forwardEdge.getOSMID)
      val acceptWay = new EncodingManager.AcceptWay
      val osmWay = osm.ways.get(forwardEdge.getOSMID)
      if (osmWay != null) {
        osmWay.tags.forEach((tag: OSMEntity.Tag) => way.setTag(tag.key, tag.value))
      }
      encodingManager.acceptWay(way, acceptWay)
      ghEdge.setDistance(forwardEdge.getLengthM)
      ghEdge.setFlags(encodingManager.handleWayTags(way, acceptWay, relationFlags))
      encodingManager.applyWayTags(way, ghEdge)
      val pl = new PointList
      val coordinates = forwardEdge.getGeometry.getCoordinates
      for (coordinate <- coordinates.slice(1, coordinates.size - 1)) {
        pl.add(coordinate.y, coordinate.x)
      }
      ghEdge.setWayGeometry(pl)
    }
    graphHopperStorage.freeze()
    val handler = new CHPreparationHandler
    handler.addCHConfig(fastest_car)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, fastest_car))
    handler.addCHConfig(best_bike)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, best_bike))
    handler.addCHConfig(fastest_foot)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, fastest_foot))
    handler.setPreparationThreads(3)
    handler.prepare(graphHopperStorage.getProperties, false)
    graphHopperStorage.getProperties.put(
      "graph.profiles." + fastestCarProfile.getName + ".version",
      fastestCarProfile.getVersion
    )
    graphHopperStorage.getProperties.put("prepare.ch.done", true)
    graphHopperStorage.flush()
  }
}
