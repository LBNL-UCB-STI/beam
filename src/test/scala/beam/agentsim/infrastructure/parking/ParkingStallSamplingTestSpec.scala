package beam.agentsim.infrastructure.parking

import scala.util.Random
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory, PrecisionModel}
import org.matsim.api.core.v01.network.{Link, NetworkFactory, Node}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.{LinkFactory, NetworkUtils}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

class ParkingStallSamplingTestSpec extends AnyWordSpec with Matchers {
  val trialsPerTest: Int = 100
  "testLinkBasedSampling" when {
    "square TAZ with agent destination near corner" when {
      "100% availability" should {
        "place parking stall as close as possible to agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val availabilityRatio: Double = 1.0
          val result: Coord = ParkingStallSampling.linkBasedSampling(
            random,
            agent,
            tazTreeMap.TAZtoLinkIdMapping.get(taz.tazId),
            distance,
            availabilityRatio,
            taz,
            true
          )
          distance(agent, result) should equal(100.0) // Request at 100, 100 gets snapped to point at line on y=200
        }
      }
      "80% availability" should {
        "place closest parking stall each link roughly 50% of the time" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val availabilityRatio: Double = 0.8
          val distances = (1 to 100).map { x =>
            val result: Coord = ParkingStallSampling.linkBasedSampling(
              random,
              agent,
              tazTreeMap.TAZtoLinkIdMapping.get(taz.tazId),
              distance,
              availabilityRatio,
              taz,
              true
            )
            distance(agent, result)
          }
          // Should create a stall on the closest point (100m away) most of the time, rarely create one really far away
          val numberOfTimesAtClosestLink = distances.count(_ == 100.0)
          numberOfTimesAtClosestLink should be >= 50
          val numberOfTimesAtFartherLinks = distances.count(x => (x != 100.0) & (x != 150.0))
          numberOfTimesAtFartherLinks should be <= 30
        }
      }
      "30% availability" should {
        "place closest parking stall each link roughly 50% of the time" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val availabilityRatio: Double = 0.3
          val distances = (1 to 100).map { x =>
            val result: Coord = ParkingStallSampling.linkBasedSampling(
              random,
              agent,
              tazTreeMap.TAZtoLinkIdMapping.get(taz.tazId),
              distance,
              availabilityRatio,
              taz,
              true
            )
            distance(agent, result)
          }
          // Should be more likely to create a stall farther away
          val numberOfTimesAtClosestLink = distances.count(_ == 100.0)
          numberOfTimesAtClosestLink should be <= 50
          val numberOfTimesAtFartherLinks = distances.count(x => (x != 100.0) & (x != 150.0))
          numberOfTimesAtFartherLinks should be > 20
        }
      }
    }
  }

  "testAvailabilityAwareSampling" when {
    "square TAZ with agent destination near corner" when {
      "100% availability" should {
        "place parking stall directly at agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          val availabilityRatio: Double = 1.0
          val result: Coord = ParkingStallSampling.availabilityAwareSampling(
            random,
            agent,
            taz,
            availabilityRatio,
            true
          )
          distance(agent, result) should equal(0.0)
        }
      }
      ">75% availability" should {
        "place parking stall very close to agent location" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (100 until 75 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 20% of the taz diameter
            val errorBounds: Double = tazD * 0.20
            dist should be <= errorBounds
          }
        }
      }
      ">50% availability" should {
        "place parking stall in a wider field with relation to the agent" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (75 until 50 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 50% of the taz diameter
            val errorBounds: Double = tazD * 0.50
            dist should be <= errorBounds
          }
        }
      }
      ">25% availability" should {
        "place parking stall in a range that is nearly as wide as the TAZ with relation to the agent" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          for {
            availabilityRatio <- (50 until 25 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {
            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(agent, result)

            // allow points placed at a distance up to 100% of the taz diameter
            val errorBounds: Double = tazD
            dist should be <= errorBounds
          }
        }
      }
      "low availability" should {
        "place 99.9% of parking stalls in a range that is within 125% of TAZ diameter with relation to the TAZ centroid" in new ParkingStallSamplingTestSpec.SquareTAZWorld {
          var (counter, max) = (0, 0)
          for {
            availabilityRatio <- (25 until 0 by -1).map { _.toDouble * 0.01 }
            _                 <- 1 to trialsPerTest
          } {

            val result = ParkingStallSampling.availabilityAwareSampling(
              random,
              agent,
              taz,
              availabilityRatio,
              true
            )

            val dist: Double = distance(taz.coord, result)
            if (dist > tazD * 1.25) counter += 1
            max += 1
          }

          // confirm samples perform within tolerance
          // since we are using gaussian random, we sometimes have outliers, but we require that 99.9% of values are
          // in an expected bounds
          val targetPercentError: Double = 0.001
          val integerScale: Int = 10000
          val ratioAsInteger: Int = math.ceil((counter.toDouble / max.toDouble) * integerScale).toInt
          ratioAsInteger should be <= (targetPercentError * integerScale).toInt
        }
      }
    }
  }

}

