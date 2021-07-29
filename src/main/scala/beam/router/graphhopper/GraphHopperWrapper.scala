package beam.router.graphhopper

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Router
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.common.GeoUtils
import com.conveyal.osmlib.{OSM, OSMEntity}
import com.conveyal.r5.transit.TransportNetwork
import com.graphhopper.config.{CHProfile, Profile}
import com.graphhopper.reader.ReaderWay
import com.graphhopper.routing.ch.{CHPreparationHandler, PrepareContractionHierarchies}
import com.graphhopper.routing.util._
import com.graphhopper.routing.weighting.{FastestWeighting, TurnCostProvider, Weighting}
import com.graphhopper.storage._
import com.graphhopper.util.{PMap, Parameters, PointList}
import com.graphhopper.{GHRequest, GraphHopper, ResponsePath}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

abstract class GraphHopperWrapper(
  graphDir: String,
  geo: GeoUtils,
  id2Link: Map[Int, (Coord, Coord)]
) extends Router {

  protected val beamMode: BeamMode

  private val graphHopper = {
    val profile = getProfile()
    val graphHopper = createGraphHopper()
    graphHopper.setPathDetailsBuilderFactory(new BeamPathDetailsBuilderFactory())
    graphHopper.setGraphHopperLocation(graphDir)
    graphHopper.setProfiles(profile)
    graphHopper.getCHPreparationHandler.setCHProfiles(new CHProfile(profile.getName))
    graphHopper.importOrLoad()
    graphHopper
  }

  protected def createGraphHopper(): GraphHopper
  protected def getProfile(): Profile
  protected def prepareRequest(request: GHRequest)
  protected def getLinkTravelTimes(responsePath: ResponsePath, totalTravelTime: Int): IndexedSeq[Double]
  protected def getCost(beamLeg: BeamLeg, vehicleTypeId: Id[BeamVehicleType]): Double

  override def calcRoute(
    routingRequest: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    assert(!routingRequest.withTransit, "Can't route transit yet")
    assert(
      routingRequest.streetVehicles.size == 1,
      "Can only route unimodal trips with single available vehicle so far"
    )
    val origin = geo.utm2Wgs(routingRequest.originUTM)
    val destination = geo.utm2Wgs(routingRequest.destinationUTM)
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val streetVehicle = routingRequest.streetVehicles.head
    val request = new GHRequest(origin.getY, origin.getX, destination.getY, destination.getX)
    prepareRequest(request)

    val response = graphHopper.route(request)
    val alternatives = if (response.hasErrors) {
      Seq()
    } else {
      response.getAll.asScala
        .map(responsePath => {
          for {
            (beamTotalTravelTime, linkIds, linkTravelTimes, distance) <- processResponsePath(responsePath)
            response <- getRouteResponse(
              routingRequest,
              beamTotalTravelTime,
              linkIds,
              linkTravelTimes,
              origin,
              destination,
              distance,
              streetVehicle
            )
          } yield {
            response
          }
        })
        .filter(_.isDefined)
        .map(_.get)
    }
    RoutingResponse(
      alternatives,
      routingRequest.requestId,
      Some(routingRequest),
      isEmbodyWithCurrentTravelTime = false,
      triggerId = routingRequest.triggerId
    )
  }

  private def processResponsePath(responsePath: ResponsePath) = {
    var totalTravelTime = (responsePath.getTime / 1000).toInt
    val ghLinkIds: IndexedSeq[Int] =
      responsePath.getPathDetails
        .asScala(Parameters.Details.EDGE_ID)
        .asScala
        .map(pd => pd.getValue.asInstanceOf[Int])
        .toIndexedSeq

    val allLinkTravelTimes = getLinkTravelTimes(responsePath, totalTravelTime)

    val linkTravelTimes: IndexedSeq[Double] = allLinkTravelTimes
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

    // got NaN speed if travel time equals to 0
    if (ghLinkIds.size < 2 || (totalTravelTime - linkTravelTimes.head.toInt) == 0) {
      None
    } else {
      val linkIds = convertGhLinksToR5(ghLinkIds)
      val beamTotalTravelTime = totalTravelTime - linkTravelTimes.head.toInt
      Some(beamTotalTravelTime, linkIds, linkTravelTimes, responsePath.getDistance)
    }
  }

  private def getRouteResponse(
    routingRequest: RoutingRequest,
    beamTotalTravelTime: Int,
    linkIds: IndexedSeq[Int],
    linkTravelTimes: IndexedSeq[Double],
    origin: Coord,
    destination: Coord,
    distance: Double,
    streetVehicle: StreetVehicle
  ) = {
    try {
      val beamLeg = BeamLeg(
        routingRequest.departureTime,
        beamMode,
        beamTotalTravelTime,
        BeamPath(
          linkIds,
          linkTravelTimes,
          None,
          SpaceTime(origin, routingRequest.departureTime),
          SpaceTime(destination, routingRequest.departureTime + beamTotalTravelTime),
          distance
        )
      )
      Some(
        EmbodiedBeamTrip(
          IndexedSeq(
            EmbodiedBeamLeg(
              beamLeg,
              streetVehicle.id,
              streetVehicle.vehicleTypeId,
              asDriver = true,
              cost = getCost(beamLeg, streetVehicle.vehicleTypeId),
              unbecomeDriverOnCompletion = true
            )
          )
        )
      )
    } catch {
      case _: Exception => None
    }
  }

  private def convertGhLinksToR5(ghLinkIds: IndexedSeq[Int]) = {
    val linkIds = ghLinkIds
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

    linkIds :+ (if (id2Link(linkIds.last)._2 == id2Link(ghLinkIds.last * 2)._1) ghLinkIds.last * 2
                else ghLinkIds.last * 2 + 1)
  }
}

