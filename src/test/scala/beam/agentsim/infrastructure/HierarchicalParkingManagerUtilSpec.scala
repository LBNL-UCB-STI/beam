package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType.DC
import beam.agentsim.infrastructure.parking.ParkingType.Residential
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtilsSpec.PositiveTestData
import beam.agentsim.infrastructure.parking.{ParkingZone, ParkingZoneFileUtils, ParkingZoneId}
import org.matsim.api.core.v01.Id
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
  * @author Dmitry Openkov
  */
class HierarchicalParkingManagerUtilSpec extends AnyWordSpec with Matchers {
  "HierarchicalParkingManager" when {
    "creates taz parking zones out of link parking zones" should {
      "produce correct zones" in new PositiveTestData {

        val ParkingZoneFileUtils.ParkingLoadingAccumulator(linkZones, _, _, _) =
          ParkingZoneFileUtils.fromIterator(parkingWithLocation, None, None)

        private val (tazZones, linkZoneToTazZoneMap) =
          HierarchicalParkingManager.convertToTazParkingZones(linkZones.toMap)

        println(tazZones.values.mkString("Array(", ", ", ")"))
        println(linkZoneToTazZoneMap.mkString("Array(", ", ", ")"))
        tazZones.size should be(9)
        val joinZone: Option[ParkingZone] = tazZones.values.find { zone =>
          zone.tazId.toString == "100026" && zone.parkingType == Residential && zone.chargingPointType.contains(
            CustomChargingPoint("dcfast", 50.0, DC)
          )
        }

        joinZone shouldBe defined
        joinZone.get.maxStalls should be(1100)
        tazZones.count(_._2.tazId.toString == "100100") should be(2)

        val zoneOfTaz100100 = tazZones.values
          .filter(zone =>
            zone.tazId.toString == "100100"
            && zone.chargingPointType.exists(_.toString.startsWith("ultrafast"))
          )
          .loneElement
        withClue("Two similar link zones should be collapsed to a single one") {
          zoneOfTaz100100.maxStalls should be(20000)
          zoneOfTaz100100.stallsAvailable should be(20000)
        }
        withClue("For each link zone should be created an entry in linkZoneToTazZoneMap") {
          linkZoneToTazZoneMap should have size 11
        }
        withClue("link zones 4, 7 and 10, 11 should be collapsed into single taz zones") {
          val groupedByTazZone: IndexedSeq[Set[Id[ParkingZoneId]]] =
            linkZoneToTazZoneMap.groupBy { case (_, tazZoneId) => tazZoneId }.values.map(_.keySet).toIndexedSeq
          val (singles, collapsed) = groupedByTazZone.partition(_.size == 1)
          singles should have size 7
          collapsed.map(_.map(_.toString)) should contain theSameElementsAs Seq(Set("4", "7"), Set("10", "11"))
//>>>>>>> develop
        }
      }
    }
  }
}
