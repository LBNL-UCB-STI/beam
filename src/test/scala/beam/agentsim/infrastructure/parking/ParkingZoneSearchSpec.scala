//package beam.agentsim.infrastructure.parking
//
//import scala.util.Random
//
//import beam.agentsim.agents.choice.logit.MultinomialLogit
//import beam.agentsim.infrastructure.ParkingInquiry
//import beam.agentsim.infrastructure.charging._
//import beam.agentsim.infrastructure.parking.ParkingZoneSearch._
//import beam.agentsim.infrastructure.taz._
//import beam.sim.common.GeoUtils
//import org.matsim.api.core.v01.{Coord, Id}
//import org.scalatest.{Matchers, WordSpec}
//import com.vividsolutions.jts.geom.Envelope
//import org.geotools.data.shapefile.index.quadtree.QuadTree
//
//class ParkingZoneSearchSpec extends AnyWordSpec with Matchers {
//  "A ParkingZoneSearch" when {
//    "searched for something when there are no alternatives" should {
//      "result in None" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//
//        val tree: ParkingZoneSearch.ZoneSearchTree[TAZ] = Map()
//        val zones: Array[ParkingZone] = Array()
//
//        val params = ParkingZoneSearchParams(
//          destinationInMiddle,
//          parkingDuration = 0.0,
//          MultinomialLogit(Map.empty, Map.empty),
//          tree,
//          zones,
//
//        )
//
//        val result = ParkingZoneSearch.incrementalParkingZoneSearch(
//          parkingZoneSearchConfig,
//
//        )
//          destinationInMiddle,
//          valueOfTime = 0.0,
//          parkingDuration = 0.0, // ignore pricing ranking
//          ParkingInquiry.simpleDistanceAndParkingTicketEqualUtilityFunction,
//          Seq(TAZ.DefaultTAZ),
//          Seq(ParkingType.Public),
//          tree,
//          zones,
//          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//          ParkingZoneSearchSpec.random,
//          true,
//          true
//        )
//
//        result should be(None)
//      }
//    }
//
//    "search for parking with full availability" should {
//      "find a spot which places the stall exactly at the driver's destination" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//        val result: Option[ParkingZoneSearch.ParkingSearchResult] = ParkingZoneSearch.find(
//          destinationNearTazB,
//          valueOfTime = 1.0,
//          parkingDuration = 0.0, // ignore pricing ranking
//          ParkingInquiry.simpleDistanceEqualUtilityFunction,
//          tazsInProblem,
//          Seq(ParkingType.Public),
//          parkingSearchTree,
//          parkingZones,
//          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//          ParkingZoneSearchSpec.random,
//          true,
//          true
//        )
//
//        result match {
//          case None => fail()
//          case Some(
//              ParkingZoneSearch.ParkingSearchResult(taz, parkingType, parkingZone, stallCoord, utilityOfAlternative)
//              ) =>
//            // due to 100% availability at both stalls, the taz could be either TAZ
//
//            // since everything is equal, either TAZ should work out, but
//            utilityOfAlternative should equal(0)
//
//            // these should be consistent with the configuration of this scenario
//            parkingType should equal(ParkingType.Public)
//
//            // since availability is 18/18 = 1.0, sample location should equal destination coordinate
//            stallCoord should equal(destinationNearTazB)
//        }
//      }
//    }
//
//    "search for parking exactly between two TAZs finds some availability at one TAZ" should {
//      "find a spot near their destination but with some variance due to the availability of parking" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//
//        // make TAZ A's parking stalls have very low availability
//        parkingZones(0).stallsAvailable = 1
//        // make TAZ B's parking stalls have medium availability
//        parkingZones(1).stallsAvailable = 14
//
//        val result: Option[ParkingZoneSearch.ParkingSearchResult] = ParkingZoneSearch.find(
//          destinationInMiddle,
//          valueOfTime = 1.0,
//          parkingDuration = 0.0, // ignore pricing ranking
//          ParkingInquiry.simpleDistanceAndParkingTicketEqualUtilityFunction,
//          tazsInProblem,
//          Seq(ParkingType.Public),
//          parkingSearchTree,
//          parkingZones,
//          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//          ParkingZoneSearchSpec.random,
//          true,
//          true
//        )
//
//        result match {
//          case None                                                                                                 => fail()
//          case Some(ParkingZoneSearch.ParkingSearchResult(taz, parkingType, parkingZone, stallCoord, rankingValue)) =>
//            // TAZ B should have been selected because everything is equal except for availability is lower for A
//            taz should equal(tazB)
//
//            // these should be consistent with the configuration of this scenario
//            parkingType should equal(ParkingType.Public)
//            parkingZone.parkingZoneId should equal(1)
//
//            // since availability is 14/18 = 77%, the location of the stall should be fairly
//            // close to the destination coordinate (5,5) within a bounds
//            val bounds: Double = 1.0
//            math.abs(stallCoord.getX - destinationInMiddle.getX) should be < bounds
//            math.abs(stallCoord.getY - destinationInMiddle.getY) should be < bounds
//
//            // the parking location should be closer to TAZ B than TAZ A
//            val distToTAZA = ParkingZoneSearchSpec.distance(tazA.coord, stallCoord)
//            val distToTAZB = ParkingZoneSearchSpec.distance(tazB.coord, stallCoord)
//            distToTAZB should be < distToTAZA
//        }
//      }
//    }
//    "search for parking near a TAZ when it has no availability, when the other has full availability" should {
//      "find a spot nearer in the other TAZ but at the agent's destination" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//
//        // make TAZ A's parking stalls have very low availability
//        parkingZones(0).stallsAvailable = 0
//
//        val result: Option[ParkingZoneSearch.ParkingSearchResult] = ParkingZoneSearch.find(
//          destinationNearTazA,
//          valueOfTime = 1.0,
//          parkingDuration = 0.0,
//          ParkingInquiry.simpleDistanceAndParkingTicketEqualUtilityFunction,
//          tazsInProblem,
//          Seq(ParkingType.Public),
//          parkingSearchTree,
//          parkingZones,
//          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//          ParkingZoneSearchSpec.random,
//          true,
//          true
//        )
//
//        result match {
//          case None                                                                                                 => fail()
//          case Some(ParkingZoneSearch.ParkingSearchResult(taz, parkingType, parkingZone, stallCoord, rankingValue)) =>
//            // TAZ B should have been selected because everything is equal except for availability is lower for A
//            taz should equal(tazB)
//
//            // these should be consistent with the configuration of this scenario
//            parkingType should equal(ParkingType.Public)
//            parkingZone.parkingZoneId should equal(1)
//
//            // the stall should be exactly located at the destination
//            stallCoord should equal(destinationNearTazA)
//        }
//      }
//    }
//    "search for parking exactly between two TAZs where block pricing is better" should {
//      "choose parking with block pricing" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//
//        // our block rate is $1.00/hr, our flat fee is $10.00.
//        // parking duration is 9hrs; $9.00 vs $10.00, block pricing wins
//        val parkingDuration: Double = 3600 * 9
//        val valueOfTime: Double = 0.0
//
//        // number of samples for sampling based on MNL
//        val sampleSize = 10000
//
//        val samples: IndexedSeq[ParkingZoneSearch.ParkingSearchResult] = for {
//          _ <- 1 until sampleSize
//          result <- ParkingZoneSearch.find(
//            destinationInMiddle,
//            valueOfTime = valueOfTime,
//            parkingDuration = parkingDuration,
//            ParkingInquiry.simpleDistanceAndParkingTicketEqualUtilityFunction,
//            tazsInProblem,
//            Seq(ParkingType.Public),
//            parkingSearchTree,
//            parkingZones,
//            ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//            ParkingZoneSearchSpec.random,
//            true,
//            true
//          )
//        } yield {
//          result
//        }
//
//        // 50% or more times TAZ B should have been selected because everything is equal
//        // except the ticket fee and the availability which is lower for A
//        samples.count {
//          _.bestTAZ == tazB
//        } > (sampleSize / 2)
//
//        // these should be consistent with the configuration of this scenario
//        samples
//          .filter(_.bestTAZ == tazB)
//          .foreach(searchResult => (searchResult.bestParkingType should equal(ParkingType.Public)))
//        samples
//          .filter(_.bestTAZ == tazB)
//          .foreach(searchResult => (searchResult.bestParkingZone.parkingZoneId should equal(1)))
//
//        // the stall should be exactly located at the destination
//        samples
//          .filter(_.bestTAZ == tazB)
//          .foreach(searchResult => (searchResult.bestCoord should equal(destinationInMiddle)))
//
//      }
//    }
//    "search for parking exactly between two TAZs where flat fee pricing is better" should {
//      "choose parking with flat fee pricing" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
//
//        // our block rate is $1.00/hr, our flat fee is $10.00.
//        // parking duration is 11hrs; $11.00 vs $10.00, flat fee wins
//        val parkingDuration: Double = 3600 * 11
//        val valueOfTime: Double = 0.0
//
//        // number of samples for sampling based on MNL
//        val sampleSize = 10000
//
//        val samples: IndexedSeq[ParkingZoneSearch.ParkingSearchResult] = for {
//          _ <- 1 until sampleSize
//          result <- ParkingZoneSearch.find(
//            destinationInMiddle,
//            valueOfTime = valueOfTime,
//            parkingDuration = parkingDuration,
//            ParkingInquiry.simpleDistanceAndParkingTicketEqualUtilityFunction,
//            tazsInProblem,
//            Seq(ParkingType.Public),
//            parkingSearchTree,
//            parkingZones,
//            ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
//            ParkingZoneSearchSpec.random,
//            true,
//            true
//          )
//        } yield {
//          result
//        }
//
//        // 50% or more times TAZ A should have been selected because everything except the ticket fee is equal
//        // except the ticket fee and the availability which is lower for A
//        samples.count {
//          _.bestTAZ == tazA
//        } > (sampleSize / 2)
//
//        // these should be consistent with the configuration of this scenario
//        samples
//          .filter(_.bestTAZ == tazA)
//          .foreach(searchResult => (searchResult.bestParkingType should equal(ParkingType.Public)))
//
//        samples
//          .filter(_.bestTAZ == tazA)
//          .foreach(searchResult => (searchResult.bestParkingZone.parkingZoneId should equal(0)))
//
//        // the stall should be exactly located at the destination
//        samples
//          .filter(_.bestTAZ == tazA)
//          .foreach(searchResult => (searchResult.bestCoord should equal(destinationInMiddle)))
//      }
//    }
//  }
//
//}
//
//object ParkingZoneSearchSpec {
//
//  val random: Random = new Random(0L)
//
//  // this test scenario covers some basic functionality of ParkingZoneSearches
//  // including ranking by availability and pricing model
//  trait SimpleParkingAlternatives {
//
//    val mockGeoUtils = new GeoUtils {
//      def localCRS: String = "epsg:32631"
//    }
//
//    // in this scenario, there are two TAZs: one at (0,0) and one at (10,10)
//    // there are three agent destinations which are being considered
//    // the TAZs have a slightly different number of stalls but are otherwise the same
//
//    val sourceData: Iterator[String] =
//      """taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor
//        |A,Public,FlatFee,none,7,1000,
//        |B,Public,Block,none,18,100,
//        |
//      """.stripMargin.split("\n").toIterator
//
//    val parkingZoneSearchConfig: ParkingZoneSearchConfiguration =
//      ParkingZoneSearchConfiguration(
//        searchStartRadius = 1.0,
//        searchMaxRadius = 20.0,
//        new Envelope(-1, 11, -1, 11),
//        mockGeoUtils.distUTMInMeters
//      )
//
//    val parkingZoneFilterFunction: ParkingZone => Boolean =
//      (parkingZone: ParkingZone) => {
//        true
//      }
//
//    val ParkingZoneFileUtils.ParkingLoadingAccumulator(parkingZones, parkingSearchTree, _, _) =
//      ParkingZoneFileUtils.fromIterator(sourceData)
//    val destinationNearTazA = new Coord(1, 1) // near taz 1
//    val destinationNearTazB = new Coord(9, 9) // near taz 2
//    val destinationInMiddle = new Coord(5, 5) // middle of TAZs
//    val tazA = new TAZ(Id.create("A", classOf[TAZ]), new Coord(0, 0), 0)
//    val tazB = new TAZ(Id.create("B", classOf[TAZ]), new Coord(10, 10), 0)
//    val tazsInProblem: Seq[TAZ] = Seq(tazA, tazB)
//
//    val mockTazTreeMap: Map[Id[TAZ], TAZ] =
//      Map(
//        tazA.tazId -> tazA,
//        tazB.tazId -> tazB
//      )
//    val mockTazQuadTree: QuadTree[TAZ] =
//  }
//
//  // Euclidian distance for tests
//  def distance(a: Coord, b: Coord): Double = math.sqrt(math.pow(a.getY - b.getY, 2) + math.pow(a.getX - b.getX, 2))
//}
