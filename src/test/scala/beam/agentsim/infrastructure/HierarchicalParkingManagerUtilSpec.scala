package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.taz.TAZTreeMap
import org.matsim.api.core.v01.Coord
import org.scalatest.{Matchers, WordSpecLike}

/**
  *
  * @author Dmitry Openkov
  */
class HierarchicalParkingManagerUtilSpec extends WordSpecLike with Matchers {

  "HierarchicalParkingManager" should {

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
      val clusters: Vector[HierarchicalParkingManager.ParkingCluster] =
        HierarchicalParkingManager.createClusters(treeMap, parkingZones, 4)
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
      val parkingZones = ZonalParkingManagerSpec.makeParkingZones(treeMap, numZones)
          .drop(1)
      val clusters: Vector[HierarchicalParkingManager.ParkingCluster] =
        HierarchicalParkingManager.createClusters(treeMap, parkingZones, 2)
      val sumTAZesOverAllClusters = clusters.foldLeft(0) { case (acc, a) =>
        acc + a.tazes.size
      }
      sumTAZesOverAllClusters should (be(treeMap.tazQuadTree.size()))
    }

  }

}
