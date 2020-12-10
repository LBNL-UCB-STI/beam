package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtilsSpec.PositiveTestData
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.scalatest.{Matchers, WordSpec}

import scala.util.Random

class ParkingZoneFileUtilsSpec extends WordSpec with Matchers {
  "ParkingZoneFileUtils" when {
    ".fromIterator" when {
      "positive tests" when {
        "a row contains all valid entries" should {
          "construct a ParkingZone collection and random lookup tree" in new ParkingZoneFileUtilsSpec.PositiveTestData {
            val ParkingZoneFileUtils.ParkingLoadingAccumulator(collection, lookupTree, totalRows, failedRows) =
              ParkingZoneFileUtils.fromIterator[TAZ](validRow)
            totalRows should equal(1)
            failedRows should equal(0)
            lookupTree.get(Id.create("1", classOf[TAZ])) match {
              case None => fail("should contain TAZ with id = 1 in the lookup tree")
              case Some(lookupSubtree) =>
                lookupSubtree.get(ParkingType.Residential) match {
                  case None                     => fail("should contain a 'residential' parking type under TAZ 1 in the lookup tree")
                  case Some(listOfParkingZones) =>
                    // confirm lookup table and collection have correct attributes
                    lookupSubtree.size should equal(1)
                    lookupTree.size should equal(1)
                    listOfParkingZones.length should equal(1)

                    // extract parking zone based on discovered zone id
                    val foundParkingZoneId: Int = listOfParkingZones.head
                    val parkingZone: ParkingZone[TAZ] = collection(foundParkingZoneId)

                    // confirm parking zone has correct attributes
                    parkingZone.parkingZoneId should equal(foundParkingZoneId)
                    parkingZone.maxStalls should equal(testNumStalls)
                    parkingZone.stallsAvailable should equal(testNumStalls)
                    parkingZone.pricingModel match {
                      case None                             => fail("should have found a pricing model in the parking zone")
                      case Some(PricingModel.FlatFee(cost)) => cost should equal(testFeeInDollars)
                      case x                                => fail(s"found $x but expected $testPricingModel pricing model type in the parking zone")
                    }
                    parkingZone.chargingPointType match {
                      case None => fail("should have found a charging point in the parking zone")
                      case Some(chargingPoint) =>
                        chargingPoint should equal(ChargingPointType.TeslaSuperCharger)
                    }

                }
            }
          }
        }
        "a row contains all valid entries, using some empty columns where optional" ignore {
          "construct a ParkingZone collection and random lookup tree" in new ParkingZoneFileUtilsSpec.PositiveTestData {}
        }
      }
      "negative tests" when {
        "parking type doesn't exist" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badParkingType)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badParkingType)
//          }
        }
        "pricing model doesn't exist" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badPricingModel)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badPricingModel)
//          }
        }
        "charging type doesn't exist" ignore {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badChargingType)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badChargingType)
//          }
        }
        "non-numeric number of stalls" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badNumStalls)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badNumStalls)
//          }
        }
        "invalid (negative) number of stalls" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(invalidNumStalls)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(invalidNumStalls)
//          }
        }
        "non-numeric fee in cents" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badFeeInCents)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badFeeInCents)
//          }
        }
      }
    }
    "creates zone search tree" should {
      "produce correct tree" in new PositiveTestData {
        val ParkingZoneFileUtils.ParkingLoadingAccumulator(zones, lookupTree, _, _) =
          ParkingZoneFileUtils.fromIterator[Link](linkLevelData)
        val tree: ZoneSearchTree[Link] = ParkingZoneFileUtils.createZoneSearchTree(zones)
        tree should equal(lookupTree)
      }
      "produce correct tree for bigger data" in new PositiveTestData {
        val (zones, lookupTree) =
          ParkingZoneFileUtils.fromFile[Link]("test/input/sf-light/link-parking.csv.gz", new Random(42), 0.13)
        val tree: ZoneSearchTree[Link] = ParkingZoneFileUtils.createZoneSearchTree(zones)
        tree should equal(lookupTree)
      }
    }
  }
}

object ParkingZoneFileUtilsSpec {

  trait PositiveTestData {
    val testChargingType: ChargingPointType = ChargingPointType.TeslaSuperCharger
    val testNumStalls: Int = 7
    val testFeeInCents: Int = 100
    val testFeeInDollars: Int = testFeeInCents / 100
    val testPricingModel: String = "FlatFee"

    val validRow: Iterator[String] =
      s"""taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
         |1,Residential,$testPricingModel,$testChargingType,$testNumStalls,$testFeeInCents,unused
      """.stripMargin.split("\n").toIterator

    val validRowWithEmpties: Iterator[String] =
      s"""taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
         |1,Residential,,,$testNumStalls,$testFeeInCents,unused
      """.stripMargin.split("\n").toIterator

    val linkLevelData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor
        |49577,Residential,FlatFee,level1(2.3|AC),10,0.0,
        |49577,Public,Block,level2(7.2|AC),10,0.0,
        |49577,Workplace,FlatFee,dcfast(50.0|DC),10,0.0,
        |83658,Residential,Block,dcfast(50.0|DC),100,0.0,
        |83658,Residential,FlatFee,NoCharger,100,0.0,
        |83658,Workplace,FlatFee,ultrafast(250.0|DC),100,0.0,
        |83661,Residential,Block,dcfast(50.0|DC),1000,0.0,
        |83661,Public,FlatFee,level2(7.2|AC),1000,0.0,
        |83661,Public,FlatFee,level2(7.2|AC),1000,0.0,
        |83663,Workplace,FlatFee,level2(7.2|AC),10000,0.0,
        |83663,Workplace,FlatFee,ultrafast(250.0|DC),10000,0.0,
        |
      """.stripMargin.split("\n").toIterator
  }

  trait NegativeTestData {

    val badParkingType: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Foo,FlatFee,TeslaSuperCharger,7,0,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator

    val badPricingModel: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Workplace,Foo,TeslaSuperCharger,7,0,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator

    val badChargingType: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,Foo,7,0,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator

    val badNumStalls: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,Foo,0,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator

    val invalidNumStalls: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,-1,0,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator

    val badFeeInCents: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,7,Foo,unused
        |2,Public,Block,TeslaSuperCharger,18,0,unused
        |
      """.stripMargin.split("\n").toIterator
  }
}
