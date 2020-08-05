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
import com.graphhopper.GHRequest
import com.graphhopper.config.{CHProfile, Profile}
import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ch.{CHPreparationHandler, PrepareContractionHierarchies}
import com.graphhopper.routing.util._
import com.graphhopper.routing.weighting.{FastestWeighting, PriorityWeighting, TurnCostProvider}
import com.graphhopper.storage._
import com.graphhopper.util.{PMap, Parameters, PointList}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.router.util.TravelTime

import scala.collection.JavaConverters._

class GraphHopperWrapper(
                          carRouter: String,
                          noOfTimeBins: Int,
                          timeBinSize: Int,
                          graphDir: String,
                          geo: GeoUtils,
                          vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
                          fuelTypePrices: FuelTypePrices,
                          links: Seq[Link],
                          travelTime: Option[TravelTime] = None,
                        ) extends Router {

  val id2Link = links.map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord)).toMap

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  private val graphHopper = {
    val profiles = GraphHopperWrapper.getProfiles(carRouter, noOfTimeBins)
    val graphHopper = new BeamGraphHopper(links, travelTime)
    graphHopper.setPathDetailsBuilderFactory(new BeamPathDetailsBuilderFactory())
    graphHopper.setGraphHopperLocation(graphDir)
    graphHopper.setProfiles(profiles.asJava)
    graphHopper.getCHPreparationHandler.setCHProfiles(profiles.map(p => new CHProfile(p.getName)).asJava)
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
    if (carRouter == "quasiDynamicGH") {
      request.setProfile(s"beam_car_hour_${Math.floor(routingRequest.departureTime / timeBinSize).toInt}")
    } else {
      request.setProfile("fastest_car")
    }

    // set this somehow to remove douglas peuker
    //    request.setPointHints(Routing.WAY_POINT_MAX_DISTANCE,0)
    request.setPathDetails(Seq(Parameters.Details.EDGE_ID, Parameters.Details.TIME).asJava)
    val response = graphHopper.route(request)
    val alternatives = if (response.hasErrors) {
      Seq()
    } else {
      response.getAll.asScala.map(responsePath => {
        var linkIds = IndexedSeq.empty[Int]
        val totalTravelTime = (responsePath.getTime / 1000).toInt
        val ghLinkIds: IndexedSeq[Int] =
          responsePath.getPathDetails.asScala(Parameters.Details.EDGE_ID).asScala.map(pd => pd.getValue.asInstanceOf[Int]).toIndexedSeq
        val ghOriginalLinkIds: IndexedSeq[Int] =
          responsePath.getPathDetails.asScala("original_edge_id").asScala.map(pd => pd.getValue.asInstanceOf[Int]).toIndexedSeq
        println(ghOriginalLinkIds)
        var linkTravelTimes: IndexedSeq[Double] = responsePath.getPathDetails
          .asScala(Parameters.Details.TIME)
          .asScala
          .map(pd => pd.getValue.asInstanceOf[Long].toDouble / 1000.0)
          .toIndexedSeq
        if (ghLinkIds.isEmpty) {
          // An empty path by GH's definition. But we still want it to be from a link to a link.
          val snappedPoint = graphHopper.getLocationIndex.findClosest(origin.getY, origin.getX, EdgeFilter.ALL_EDGES)
          val edgeId = snappedPoint.getClosestEdge.getEdge * 2
          linkIds = IndexedSeq(edgeId, edgeId)
          linkTravelTimes = IndexedSeq(0.0, 0.0)
        } else {
          linkIds = ghLinkIds.sliding(2).map { list =>
            val (ghId1, ghId2) = (list.head, list.last)
            val leftStraight = id2Link(ghId1 * 2)

            val rightStraight = id2Link(ghId2 * 2)
            val rightReverse = id2Link(ghId2 * 2 + 1)
            if (leftStraight._2 == rightStraight._1 || leftStraight._2 == rightReverse._1) {
              ghId1 * 2
            } else ghId1 * 2 + 1
          }.toIndexedSeq

          linkIds = linkIds :+ (if (
            id2Link(linkIds.last)._2 == id2Link(ghLinkIds.last * 2)._1
          ) ghLinkIds.last * 2 else ghLinkIds.last * 2 + 1)
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

object GraphHopperWrapper {

  def createGraphDirectoryFromR5(
                                  carRouter: String,
                                  noOfTimeBins: Int,
                                  transportNetwork: TransportNetwork,
                                  osm: OSM, directory: String,
                                  links: Seq[Link],
                                  travelTime: Option[TravelTime]
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

    //    val fastestCar = new FastestWeighting(carFlagEncoder)
    val carCHs = if (carRouter == "quasiDynamicGH") {
      (0 until noOfTimeBins).map { hour =>
        val weights = travelTime.map { times =>
          links.map(l =>
            l.getId.toString.toLong -> times.getLinkTravelTime(l, 0, null, null)).toMap
        }.getOrElse(Map.empty)

        CHConfig.nodeBased(s"${BeamGraphHopper.profilePrefix}$hour",
          new BeamWeighting(carFlagEncoder, TurnCostProvider.NO_TURN_COST_PROVIDER, weights))
      }
    } else {
      List(CHConfig.nodeBased("fastest_car", new FastestWeighting(carFlagEncoder)))
    }

    val best_bike = CHConfig.nodeBased("best_bike",
      new PriorityWeighting(bikeFlagEncoder, new PMap, TurnCostProvider.NO_TURN_COST_PROVIDER))
    val fastest_foot = CHConfig.nodeBased("fastest_foot",
      new FastestWeighting(footFlagEncoder))

    val ghDirectory = new GHDirectory(directory, DAType.RAM_STORE)
    val graphHopperStorage = new GraphHopperStorage(ghDirectory, encodingManager, false)
    carCHs.foreach(graphHopperStorage.addCHGraph)
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
    carCHs.foreach { ch =>
      handler.addCHConfig(ch)
      handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, ch))
    }
    handler.addCHConfig(best_bike)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, best_bike))
    handler.addCHConfig(fastest_foot)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, fastest_foot))
    handler.setPreparationThreads(3)
    handler.prepare(graphHopperStorage.getProperties, false)

    getProfiles(carRouter, noOfTimeBins).foreach(
      p => graphHopperStorage.getProperties.put("graph.profiles." + p.getName + ".version", p.getVersion)
    )
    graphHopperStorage.getProperties.put("prepare.ch.done", true)

    graphHopperStorage.flush()
  }

  def getProfiles(carRouter: String, noOfTimeBins: Int) = {
    val carProfiles = if (carRouter == "quasiDynamicGH") {
      (0 until noOfTimeBins).map { hour =>
        val profile = new Profile(s"beam_car_hour_$hour")
        profile.setVehicle("car")
        profile.setWeighting("beam")
        profile.setTurnCosts(false)
        profile
      }.toList
    } else {
      val fastestCarProfile = new Profile("fastest_car")
      fastestCarProfile.setVehicle("car")
      fastestCarProfile.setWeighting("fastest")
      fastestCarProfile.setTurnCosts(false)
      List(fastestCarProfile)
    }
    val bestBikeProfile = new Profile("best_bike")
    bestBikeProfile.setVehicle("bike")
    bestBikeProfile.setWeighting("fastest")
    bestBikeProfile.setTurnCosts(false)
    val fastestFootProfile = new Profile("fastest_foot")
    fastestFootProfile.setVehicle("foot")
    fastestFootProfile.setWeighting("fastest")
    fastestFootProfile.setTurnCosts(false)
    carProfiles ++ List(bestBikeProfile, fastestFootProfile)
  }
}