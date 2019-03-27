package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging.ChargingInquiry
import beam.agentsim.infrastructure.taz._
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.{Matchers, WordSpec}

class ParkingZoneSearchSpec extends WordSpec with Matchers {
  "A ParkingZoneSearch" when {
    "searched for something when there are no alternatives" should {
      "result in None" in {
        val tree: ParkingZoneSearch.ZoneSearch = Map()
        val zones: Array[ParkingZone] = Array()

        val result = ParkingZoneSearch.find(
          Option.empty[ChargingInquiry],
          Seq(TAZ.DefaultTAZ),
          Seq(ParkingType.Public),
          tree,
          zones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0)
        )

        result should be (None)
      }
    }
    "search for options that do exist" should {
      "receive all of those options" in new ParkingZoneSearchSpec.SmallProblem {
        val result: Option[ParkingRanking.RankingAccumulator] = ParkingZoneSearch.find(
          Option.empty[ChargingInquiry],
          tazsInProblem,
          Seq(ParkingType.Public),
          smallTree,
          smallZones,
          ParkingRanking.rankingFunction(parkingDuration = 100.0)
        )

        result match {
          case None => fail()
          case Some(ParkingRanking.RankingAccumulator(taz, parkingType, parkingZone, rankingValue, availability)) =>
            taz should equal (taz2)
            parkingType should equal (ParkingType.Public)
            parkingZone.stallsAvailable should equal (18)
            parkingZone.maxStalls should equal (18)
            parkingZone.parkingZoneId should equal (1)
        }
      }
    }
  }

}

object ParkingZoneSearchSpec {
  trait SmallProblem {
    val sourceData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,UltraFast,7,0,unused
        |2,Public,Block,UltraFast,18,0,unused
        |
      """.stripMargin.split("\n").toIterator
    val (smallZones, smallTree) = ParkingZoneFileUtils.fromIterator(sourceData)
    val taz1 = new TAZ(Id.create("1", classOf[TAZ]), new Coord(), 0)
    val taz2 = new TAZ(Id.create("2", classOf[TAZ]), new Coord(), 0)
    val tazsInProblem: Seq[TAZ] = Seq(taz1, taz2)
  }
}
