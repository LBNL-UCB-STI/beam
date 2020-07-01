package beam.utils.osm

import java.lang

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import com.conveyal.osmlib.{Node, OSM, Way}
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.util.Random

object OSMMapUtils {

  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:2808"
  }

  def readMap(filePath: String): OSM = {
    val osm = new OSM(null)
    osm.readFromFile(filePath)
    osm
  }

  def showInfo(osm: OSM): Unit = {
    println(s"count of nodes is: ${osm.nodes.size()}")
    println(s"count of ways is: ${osm.ways.size()}")
    println(s"count of relations is: ${osm.relations.size()}")

    val wayTypes = mutable.HashMap.empty[String, Long]
    def countHighway(highwayType: String): Unit = {
      wayTypes.get(highwayType) match {
        case Some(count) => wayTypes(highwayType) = count + 1
        case None        => wayTypes(highwayType) = 1
      }
    }

    osm.ways.asScala.foreach {
      case (_, way) if way.tags != null =>
        way.tags.asScala.find(tag => tag.key == "highway") match {
          case Some(tag) => countHighway(tag.value)
          case _         => countHighway("other")
        }
      case _ => countHighway("empty")
    }

    wayTypes.toSeq.sortBy { case (_, count) => count }.reverse.take(5).foreach {
      case (wayType, count) => println(s"$wayType : $count")
    }
  }

  def getNodeCoord(osm: OSM, nodeId: Long): Coord = {
    val node: Node = osm.nodes.get(nodeId)
    new Coord(node.getLon, node.getLat)
  }

  def getDistance(osm: OSM, nodeId1: Long, nodeId2: Long): Double = {
    val coord1 = getNodeCoord(osm, nodeId1)
    val coord2 = getNodeCoord(osm, nodeId2)
    geo.distLatLon2Meters(coord1, coord2)
  }

  def getWayLen(osm: OSM, way: Way): Double = {
    val (_, distance) = way.nodes.tail.foldLeft((way.nodes.head, 0.0)) {
      case ((node1, totalDistance), node2) => (node2, getDistance(osm, node1, node2) + totalDistance)
    }

    distance
  }

  def writeOSMInfo(osm: OSM, outputCSVFilePath: String): Unit = {
    val csvWriter = new CsvWriter(
      outputCSVFilePath,
      Vector(
        "wayId",
        "nodesCount",
        "wayLength"
      )
    )

    osm.ways.asScala.foreach {
      case (wayId, way) => csvWriter.writeRow(IndexedSeq(wayId, way.nodes.length, getWayLen(osm, way)))
    }

    csvWriter.close()
  }

  class Progress(val multiplicator: Int, val maxProgress: Int) {
    private val onePiece = maxProgress / multiplicator
    println(s"1/$multiplicator is $onePiece ways")
    var processed = 0
    var pieces = 0

    def plusOne(): Unit = {
      processed += 1
      if (processed >= onePiece) {
        processed = 0
        pieces += 1
        println(s"$pieces/$multiplicator done")
      }
    }
  }

  def removeShortWays(
    osm: OSM,
    highwayTypeToProcess: Set[String],
    minDistanceInMeters: Double,
    maxIterations: Int = 5
  ): OSM = {
    var nextNodeId: Long = osm.nodes.asScala.keys.max + 1
    class NodeGroup(val nodesIds: Set[Long]) {
      val nodes: Map[lang.Long, Node] = osm.nodes.asScala.filter { case (nodeId, _) => nodesIds.contains(nodeId) }.toMap

      def getCenterNode: Node = {
        val centerLat: Double = {
          val minLat: Double = nodes.map(_._2.getLat).min
          val maxLat: Double = nodes.map(_._2.getLat).max
          (minLat + maxLat) / 2.0
        }

        val centerLon: Double = {
          val minLon: Double = nodes.map(_._2.getLon).min
          val maxLon: Double = nodes.map(_._2.getLon).max
          (minLon + maxLon) / 2.0
        }

        new Node(centerLat, centerLon)
      }

      val centerNodeId: Long = {
        val newId = nextNodeId
        nextNodeId += 1
        newId
      }
    }

    def waySelected(way: Way): Boolean = {
      way.tags != null &&
      way.tags.asScala.exists(tag => tag.key == "highway" && highwayTypeToProcess.contains(tag.value)) &&
      getWayLen(osm, way) < minDistanceInMeters
    }

    val shortWays = osm.ways.asScala.filter { case (_, way) => waySelected(way) }.toArray

    println(s"got ${shortWays.length} ways with len less than $minDistanceInMeters")

    val waysToRemove = mutable.HashMap.empty[Long, Way]
    val affectedNodes = mutable.HashMap.empty[Long, NodeGroup]
    val groupedNodes = mutable.ListBuffer.empty[NodeGroup]

    print("processing short ways to find all ways to remove. ")
    val progress = new Progress(7, shortWays.length)

    shortWays.foreach {
      case (wayId, way) if !way.nodes.exists(affectedNodes.contains) =>
        waysToRemove(wayId) = way
        val group = new NodeGroup(way.nodes.toSet)
        groupedNodes += group
        way.nodes.foreach(nodeId => affectedNodes(nodeId) = group)
        progress.plusOne()
      case _ => progress.plusOne()
    }

    val copy = new OSM(null)
    copy.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    copy.setReplicationTimestamp(secondsSinceEpoch)

    print("processing all ways. ")
    val progress2 = new Progress(13, osm.ways.size())

    osm.ways.asScala.foreach {
      case (wayId, way) if !waysToRemove.contains(wayId) =>
        if (way.nodes.exists(affectedNodes.contains)) {
          val newNodes = mutable.ListBuffer.empty[Long]
          way.nodes
            .map { nodeId =>
              affectedNodes.get(nodeId) match {
                case None        => nodeId
                case Some(group) => group.centerNodeId
              }
            }
            .foreach { nodeId =>
              newNodes.lastOption match {
                case None                             => newNodes += nodeId
                case Some(lastId) if nodeId != lastId => newNodes += nodeId
                case _                                =>
              }
            }
          way.nodes = newNodes.toArray
          copy.writeWay(wayId, way)
        } else {
          copy.writeWay(wayId, way)
        }
        progress2.plusOne()
      case _ => progress2.plusOne()
    }

    osm.nodes.asScala.foreach {
      case (nodeId, node) if !affectedNodes.contains(nodeId) => copy.writeNode(nodeId, node)
      case _                                                 =>
    }

    groupedNodes.foreach { nodeGroup =>
      copy.writeNode(nodeGroup.centerNodeId, nodeGroup.getCenterNode)
    }

    osm.relations.asScala.foreach {
      case (relationId, relation) => copy.writeRelation(relationId, relation)
    }

    copy
  }

  def copyWithChosenWays(osm: OSM, isChosen: Way => Boolean): OSM = {
    val selectedNodes = mutable.HashSet.empty[Long]

    val copy = new OSM(null)
    copy.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    copy.setReplicationTimestamp(secondsSinceEpoch)

    osm.ways.asScala.foreach {
      case (wayId, way) if isChosen(way) =>
        copy.writeWay(wayId, way)
        way.nodes.foreach(nodeId => selectedNodes.add(nodeId))
      case _ =>
    }

    osm.nodes.asScala.foreach {
      case (nodeId, node) if selectedNodes.contains(nodeId) => copy.writeNode(nodeId, node)
      case _                                                =>
    }

    osm.relations.asScala.foreach {
      case (relationId, relation) => copy.writeRelation(relationId, relation)
    }

    copy
  }

  def main(args: Array[String]): Unit = {

    def printInfo(filePath: String): Unit = {
      println(s"map: $filePath")
      val map = readMap(filePath)
      showInfo(map)
    }

    printInfo("/mnt/data/work/beam/newyork-scenario-data/newyork-big-filtered.osm.pbf")
    printInfo("/mnt/data/work/beam/newyork-scenario-data/newyork-big-original.osm.pbf")
    printInfo("/mnt/data/work/beam/newyork-scenario-data/newyork-big-without-residential.osm.pbf")

//    printInfo("/mnt/data/work/beam/detroit-scenario-data/detroit-big-cut-0.8.osm.pbf")
//    printInfo("/mnt/data/work/beam/detroit-scenario-data/detroit-tiny.osm.pbf")
//    printInfo("/mnt/data/work/beam/newyork-scenario-data/newyork-simplified.osm.pbf")
//    printInfo("/mnt/data/work/beam/newyork-scenario-data/newyork-filtered.osm.pbf")

//    val mapPath = "/mnt/data/work/beam/detroit-scenario-data/detroit-tiny.osm.pbf"
//    val cutMapPath = "/mnt/data/work/beam/detroit-scenario-data/detroit-tiny-simplified.osm.pbf"
//    val map = readMap(mapPath)
//    showInfo(map)
//
//    val copy = removeShortWays(map, "residential", 30.0)
//    showInfo(copy)
//    copy.writeToFile(cutMapPath)

//    writeOSMInfo(map, "/home/nikolay/.jupyter-files/detroit.tiny.stats.csv")
//    val random = new Random()
//
//    // highway=residential
//    def choosePercentOfResidentialAndFootway(way: Way): Boolean = {
//      if (way.tags == null || !way.tags.asScala.exists(
//            tag => tag.key == "highway" && (tag.value == "residential" || tag.value == "footway")
//          )) {
//        true
//      } else {
//        random.nextDouble() < 0.6
//      }
//    }
//
//    val map_cut = copyWithChosenWays(map, choosePercentOfResidentialAndFootway)
//    showInfo(map_cut)
//    map_cut.writeToFile(cutMapPath)
  }
}
