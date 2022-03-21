package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType.DC
import beam.agentsim.infrastructure.parking.ParkingType.Residential
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtilsSpec.PositiveTestData
import beam.agentsim.infrastructure.parking.{LinkLevelOperations, ParkingZone, ParkingZoneFileUtils, ParkingZoneId}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.HashMap
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class HierarchicalParkingManagerUtilSpec extends AnyWordSpec with Matchers {
  "HierarchicalParkingManager" when {
    "creates taz parking zones out of link parking zones" should {
      "produce correct zones" in new PositiveTestData {

        val ParkingZoneFileUtils.ParkingLoadingAccumulator(linkZones, _, _, _) =
          ParkingZoneFileUtils.fromIterator[Link](linkLevelData, None, None)

        val linkToTazMapping: Map[Id[Link], Id[TAZ]] = HashMap(
          Id.createLinkId(49577) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83658) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83661) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83663) -> Id.create(100100, classOf[TAZ]),
          Id.createLinkId(83664) -> Id.create(100100, classOf[TAZ])
        )

        private val (tazZones, linkZoneToTazZoneMap) =
          HierarchicalParkingManager.convertToTazParkingZones(linkZones.toMap, linkToTazMapping)

        println(tazZones.mkString("Array(", ", ", ")"))
        println(linkZoneToTazZoneMap.mkString("Array(", ", ", ")"))
        tazZones.size should be(9)
        val joinZone: Option[ParkingZone[TAZ]] = tazZones.values.find { zone =>
          zone.geoId.toString == "100026" && zone.parkingType == Residential && zone.chargingPointType.contains(
            CustomChargingPoint("dcfast", 50.0, DC)
          )
        }

        joinZone shouldBe defined
        joinZone.get.maxStalls should be(1100)
        tazZones.count(_._2.geoId.toString == "100100") should be(2)

        val zoneOfTaz100100 = tazZones.values
          .filter(zone =>
            zone.geoId.toString == "100100"
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
          collapsed.map(_.map(_.toString)) should contain theSameElementsAs (Seq(Set("4", "7"), Set("10", "11")))
        }
      }
    }
    "creates taz link quad tree mapping" should {
      "correct quad tree" in {
        val network =
          NetworkUtilsExtensions.readNetwork("test/test-resources/beam/physsim/beamville-network-output.xml")
        val totalNumberOfLinks = network.getLinks.size()
        val tazTreeMap = TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
        val totalNumberOfTAZes = tazTreeMap.tazQuadTree.size()

        val linkToTAZMapping: Map[Link, TAZ] = LinkLevelOperations.getLinkToTazMapping(network, tazTreeMap)
        linkToTAZMapping.size should be(totalNumberOfLinks)
        linkToTAZMapping.values.toSet.size should be(totalNumberOfTAZes)

        val tazToLinkQuads = HierarchicalParkingManager.createTazLinkQuadTreeMapping(linkToTAZMapping)
        tazToLinkQuads.values.map(_.size()).sum should be(totalNumberOfLinks)

        //get certain TAZ links and validate that it contains the right ones.
        val myTazId: Id[TAZ] = Id.create(1, classOf[TAZ])
        val myZonesTree = tazToLinkQuads(myTazId)
        myZonesTree.values().size() should be(130)
        myZonesTree.values().asScala.map(_.getId.toString).toList should contain allElementsOf List(
          "2",
          "3",
          "6",
          "7",
          "322",
          "323"
        )
      }
    }
  }
}
