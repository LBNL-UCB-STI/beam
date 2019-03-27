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
      "result in None" in {
        val tree: ParkingZoneSearch.ZoneSearch = Map()
        val zones: Array[ParkingZone] = Array()

        val result = ParkingZoneSearch.find(
          new Coord(0,0),
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
      "find a spot in the nearest TAZ with full availability which places the stall exactly at the driver's destination" in new ParkingZoneSearchSpec.SmallProblem {
        val result: Option[ParkingRanking.RankingAccumulator] = ParkingZoneSearch.find(
          destinationCoord2,
          Option.empty[ChargingInquiryData[String,String]],
          tazsInProblem,
          Seq(ParkingType.Public),
          smallTree,
          smallZones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0),
          ParkingZoneSearchSpec.mockGeoUtils.distUTMInMeters,
          ParkingZoneSearchSpec.random
        )

        result match {
          case None => fail()
          case Some(ParkingRanking.RankingAccumulator(taz, parkingType, parkingZone, stallCoord, rankingValue)) =>
            taz should equal(taz2)
            parkingType should equal(ParkingType.Public)
            parkingZone.stallsAvailable should equal(18)
            parkingZone.maxStalls should equal(18)
            parkingZone.parkingZoneId should equal(1)
            // since availability is 18/18 = 1.0, sample location should equal destination coordinate
            stallCoord should equal (destinationCoord2)
        }
      }
    }
  }

}

object ParkingZoneSearchSpec {

  val random: Random = Random

  trait SmallProblem {

    val sourceData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,UltraFast,7,0,unused
        |2,Public,Block,UltraFast,18,0,unused
        |
      """.stripMargin.split("\n").toIterator
    val (smallZones, smallTree) = ParkingZoneFileUtils.fromIterator(sourceData)
    val destinationCoord1 = new Coord(1,1) // near taz 1
    val destinationCoord2 = new Coord(9,9) // near taz 2
    val taz1 = new TAZ(Id.create("1", classOf[TAZ]), new Coord(0,0), 0)
    val taz2 = new TAZ(Id.create("2", classOf[TAZ]), new Coord(10,10), 0)
    val tazsInProblem: Seq[TAZ] = Seq(taz1, taz2)
  }

  val mockGeoUtils = new GeoUtils {
    def localCRS: String = "epsg:32631"
  }
}