object ParkingStallSamplingTestSpec {

  // a parking problem which is a square coordinate system in the range x=[0,1000],y=[0,1000]
  // with agent destination at position (100,100)
  trait SquareTAZWorld {
    val random: Random = new Random(0L)

    val (tazX: Double, tazY: Double) = (500.0, 500.0)
    val tazD: Double = 1000.0
    val tazArea: Double = tazD * tazD

    private val projection: Int = 4326
    private val geometryFactory: GeometryFactory = new GeometryFactory(new PrecisionModel(), projection)

    val geometry = geometryFactory.createPolygon(
      Array[Coordinate](
        new Coordinate(0.0, 0.0),
        new Coordinate(500.0, 0.0),
        new Coordinate(500.0, 500.0),
        new Coordinate(0.0, 500.0),
        new Coordinate(0.0, 0.0)
      )
    )

    val TAZtoLinkIdMapping: mutable.HashMap[Id[TAZ], QuadTree[Link]] =
      mutable.HashMap.empty[Id[TAZ], QuadTree[Link]]
    val network = NetworkUtils.createNetwork()

    // Setup: Four links in the TAZ, one (horizontal) passes within 100m of the point at (100, 200) and one passes
    // within 150m of the point at (250, 100). The other two links are farther away (350,100) and (100, 300).
    // Those are the four potential places where stalls should be created. If there's 100% availability the stall
    // should always be at the closer option, if there's less availability then  it's more likely the stall is
    // farther away

    /////////////////////////////////////
    //                 |               |
    //                 |               |
    //-----X-----------+---------------+-
    //                 |               |
    //                 |               |
    //-----X-----------+---------------+-
    //                 |               |
    //     *           X               X
    //                 |               |
    //////////////////////////////////////

    val n1: Node = network.getFactory.createNode(Id.createNodeId("1"), new Coord(1.0, 200.0))
    val n2: Node = network.getFactory.createNode(Id.createNodeId("2"), new Coord(499.0, 200.0))
    val n3: Node = network.getFactory.createNode(Id.createNodeId("3"), new Coord(250.0, 1.0))
    val n4: Node = network.getFactory.createNode(Id.createNodeId("4"), new Coord(250.0, 499.0))
    val n5: Node = network.getFactory.createNode(Id.createNodeId("5"), new Coord(1.0, 300.0))
    val n6: Node = network.getFactory.createNode(Id.createNodeId("6"), new Coord(499.0, 300.0))
    val n7: Node = network.getFactory.createNode(Id.createNodeId("7"), new Coord(350.0, 1.0))
    val n8: Node = network.getFactory.createNode(Id.createNodeId("8"), new Coord(350.0, 499.0))
    val link12 = network.getFactory.createLink(Id.createLinkId("12"), n1, n2)
    val link34 = network.getFactory.createLink(Id.createLinkId("34"), n3, n4)
    val link65 = network.getFactory.createLink(Id.createLinkId("65"), n6, n5)
    val link78 = network.getFactory.createLink(Id.createLinkId("78"), n8, n7)

    network.addNode(n1)
    network.addNode(n2)
    network.addNode(n3)
    network.addNode(n4)
    network.addNode(n5)
    network.addNode(n6)
    network.addNode(n7)
    network.addNode(n8)
    network.addLink(link12)
    network.addLink(link34)
    network.addLink(link65)
    network.addLink(link78)

    val taz: TAZ = new TAZ(
      Id.create("taz", classOf[TAZ]),
      new Coord(tazX, tazY),
      tazArea,
      Some(geometry)
    )

    val tazQuadTree = new QuadTree[TAZ](0.0, 0.0, 500.0, 500.0)
    tazQuadTree.put(geometry.getCentroid.getX, geometry.getCentroid.getY, taz)
    val tazTreeMap = new TAZTreeMap(tazQuadTree)

    tazTreeMap.mapNetworkToTAZs(network)

    val agent: Coord = new Coord(100.0, 100.0)

    // Euclidian distance for tests
    def distance(a: Coord, b: Coord): Double = math.sqrt(math.pow(a.getY - b.getY, 2) + math.pow(a.getX - b.getX, 2))
  }
}
