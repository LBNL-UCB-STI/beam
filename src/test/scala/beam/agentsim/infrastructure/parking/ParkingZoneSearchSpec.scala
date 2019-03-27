package beam.agentsim.infrastructure.parking

import scala.util.Random

import beam.agentsim.infrastructure.charging.ChargingInquiryData
import beam.agentsim.infrastructure.taz._
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.{Matchers, WordSpec}

class ParkingZoneSearchSpec extends WordSpec with Matchers {
  "A ParkingZoneSearch" when {
    "searched for something when there are no alternatives" should {
      "result in None" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
        val tree: ParkingZoneSearch.ZoneSearch = Map()
        val zones: Array[ParkingZone] = Array()

        val result = ParkingZoneSearch.find(
          destinationInMiddle,
          Option.empty[ChargingInquiryData[String, String]],
          Seq(TAZ.DefaultTAZ),
          Seq(ParkingType.Public),
          tree,
          zones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0),
          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
          ParkingZoneSearchSpec.random
        )

        result should be(None)
      }
    }
    "search for parking with full availability" should {
      "find a spot in the nearest TAZ with full availability which places the stall exactly at the driver's destination" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {
        val result: Option[ParkingRanking.RankingAccumulator] = ParkingZoneSearch.find(
          destinationNearTazA,
          Option.empty[ChargingInquiryData[String,String]],
          tazsInProblem,
          Seq(ParkingType.Public),
          parkingSearchTree,
          parkingZones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0),
          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
          ParkingZoneSearchSpec.random
        )

        result match {
          case None => fail()
          case Some(ParkingRanking.RankingAccumulator(taz, parkingType, parkingZone, stallCoord, rankingValue)) =>

            // since everything is equal, centroid distance should win for TAZ selection
            taz should equal(tazA)

            // these should be consistent with the configuration of this scenario
            parkingType should equal(ParkingType.Public)
            parkingZone.parkingZoneId should equal(0)

            // since availability is 18/18 = 1.0, sample location should equal destination coordinate
            stallCoord should equal (destinationNearTazA)

            // rankingFunction is not fully implemented yet as of writing - rjf 20190327
        }
      }
    }
    "search for parking finds some availability at one TAZ" should {
      "find a spot near their destination but with some variance due to the availability of parking" in new ParkingZoneSearchSpec.SimpleParkingAlternatives {

        // make TAZ A's parking stalls have very low availability
        parkingZones(0).stallsAvailable = 1
        // make TAZ B's parking stalls have medium availability
        parkingZones(1).stallsAvailable = 14


        val result: Option[ParkingRanking.RankingAccumulator] = ParkingZoneSearch.find(
          destinationInMiddle,
          Option.empty[ChargingInquiryData[String,String]],
          tazsInProblem,
          Seq(ParkingType.Public),
          parkingSearchTree,
          parkingZones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0),
          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
          ParkingZoneSearchSpec.random
        )

        result match {
          case None => fail()
          case Some(ParkingRanking.RankingAccumulator(taz, parkingType, parkingZone, stallCoord, rankingValue)) =>

            // TAZ B should have been selected because everything is equal except for availability is lower for A
            taz should equal(tazB)

            // these should be consistent with the configuration of this scenario
            parkingType should equal(ParkingType.Public)
            parkingZone.parkingZoneId should equal(1)

            // since availability is 14/18 = 77%, the location of the stall should be
            // close to the destination coordinate (1,1). It should be near within a small bounds (1.0)
            val deviationBounds: Double = 2.0
            math.abs(stallCoord.getX - destinationInMiddle.getX) should be < deviationBounds
            math.abs(stallCoord.getY - destinationInMiddle.getY) should be < deviationBounds

            // rankingFunction is not fully implemented yet as of writing - rjf 20190327
        }
      }
    }
  }

}

object ParkingZoneSearchSpec {

  val random: Random = Random

  trait SimpleParkingAlternatives {

    // in this scenario, there are two TAZs: one at (0,0) and one at (10,10)
    // there are three agent destinations which are being considered
    // the TAZs have a slightly different number of stalls but are otherwise the same

    val sourceData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor
        |A,Public,Block,UltraFast,7,0,unused
        |B,Public,Block,UltraFast,18,0,unused
        |
      """.stripMargin.split("\n").toIterator
    val (parkingZones, parkingSearchTree) = ParkingZoneFileUtils.fromIterator(sourceData)
    val destinationNearTazA = new Coord(1,1) // near taz 1
    val destinationNearTazB = new Coord(9,9) // near taz 2
    val destinationInMiddle = new Coord(5,5) // middle of TAZs
    val tazA = new TAZ(Id.create("A", classOf[TAZ]), new Coord(0,0), 0)
    val tazB = new TAZ(Id.create("B", classOf[TAZ]), new Coord(10,10), 0)
    val tazsInProblem: Seq[TAZ] = Seq(tazA, tazB)
  }

  val mockGeoUtils = new GeoUtils {
    def localCRS: String = "epsg:32631"
  }
}
