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
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

class GraphHopperWrapper(
  carRouter: String,
  graphDir: String,
  geo: GeoUtils,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  fuelTypePrices: FuelTypePrices,
  wayId2TravelTime: Map[Long, Double],
  id2Link: Map[Int, (Coord, Coord)]
) extends Router {
  private val graphHopper = {
    val profiles = GraphHopperWrapper.getProfiles(carRouter)
    val graphHopper = new BeamGraphHopper(wayId2TravelTime)
    graphHopper.setGraphHopperLocation(graphDir)
    graphHopper.setProfiles(profiles.asJava)
    graphHopper.getCHPreparationHandler.setCHProfiles(profiles.map(p => new CHProfile(p.getName)).asJava)
    graphHopper.importOrLoad()
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
      request.setProfile("beam_car")
    } else {
      request.setProfile("fastest_car")
    }

    request.setPathDetails(Seq(Parameters.Details.EDGE_ID, Parameters.Details.TIME).asJava)
    val response = graphHopper.route(request)
    val alternatives = if (response.hasErrors) {
      Seq()
    } else {
      response.getAll.asScala.map(responsePath => {
        var linkIds = IndexedSeq.empty[Int]
        var totalTravelTime = (responsePath.getTime / 1000).toInt
        val ghLinkIds: IndexedSeq[Int] =
          responsePath.getPathDetails
            .asScala(Parameters.Details.EDGE_ID)
            .asScala
            .map(pd => pd.getValue.asInstanceOf[Int])
            .toIndexedSeq

        val allLinkTravelTimes = responsePath.getPathDetails
          .asScala(Parameters.Details.TIME)
          .asScala
          .map(pd => pd.getValue.asInstanceOf[Long].toDouble / 1000.0)
          .toIndexedSeq

        var linkTravelTimes: IndexedSeq[Double] = allLinkTravelTimes
        // TODO ask why GH is producing negative travel time
//          .map { x =>
//            require(x > 0, "GOING BACK IN TIME")
//            x
//          }
        //FIXME BECAUSE OF ADDITIONAL ZEROs WE HAVE A DISCREPANCY BETWEEN NUMBER OF LINK IDS AND TRAVEL TIMES
          .take(ghLinkIds.size)

        if (allLinkTravelTimes.size > ghLinkIds.size) {
          allLinkTravelTimes.drop(ghLinkIds.size).foreach { time =>
            totalTravelTime -= time.toInt
          }
        }

        if (ghLinkIds.size < 2) {
          // An empty path by GH's definition. But we still want it to be from a link to a link.
          val snappedPoint = graphHopper.getLocationIndex.findClosest(origin.getY, origin.getX, EdgeFilter.ALL_EDGES)
          val edgeId = snappedPoint.getClosestEdge.getEdge * 2

          linkIds = IndexedSeq(edgeId)
          linkTravelTimes = IndexedSeq(0.0)
        } else {
          linkIds = ghLinkIds
            .sliding(2)
            .map { list =>
              val (ghId1, ghId2) = (list.head, list.last)
              val leftStraight = id2Link(ghId1 * 2)

              val rightStraight = id2Link(ghId2 * 2)
              val rightReverse = id2Link(ghId2 * 2 + 1)
              if (leftStraight._2 == rightStraight._1 || leftStraight._2 == rightReverse._1) {
                ghId1 * 2
              } else ghId1 * 2 + 1
            }
            .toIndexedSeq

          if (ghLinkIds.size > 1) {
            linkIds = linkIds :+ (if (id2Link(linkIds.last)._2 == id2Link(ghLinkIds.last * 2)._1) ghLinkIds.last * 2
                                  else ghLinkIds.last * 2 + 1)
          }
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
    transportNetwork: TransportNetwork,
    osm: OSM,
    directory: String,
    wayId2TravelTime: Map[Long, Double]
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

    val carCH = if (carRouter == "quasiDynamicGH") {
      CHConfig.nodeBased(
        BeamGraphHopper.profile,
        new BeamWeighting(carFlagEncoder, TurnCostProvider.NO_TURN_COST_PROVIDER, wayId2TravelTime)
      )
    } else {
      CHConfig.nodeBased("fastest_car", new FastestWeighting(carFlagEncoder))
    }

    val bikeCH = CHConfig.nodeBased(
      "best_bike",
      new PriorityWeighting(bikeFlagEncoder, new PMap, TurnCostProvider.NO_TURN_COST_PROVIDER)
    )
    val footCH = CHConfig.nodeBased("fastest_foot", new FastestWeighting(footFlagEncoder))

    val ghDirectory = new GHDirectory(directory, DAType.RAM_STORE)
    val graphHopperStorage = new GraphHopperStorage(ghDirectory, encodingManager, false)
    graphHopperStorage.addCHGraph(carCH)
    graphHopperStorage.addCHGraph(bikeCH)
    graphHopperStorage.addCHGraph(footCH)
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
    handler.addCHConfig(carCH)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, carCH))
    handler.addCHConfig(bikeCH)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, bikeCH))
    handler.addCHConfig(footCH)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, footCH))
    handler.setPreparationThreads(3)
    handler.prepare(graphHopperStorage.getProperties, false)

    getProfiles(carRouter).foreach(
      p => graphHopperStorage.getProperties.put("graph.profiles." + p.getName + ".version", p.getVersion)
    )
    graphHopperStorage.getProperties.put("prepare.ch.done", true)

    graphHopperStorage.flush()
  }

  def getProfiles(carRouter: String) = {
    val carProfiles = if (carRouter == "quasiDynamicGH") {
      val profile = new Profile(BeamGraphHopper.profile)
      profile.setVehicle("car")
      profile.setWeighting(BeamGraphHopper.weightingName)
      profile.setTurnCosts(false)
      profile
    } else {
      val fastestCarProfile = new Profile("fastest_car")
      fastestCarProfile.setVehicle("car")
      fastestCarProfile.setWeighting("fastest")
      fastestCarProfile.setTurnCosts(false)
      fastestCarProfile
    }
    val bestBikeProfile = new Profile("best_bike")
    bestBikeProfile.setVehicle("bike")
    bestBikeProfile.setWeighting("fastest")
    bestBikeProfile.setTurnCosts(false)
    val fastestFootProfile = new Profile("fastest_foot")
    fastestFootProfile.setVehicle("foot")
    fastestFootProfile.setWeighting("fastest")
    fastestFootProfile.setTurnCosts(false)
    List(carProfiles, bestBikeProfile, fastestFootProfile)
  }
}
