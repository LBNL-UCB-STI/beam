package beam.router

import java.io.File
import java.time.OffsetDateTime

import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransportNetwork
import org.scalatest.{Assertion, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

class R5RouterSpec extends FlatSpec with Matchers {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val transportNetwork = TransportNetwork.fromDirectory(new File("test/input/sf-light/r5"))

  private val mode = EdgeStore.EdgeFlag.ALLOWS_CAR
  private val streetMode = StreetMode.CAR

  private val startEdge1 = edgeById(20109) //This link doesn't have mode ALLOWS_CAR
  private val stopEdge1 = edgeById(66763)

  private val stopEdge2 = edgeById(46525) //This link doesn't have mode ALLOWS_CAR

  it should "fail with start edge" in {
    val path = routeV1(startEdge1, stopEdge1)
    pathModeCheck(path) shouldBe false
  }

  it should "fail with stop edge" in {
    val path = routeV1(startEdge1, stopEdge2)
    pathModeCheck(path) shouldBe false
  }

  it should "not fail with start edge with different setOrigin" in {
    val path = routeV2(startEdge1, stopEdge1)
    pathModeCheck(path) shouldBe true
  }

  it should "not fail with stop edge with different setDestination" in {
    val path = routeV2(startEdge1, stopEdge2)
    pathModeCheck(path) shouldBe true
  }

  private def pathModeCheck(path: Iterable[EdgeStore#Edge]): Boolean = {
    val checkFlag = path.forall(_.getFlag(mode))
    if (!checkFlag) {
      logger.error(
        "Failed with path where edges are: {}",
        path.map(e => s"${e.getEdgeIndex} - ${e.getFlags}").mkString("\n", "\n", "\n")
      )
    }
    checkFlag
  }

  private def routeV1(startEdge: Option[EdgeStore#Edge], stopEdge: Option[EdgeStore#Edge]): Iterable[EdgeStore#Edge] = {
    val result = for {
      startVertex <- startEdge.flatMap(edge => vertexById(edge.getFromVertex))
      stopVertex  <- stopEdge.flatMap(edge => vertexById(edge.getToVertex))
      streetPath  <- calculateRouteV1(startVertex, stopVertex)
    } yield streetPath

    result.map(_.getEdges.asScala).getOrElse(Iterable.empty[Integer]).flatMap(eid => edgeById(eid))
  }

  private def calculateRouteV1(startVertex: VertexStore#Vertex, stopVertex: VertexStore#Vertex): Option[StreetPath] = {
    val streetRouter = createStreetRouter(startVertex, stopVertex)

    streetRouter.setOrigin(startVertex.getLat, startVertex.getLon)
    streetRouter.setDestination(stopVertex.getLat, stopVertex.getLon)
    streetRouter.route()

    Option(streetRouter.getState(streetRouter.getDestinationSplit)).map { lastState =>
      new StreetPath(lastState, transportNetwork, false)
    }
  }

  private def routeV2(startEdge: Option[EdgeStore#Edge], stopEdge: Option[EdgeStore#Edge]): Iterable[EdgeStore#Edge] = {
    val result = for {
      startSplit <- startEdge.flatMap(splitByEdge(_, true))
      stopSplit  <- stopEdge.flatMap(splitByEdge(_, false))
      streetPath <- calculateRouteV2(startSplit, stopSplit)
    } yield streetPath

    result.map(_.getEdges.asScala).getOrElse(Iterable.empty[Integer]).flatMap(eid => edgeById(eid))
  }

  private def calculateRouteV2(startSplit: Split, stopSplit: Split): Option[StreetPath] = {
    val startVertexOpt = vertexById(startSplit.vertex0)
    val stopVertexOpt = vertexById(stopSplit.vertex1)
    val streetRouterOpt = for {
      startVertex <- startVertexOpt
      stopVertex  <- stopVertexOpt
    } yield {
      val streetRouter = createStreetRouter(startVertex, stopVertex)
      streetRouter.setOrigin(startVertex.index)
      streetRouter.setDestination(stopSplit)
      streetRouter.route()

      streetRouter
    }

    streetRouterOpt.map(_.getState(stopSplit)).map { lastState =>
      new StreetPath(lastState, transportNetwork, false)
    }
  }

  private def splitByEdge(edge: EdgeStore#Edge, isStartEdge: Boolean): Option[Split] = {
    val vertex = if (isStartEdge) {
      edge.getFromVertex
    } else {
      edge.getToVertex
    }

    vertexById(vertex).flatMap { vertex =>
      Option(
        Split.findOnEdge(
          vertex.getLat,
          vertex.getLon,
          edge
        )
      )
    }
  }

  private def edgeById(edgeId: Int): Option[EdgeStore#Edge] = {
    Try(transportNetwork.streetLayer.edgeStore.getCursor(edgeId)).toOption
  }

  private def vertexById(vertexId: Int): Option[VertexStore#Vertex] = {
    Try(transportNetwork.streetLayer.edgeStore.vertexStore.getCursor(vertexId)).toOption
  }

  private def createStreetRouter(startVertex: VertexStore#Vertex, stopVertex: VertexStore#Vertex): StreetRouter = {
    val streetRouter = new StreetRouter(
      transportNetwork.streetLayer,
      travelTimeCalculator(),
      turnCostCalculator,
      travelCostCalculator()
    )

    val profileRequest = createProfileRequest(startVertex, stopVertex)

    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = streetMode

    streetRouter
  }

  private val turnCostCalculator: TurnCostCalculator =
    new TurnCostCalculator(transportNetwork.streetLayer, true) {
      override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
    }
  private def travelCostCalculator(): TravelCostCalculator =
    (_: EdgeStore#Edge, _: Int, traversalTimeSeconds: Float) => traversalTimeSeconds

  private def travelTimeCalculator(): TravelTimeCalculator = { (_: EdgeStore#Edge, _: Int, _: StreetMode, _) =>
    10
  }

  private def createProfileRequest(startVertex: VertexStore#Vertex, stopVertex: VertexStore#Vertex) = {
    val profileRequest = new ProfileRequest()
    // Warning: carSpeed is not used for link traversal (rather, the OSM travel time model is used),
    // but for R5-internal bushwhacking from network to coordinate, AND ALSO for the A* remaining weight heuristic,
    // which means that this value must be an over(!)estimation, otherwise we will miss optimal routes,
    // particularly in the presence of tolls.
    profileRequest.carSpeed = 60f
    profileRequest.maxWalkTime = 30
    profileRequest.maxCarTime = 30
    profileRequest.maxBikeTime = 30
    // Maximum number of transit segments. This was previously hardcoded as 4 in R5, now it is a parameter
    // that defaults to 8 unless I reset it here. It is directly related to the amount of work the
    // transit router has to do.
    profileRequest.maxRides = 4
    profileRequest.streetTime = 2 * 60
    profileRequest.maxTripDurationMinutes = 4 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    profileRequest.monteCarloDraws = 10
    profileRequest.date = OffsetDateTime.parse("2017-09-22T00:00:00-07:00").toLocalDate
    // Doesn't calculate any fares, is just a no-op placeholder
    profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator
    profileRequest.suboptimalMinutes = 0

    profileRequest.fromLon = startVertex.getLon
    profileRequest.fromLat = startVertex.getLat
    profileRequest.toLon = stopVertex.getLon
    profileRequest.toLat = stopVertex.getLat
    profileRequest.fromTime = 1500
    profileRequest.toTime = profileRequest.fromTime + 61

    profileRequest
  }
}
