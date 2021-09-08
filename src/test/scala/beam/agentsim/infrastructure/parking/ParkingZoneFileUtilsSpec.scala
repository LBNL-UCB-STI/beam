package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleCategory.{Car, LightDutyTruck, MediumDutyPassenger}
import beam.agentsim.infrastructure.HierarchicalParkingManager
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingType.{Public, Residential, Workplace}
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtilsSpec.PositiveTestData
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Random

class ParkingZoneFileUtilsSpec extends AnyWordSpec with Matchers {
  "ParkingZoneFileUtils" when {
    ".fromIterator" when {
      "positive tests" when {
        "a row contains all valid entries" should {
          "construct a ParkingZone collection and random lookup tree" in new ParkingZoneFileUtilsSpec.PositiveTestData {
            val ParkingZoneFileUtils.ParkingLoadingAccumulator(collection, lookupTree, totalRows, failedRows) =
              ParkingZoneFileUtils.fromIterator[TAZ](validRow, None)
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
                    val foundParkingZoneId: Id[ParkingZoneId] = listOfParkingZones.head
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
                    parkingZone.reservedFor should be(ParkingZone.GlobalReservedFor)
                    parkingZone.timeRestrictions should be(
                      Map(
                        VehicleCategory.Car            -> Range(3600, 43200),
                        VehicleCategory.LightDutyTruck -> Range(48600, 61200)
                      )
                    )
                }
            }
          }
        }
        "a row contains all valid entries, using some empty columns where optional" ignore {
          "construct a ParkingZone collection and random lookup tree" in new ParkingZoneFileUtilsSpec.PositiveTestData {}
        }
        "time restriction" should {
          "be parsed" in new PositiveTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator[TAZ](timeRestrictionData, None)
            result.failedRows should be(0)
            result.totalRows should be(2)
            result.zones(Id.create("parkingZone1", classOf[ParkingZoneId])).timeRestrictions should be(
              Map(
                MediumDutyPassenger -> (18600 until 27000),
                LightDutyTruck      -> (63000 until 86400),
                Car                 -> (0 until 63000)
              )
            )
            result.zones(Id.create("parkingZone2", classOf[ParkingZoneId])).timeRestrictions should be(
              Map(
                LightDutyTruck -> (63000 until 86400),
                Car            -> (0 until 63000)
              )
            )
            println(result.zones)
          }
        }
      }
      "negative tests" when {
        "parking type doesn't exist" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badParkingType, None)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badParkingType)
//          }
        }
        "pricing model doesn't exist" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badPricingModel, None)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badPricingModel)
//          }
        }
        "charging type doesn't exist" ignore {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badChargingType, None)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badChargingType)
//          }
        }
        "non-numeric number of stalls" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badNumStalls, None)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(badNumStalls)
//          }
        }
        "invalid (negative) number of stalls" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(invalidNumStalls, None)
            result.failedRows should equal(1)
          }
