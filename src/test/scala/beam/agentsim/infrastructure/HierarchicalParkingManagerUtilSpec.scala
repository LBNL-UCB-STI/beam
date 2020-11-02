package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.charging.ElectricCurrentType.DC
import beam.agentsim.infrastructure.parking.ParkingType.Residential
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtilsSpec.PositiveTestData
import beam.agentsim.infrastructure.parking.{LinkLevelOperations, ParkingZone, ParkingZoneFileUtils}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.scalatest.{Matchers, WordSpec}

import scala.collection.immutable.HashMap
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

/**
  * @author Dmitry Openkov
  */
class HierarchicalParkingManagerUtilSpec extends WordSpec with Matchers {
  "HierarchicalParkingManager" when {
    "creates taz parking zones out of link parking zones" should {
      "produce correct zones" in new PositiveTestData {
        val ParkingZoneFileUtils.ParkingLoadingAccumulator(linkZones, linkTree, totalRows, failedRows) =
          ParkingZoneFileUtils.fromIterator[Link](linkLevelData)
        val linkToTazMapping = HashMap(
          Id.createLinkId(49577) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83658) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83661) -> Id.create(100026, classOf[TAZ]),
          Id.createLinkId(83663) -> Id.create(100100, classOf[TAZ]),
        )
        private val (tazZones, linkToTaz) =
          HierarchicalParkingManager.convertToTazParkingZones(linkZones.toArray, linkToTazMapping)

        println(tazZones.mkString("Array(", ", ", ")"))
        tazZones.length should be(9)
        val joinZone = tazZones.find(
          zone =>
            zone.geoId.toString == "100026" && zone.parkingType == Residential && zone.chargingPointType.contains(
              CustomChargingPoint("dcfast", 50.0, DC)
          )
        )

        joinZone shouldBe defined
        joinZone.get.maxStalls should be(1100)
        tazZones.count(_.geoId.toString == "100100") should be(2)

        /*        val res =
          Array(
            ParkingZone(parkingZoneId = 0, numStalls = 1100, chargingType = dcfast(50.0 | DC), pricingModel = Block),
            ParkingZone(parkingZoneId = 1, numStalls = 10, chargingType = level1(2.3 | AC), pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 2, numStalls = 100, chargingType = None, pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 3, numStalls = 100, chargingType = ultrafast(250.0 | DC), pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 4, numStalls = 10, chargingType = level2(7.2 | AC), pricingModel = Block),
            ParkingZone(parkingZoneId = 5, numStalls = 10, chargingType = dcfast(50.0 | DC), pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 6, numStalls = 2000, chargingType = level2(7.2 | AC), pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 7, numStalls = 10000, chargingType = ultrafast(250.0 | DC), pricingModel = FlatFee),
            ParkingZone(parkingZoneId = 8, numStalls = 10000, chargingType = level2(7.2 | AC), pricingModel = FlatFee)
          )*/
      }

    }
    "creates taz link quad tree mapping" should {
      "correct quad tree" ignore {
        //todo implement test without loading the network
        val network = NetworkUtilsExtensions.readNetwork("output/sf-light/sf-light-25k/outputNetwork.xml.gz")
        val tazTreeMap = TAZTreeMap.fromCsv("test/input/sf-light/taz-centers.csv.gz")
        val linkToTAZMapping: Map[Link, TAZ] = LinkLevelOperations.getLinkToTazMapping(network, tazTreeMap)
        val tazToLinkQuads = HierarchicalParkingManager.createTazLinkQuadTreeMapping(linkToTAZMapping)
        val myTazId: Id[TAZ] = Id.create(100026, classOf[TAZ])
        val myZonesTree = tazToLinkQuads(myTazId)
        myZonesTree.values().size() should be(6)
        myZonesTree.values().asScala.map(_.getId.toString).toList should contain allElementsOf (List(
          "49577",
          "49576",
          "83660",
          "83661",
          "83658",
          "83659"
        ))

      }

    }
  }
}