object GraphHopperWrapper {

  def createCarGraphDirectoryFromR5(
    carRouter: String,
    transportNetwork: TransportNetwork,
    osm: OSM,
    directory: String,
    wayId2TravelTime: Map[Long, Double]
  ): Unit = {
    val flagEncoderParams = new PMap
    flagEncoderParams.putObject("turn_costs", false)
    val flagEncoder = new CarFlagEncoder(flagEncoderParams)

    val emBuilder: EncodingManager.Builder = new EncodingManager.Builder
    emBuilder.add(flagEncoder)
    val encodingManager = emBuilder.build

    if (carRouter == "quasiDynamicGH") {
      createGraphDirectoryFromR5(
        encodingManager,
        CarGraphHopperWrapper.BeamProfile,
        new BeamWeighting(flagEncoder, TurnCostProvider.NO_TURN_COST_PROVIDER, wayId2TravelTime),
        transportNetwork,
        osm,
        directory
      )
    } else {
      createGraphDirectoryFromR5(
        encodingManager,
        CarGraphHopperWrapper.FastestProfile,
        new FastestWeighting(flagEncoder),
        transportNetwork,
        osm,
        directory
      )
    }
  }

  def createWalkGraphDirectoryFromR5(
    transportNetwork: TransportNetwork,
    osm: OSM,
    directory: String
  ): Unit = {
    val flagEncoder = new FootFlagEncoder
    val emBuilder: EncodingManager.Builder = new EncodingManager.Builder
    emBuilder.add(flagEncoder)
    val encodingManager = emBuilder.build

    createGraphDirectoryFromR5(
      encodingManager,
      WalkGraphHopperWrapper.FastestProfile,
      new FastestWeighting(flagEncoder),
      transportNetwork,
      osm,
      directory
    )
  }

  private def createGraphDirectoryFromR5(
    encodingManager: EncodingManager,
    profile: Profile,
    weighting: Weighting,
    transportNetwork: TransportNetwork,
    osm: OSM,
    directory: String
  ): Unit = {
    val ch = CHConfig.nodeBased(profile.getName, weighting)
    val ghDirectory = new GHDirectory(directory, DAType.RAM_STORE)
    val graphHopperStorage = new GraphHopperStorage(ghDirectory, encodingManager, false)
    graphHopperStorage.addCHGraph(ch)
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
    handler.addCHConfig(ch)
    handler.addPreparation(PrepareContractionHierarchies.fromGraphHopperStorage(graphHopperStorage, ch))
    handler.setPreparationThreads(3)
    handler.prepare(graphHopperStorage.getProperties, false)

    graphHopperStorage.getProperties.put("graph.profiles." + profile.getName + ".version", profile.getVersion)
    graphHopperStorage.getProperties.put("prepare.ch.done", true)

    graphHopperStorage.flush()
  }
}