//          "throw an error" in new ParkingZoneFileUtilsSpec.NegativeTestData {
//            an [java.io.IOException] should be thrownBy ParkingZoneFileUtils.fromIterator(invalidNumStalls)
//          }
        }
        "non-numeric fee in cents" should {
          "have one failed row" in new ParkingZoneFileUtilsSpec.NegativeTestData {
            val result: ParkingZoneFileUtils.ParkingLoadingAccumulator[TAZ] =
              ParkingZoneFileUtils.fromIterator(badFeeInCents, None)
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
          ParkingZoneFileUtils.fromIterator[Link](linkLevelData, None)
        val tree: ZoneSearchTree[Link] = ParkingZoneFileUtils.createZoneSearchTree(zones.values.toSeq)
        tree should equal(lookupTree)
      }
      "produce correct tree for bigger data" in new PositiveTestData {
        val (zones, lookupTree) =
          ParkingZoneFileUtils
            .fromFile[Link](
              "test/input/sf-light/link-parking.csv.gz",
              new Random(42),
              None,
              0.13
            )
        val tree: ZoneSearchTree[Link] = ParkingZoneFileUtils.createZoneSearchTree(zones.values.toSeq)
        tree should equal(lookupTree)
      }
    }

    "creates a zone search tree" should {
      "produce the right result" in {
        val (parkingZones, _) =
          ParkingZoneFileUtils
            .fromFile[Link](
              "test/test-resources/beam/agentsim/infrastructure/taz-parking-similar-zones.csv",
              new Random(777934L),
              None
            )
        parkingZones should have size 2990
        val collapsed = HierarchicalParkingManager.collapse(parkingZones)
        val collapsedZones205 = collapsed.filter(_._2.geoId.toString == "205")
        collapsedZones205 should have size 11
        val zoneSearchTree = ParkingZoneFileUtils.createZoneSearchTree(collapsed.values.toSeq)
        val subTree = zoneSearchTree(Id.create("205", classOf[Link]))
        subTree.values.map(_.length).sum should be(11)
        subTree(Public) should have length 4
        subTree(Workplace) should have length 3
        subTree(Residential) should have length 4
      }
    }

    "Time restriction parser" when {
      "parses time restriction" should {
        "extract correct values" in {
          val restrictions = ParkingZoneFileUtils.parseTimeRestrictions("Car|1-12;LightDutyTruck|13:30-17")
          restrictions should be(
            Map(VehicleCategory.Car -> Range(3600, 43200), VehicleCategory.LightDutyTruck -> Range(48600, 61200))
          )
        }
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
      s"""taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,timeRestrictions
         |1,Residential,$testPricingModel,$testChargingType,$testNumStalls,$testFeeInCents,car|LightDutyTruck,Car|1-12;LightDutyTruck|13:30-17
      """.stripMargin.split("\n").toIterator

    val validRowWithEmpties: Iterator[String] =
      s"""taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
         |1,Residential,,,$testNumStalls,$testFeeInCents,
      """.stripMargin.split("\n").toIterator

    val linkLevelData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |49577,Residential,FlatFee,level1(2.3|AC),10,0.0,
        |49577,Public,Block,level2(7.2|AC),10,0.0,
        |49577,Workplace,FlatFee,dcfast(50.0|DC),10,0.0,
        |83658,Residential,Block,dcfast(50.0|DC),100,0.0,
        |83658,Residential,FlatFee,NoCharger,100,0.0,
        |83658,Workplace,FlatFee,ultrafast(250.0|DC),100,0.0,
        |83661,Residential,Block,dcfast(50.0|DC),1000,0.0,
        |83661,Public,FlatFee,level2(7.2|AC),1000,0.0,
        |83663,Workplace,FlatFee,level2(7.2|AC),10000,0.0,
        |83663,Workplace,FlatFee,ultrafast(250.0|DC),10000,0.0,
        |
      """.stripMargin.split("\n").toIterator

    val timeRestrictionData: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor,timeRestrictions,parkingZoneId
        |4,Public,FlatFee,NoCharger,10,0,,MediumDutyPassenger|5:10-7:30;LightDutyTruck|17:30-24;Car|0-17:30,parkingZone1
        |4,Public,Block,NoCharger,20,0,,LightDutyTruck|17:30-24;Car|0-17:30,parkingZone2""".stripMargin
        .split("\n")
        .toIterator
  }

  trait NegativeTestData {

    val badParkingType: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Foo,FlatFee,TeslaSuperCharger,7,0,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator

    val badPricingModel: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Workplace,Foo,TeslaSuperCharger,7,0,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator

    val badChargingType: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,Foo,7,0,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator

    val badNumStalls: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,Foo,0,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator

    val invalidNumStalls: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,-1,0,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator

    val badFeeInCents: Iterator[String] =
      """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
        |1,Workplace,FlatFee,TeslaSuperCharger,7,Foo,
        |2,Public,Block,TeslaSuperCharger,18,0,
        |
      """.stripMargin.split("\n").toIterator
  }
}
