package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.parking.ParkingZone
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  *
  * @author Dmitry Openkov
  */
class ParallelParkingManagerUtilSpec extends AnyWordSpecLike with Matchers {

  "ParallelParkingManager" should {

    "split parking zones into clusters" in {

      // 16 square TAZs in a grid
      val tazList: List[(Coord, Double)] = List(
        (new Coord(25, 25), 2500),
        (new Coord(75, 25), 2500),
        (new Coord(25, 75), 2500),
        (new Coord(75, 75), 2500),
        (new Coord(125, 25), 2500),
        (new Coord(175, 25), 2500),
        (new Coord(125, 75), 2500),
        (new Coord(175, 75), 2500),
        (new Coord(25, 125), 2500),
        (new Coord(75, 125), 2500),
        (new Coord(25, 175), 2500),
        (new Coord(75, 175), 2500),
        (new Coord(125, 125), 2500),
        (new Coord(175, 125), 2500),
        (new Coord(125, 175), 2500),
        (new Coord(175, 175), 2500),
      )

      val numZones = List(1, 1, 1, 1, 1, 1, 1, 1, 1, 10, 11, 12, 13, 14, 15, 16)

      val treeMap: TAZTreeMap = ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 200, 200).get
      val parkingZones = ZonalParkingManagerSpec.makeParkingZones(treeMap, numZones)
      val clusters: Vector[ParallelParkingManager.ParkingCluster] =
        ParallelParkingManager.createClusters(treeMap, parkingZones, 4, 42)
      clusters.size should (be(3) or be(4)) //sometimes we got only 3 clusters
    }

    "Put all the TAZ (including empty) to clusters" in {
      val tazList: List[(Coord, Double)] = List(
        (new Coord(25, 25), 2500),
        (new Coord(75, 25), 2500),
        (new Coord(25, 75), 2500),
        (new Coord(75, 75), 2500),
      )

      val numZones = List(1, 2, 3, 4)

      val treeMap: TAZTreeMap = ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 200, 200).get
      val parkingZones = ZonalParkingManagerSpec
        .makeParkingZones(treeMap, numZones)
        .drop(1)
      val clusters: Vector[ParallelParkingManager.ParkingCluster] =
        ParallelParkingManager.createClusters(treeMap, parkingZones, 2, 42)
      val sumTAZesOverAllClusters = clusters.foldLeft(0) {
        case (acc, a) =>
          acc + a.tazes.size
      }
      sumTAZesOverAllClusters should be(treeMap.tazQuadTree.size())
    }

    "Handle empty tazTreeMap" in {
      val treeMap = new TAZTreeMap(new QuadTree[TAZ](0, 0, 0, 0))

      val parkingZones = Array.empty[ParkingZone[TAZ]]
      val clusters: Vector[ParallelParkingManager.ParkingCluster] =
        ParallelParkingManager.createClusters(treeMap, parkingZones, 2, 2)

      clusters.size should be(1)
    }

  }

}
