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
import scala.util.Random

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
      }

      "produce correct zones on a bigger data" ignore { //this is a bigger test for sf-light
        val network = NetworkUtilsExtensions.readNetwork("output/sf-light/sf-light-25k/outputNetwork.xml.gz")

        val tazTreeMap = TAZTreeMap.fromCsv("test/input/sf-light/taz-centers.csv.gz")
        val linkToTAZMapping: Map[Link, TAZ] = LinkLevelOperations.getLinkToTazMapping(network, tazTreeMap)
        val (linkZones, lookupTree) =
          ParkingZoneFileUtils.fromFile[Link]("test/input/sf-light/link-parking.csv.gz", new Random(42), 1.0)

        val ltzIds: Map[Id[Link], Id[TAZ]] = linkToTAZMapping.map {
          case (link, taz) => link.getId -> taz.tazId
        }
        val tuple = HierarchicalParkingManager.convertToTazParkingZones(linkZones.toArray, ltzIds)
        val tazZones: Array[ParkingZone[TAZ]] = tuple._1
        val linkToTaz: Map[Int, Int] = tuple._2
        val tree = ParkingZoneFileUtils.createZoneSearchTree(tazZones)
        val myTazId: Id[TAZ] = Id.create(100026, classOf[TAZ])
        val myZones = tazZones.filter(_.geoId == myTazId)
        val myZones2 = tree(myTazId).mapValues(_.map(tazZones))

        println(myZones)
        println(myZones2)

        println(tazZones.length)
        println(tree.size)

        tazTreeMap.getTAZs.map { taz =>

        }
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
