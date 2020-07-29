package beam.router.graphhopper

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.FuelTypePrices
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.{Modes, Router}
import beam.sim.common.GeoUtils
import com.conveyal.osmlib.{OSM, OSMEntity}
import com.conveyal.r5.transit.TransportNetwork
import com.graphhopper.config.{CHProfile, Profile}
import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ch.{CHPreparationHandler, PrepareContractionHierarchies}
import com.graphhopper.routing.util.{BikeFlagEncoder, CarFlagEncoder, EdgeFilter, EncodingManager, FootFlagEncoder}
import com.graphhopper.routing.weighting.{FastestWeighting, PriorityWeighting, TurnCostProvider}
import com.graphhopper.storage._
import com.graphhopper.util.{PMap, Parameters, PointList}
import com.graphhopper.{GHRequest, GraphHopperConfig}
import org.matsim.api.core.v01.Id

import scala.collection.JavaConverters._

class GraphHopper(
                   graphDir: String,
                   geo: GeoUtils,
                   vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
                   fuelTypePrices: FuelTypePrices
                 ) extends Router {

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  private val graphHopper = {
    val graphHopper = new com.graphhopper.GraphHopper()
    graphHopper.setGraphHopperLocation(graphDir)
    graphHopper.setProfiles(GraphHopper.profiles.asJava)
    graphHopper.getCHPreparationHandler.setCHProfiles(GraphHopper.profiles.map(p => new CHProfile(p.getName)).asJava)
    graphHopper.importOrLoad()
//    val ghConfig = new GraphHopperConfig()
//    ghConfig.putObject("graph.location", graphDir)
//    ghConfig.setProfiles(GraphHopper.profiles.asJava)
//    ghConfig.setCHProfiles(GraphHopper.profiles.map(p => new CHProfile(p.getName)).asJava)
//    graphHopper.init(ghConfig)
//    graphHopper.load(graphDir)
    graphHopper
  }

  override def calcRoute(routingRequest: RoutingRequest): RoutingResponse = {
    assert(!routingRequest.withTransit, "Can't route transit yet")
    assert(
      routingRequest.streetVehicles.size == 1,
      "Can only route unimodal trips with single available vehicle so far"
    )
    val origin = geo.utm2Wgs(routingRequest.originUTM)
    val destination = geo.utm2Wgs(routingRequest.destinationUTM)
    val streetVehicle = routingRequest.streetVehicles.head
    val request = new GHRequest(origin.getY, origin.getX, destination.getY, destination.getX)
    request.setProfile("fastest_car")
//    request.setPathDetails(Seq("edge_key", "time").asJava)
    request.setPathDetails(Seq(Parameters.Details.EDGE_ID, Parameters.Details.TIME).asJava)
    val response = graphHopper.route(request)
    val alternatives = if (response.hasErrors) {
      Seq()
    } else {
      response.getAll.asScala.map(responsePath => {
        val totalTravelTime = (responsePath.getTime / 1000).toInt
        var linkIds: IndexedSeq[Int] =
          responsePath.getPathDetails.asScala(Parameters.Details.EDGE_ID).asScala.map(pd => pd.getValue.asInstanceOf[Int]).toIndexedSeq
        var linkTravelTimes: IndexedSeq[Double] = responsePath.getPathDetails
          .asScala(Parameters.Details.TIME)
          .asScala
          .map(pd => pd.getValue.asInstanceOf[Long].toDouble / 1000.0)
          .toIndexedSeq
        if (linkIds.isEmpty) {
          // An empty path by GH's definition. But we still want it to be from a link to a link.
          val snappedPoint = graphHopper.getLocationIndex.findClosest(origin.getY, origin.getX, EdgeFilter.ALL_EDGES)
          val edgeId = snappedPoint.getClosestEdge.getEdge * 2
          linkIds = IndexedSeq(edgeId, edgeId)
          linkTravelTimes = IndexedSeq(0.0, 0.0)
        }
        val partialFirstLinkTravelTime = linkTravelTimes.head
        val beamTotalTravelTime = totalTravelTime - partialFirstLinkTravelTime.toInt
        val beamLeg = BeamLeg(
          routingRequest.departureTime,
          Modes.BeamMode.CAR,
          beamTotalTravelTime,
          BeamPath(
            linkIds,
            linkTravelTimes,
            None,
            SpaceTime(origin, routingRequest.departureTime),
            SpaceTime(destination, routingRequest.departureTime + beamTotalTravelTime),
            responsePath.getDistance
          )
        )
        EmbodiedBeamTrip(
          IndexedSeq(
            EmbodiedBeamLeg(
              beamLeg,
              streetVehicle.id,
              streetVehicle.vehicleTypeId,
              asDriver = true,
              DrivingCost.estimateDrivingCost(beamLeg, vehicleTypes(streetVehicle.vehicleTypeId), fuelTypePrices),
              unbecomeDriverOnCompletion = true
            )
          )
        )
      })
    }
    RoutingResponse(alternatives, routingRequest.requestId, Some(routingRequest), isEmbodyWithCurrentTravelTime = false)
  }

}

object GraphHopper {

  def createGraphDirectoryFromR5(transportNetwork: TransportNetwork, osm: OSM, directory: String): Unit = {
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

    val ghDirectory = new GHDirectory(directory, DAType.RAM_STORE)
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

    profiles.foreach(
      p => graphHopperStorage.getProperties.put("graph.profiles." + p.getName + ".version", p.getVersion)
    )
    graphHopperStorage.getProperties.put("prepare.ch.done", true)

    graphHopperStorage.flush()
  }

  def profiles = {
    val fastestCarProfile = new Profile("fastest_car")
    fastestCarProfile.setVehicle("car")
    fastestCarProfile.setWeighting("fastest")
    fastestCarProfile.setTurnCosts(false)
    val bestBikeProfile = new Profile("best_bike")
    bestBikeProfile.setVehicle("bike")
    bestBikeProfile.setWeighting("fastest")
    bestBikeProfile.setTurnCosts(false)
    val fastestFootProfile = new Profile("fastest_foot")
    fastestFootProfile.setVehicle("foot")
    fastestFootProfile.setWeighting("fastest")
    fastestFootProfile.setTurnCosts(false)
    List(fastestCarProfile, bestBikeProfile, fastestFootProfile)
  }

}