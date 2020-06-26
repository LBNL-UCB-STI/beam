package beam.utils.osm

import java.util.Objects

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import com.conveyal.osmlib.{OSM, Way}
import com.typesafe.scalalogging.StrictLogging
import org.jgrapht.Graph
import org.jgrapht.traverse.BreadthFirstIterator

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * @author Dmitry Openkov
  */
object ReduceOSM2 extends StrictLogging {

  val geoUtils = new GeoUtils {
    override def localCRS = "epsg:2808"
  }

  def readMap(filePath: String): OSM = {
    val osm = new OSM(null)
    osm.readFromFile(filePath)
    osm
  }

  def getInfo(osm: OSM): Map[String, Int] = {
    val totalWays = osm.ways.size()

    logger.info(s"count of ways is: $totalWays")
    logger.info(s"count of nodes is: ${osm.nodes.size()}")

    val typeMap = osm.ways
      .values()
      .asScala
      .map(way => Objects.toString(way.getTag("highway")))
      .groupBy(identity)
      .mapValues(_.size)
    val types = typeMap.toList
      .sortBy(_._1)
    logger.info("types = {}", types)
    typeMap
  }

  def writeTagToWay(osm: OSM, outputCSVFilePath: String): Unit = {
    val tagValToWay = mutable.Map.empty[String, Int]
    def plusTagWay(tagVal: String): Unit = {
      tagValToWay.get(tagVal) match {
        case Some(count) => tagValToWay(tagVal) = count + 1
        case None        => tagValToWay(tagVal) = 1
      }
    }

    osm.ways.asScala.foreach {
      case (_, way) =>
        if (way.tags == null) plusTagWay("empty-tag")
        else way.tags.asScala.foreach(osmTag => plusTagWay(s"${osmTag.key}+${osmTag.value}"))
    }

    val csvWriter = new CsvWriter(
      outputCSVFilePath,
      Vector(
        "tag+value",
        "wayCnt",
      )
    )

    tagValToWay.toSeq.sortBy(_._2).foreach {
      case (tag, cnt) => csvWriter.writeRow(IndexedSeq("\"" + tag.replace(',', '.') + "\"", cnt))
    }

    csvWriter.close()
  }

  private def findUnneededWayIds(osm: OSM, wayType: String): mutable.Set[MyEdge] = {
    val g = toGraph(osm)

    var counter = 0
    var singlePathCounter = 0
    var removeCounter = 0

    def testRemoval(myEdge: MyEdge) = {
      counter += 1
      if (counter % 100 == 0)
        logger.info(
          s"Processed $counter edges, to be removed = $removeCounter" +
          s", single path = $singlePathCounter"
        )
      val way: Way = osm.ways.get(myEdge.wayId)
      val source = way.nodes(myEdge.number)
      val target = way.nodes(myEdge.number + 1)
      /*if (g.edgesOf(source).size() == 1 && g.edgesOf(target).size() == 1) {
        val middleNodes = way.nodes.slice(1, way.nodes.length - 1)
        middleNodes.forall(!g.containsVertex(_))
      } else {
        false
      }*/
      g.removeEdge(myEdge)
      val shortWayExists = findNodeInDepth(g, 5, source, target)
      if (shortWayExists) {
        removeCounter += 1
        true
      } else {
        g.addEdge(source, target, myEdge)
        false
      }
    }

    g.edgeSet()
      .asScala
      .filter(myEdge => osm.ways.get(myEdge.wayId).getTag("highway") == wayType)
      .filter((myEdge: MyEdge) => testRemoval(myEdge))
  }

  private def findNodeInDepth(g: Graph[Long, MyEdge], maxDepth: Int, startNode: Long, endNode: Long): Boolean = {
    @tailrec
    def processIter(iter: BreadthFirstIterator[Long, MyEdge], maxDepth: Int): Boolean = {
      if (iter.hasNext) {
        val v = iter.next()
        if (v == endNode) {
          true
        } else if (iter.getDepth(v) <= maxDepth) {
          processIter(iter, maxDepth)
        } else {
          false
        }
      } else {
        false
      }
    }

    val iter = new BreadthFirstIterator(g, startNode)
    iter.next()

    processIter(iter, maxDepth)
  }

  private def toGraph(osm: OSM): Graph[Long, MyEdge] = {
    val g = emptyGraph
    osm.ways.forEach { (wayId, way) =>
      val allNodesOk = way.nodes.forall(osm.nodes.containsKey(_))
      if (allNodesOk) {
        way.nodes.zip(way.nodes.tail).zipWithIndex.foreach {
          case ((source, target), ind) =>
            g.addVertex(source)
            g.addVertex(target)
            g.addEdge(source, target, MyEdge(wayId, ind))
        }
      }
    }
    logger.info(s"Graph num edges: ${g.edgeSet().size()}")
    logger.info(s"Graph num nodes: ${g.vertexSet().size()}")
    g
  }

  import org.jgrapht.graph.builder.GraphTypeBuilder

  private def emptyGraph: Graph[Long, MyEdge] =
    GraphTypeBuilder
      .undirected[Long, MyEdge]
      .allowingMultipleEdges(true)
      .allowingSelfLoops(true)
      .edgeClass(classOf[MyEdge])
      .weighted(false)
      .buildGraph

  def copy(way: Way, withNodes: Array[Long]): Way = {
    val c = new Way
    c.tags = way.tags
    c.nodes = way.nodes
    c
  }

  def generateWays(osm: OSM, waysToRemove: Map[Long, List[Int]]): Map[Long, Way] = {
    var maxWayId = osm.ways.keySet().iterator().asScala.max.toLong
    val untouched = osm.ways.asScala.filter { case (id, _) => !waysToRemove.contains(id) }
    val completelyRemoved = waysToRemove.filter {
      case (id, links) =>
        val way = osm.ways.get(id)
        way.nodes.length - 1 == links.size
    }
    val toGen = waysToRemove.filter { case (id, _) => !completelyRemoved.contains(id) }
    val gen = toGen
      .map {
        case (id, links) =>
          val grouped = groupLinks(links)
          val head :: tail = grouped
          val way = osm.ways.get(id)
          val newWay = copy(way, way.nodes.slice(head.head, head.last + 2))
          val others = tail.map(links => copy(way, way.nodes.slice(links.head, links.last + 2)))
          (id -> newWay) :: others.map { w =>
            maxWayId += 1
            maxWayId -> w
          }
      }
      .flatten
      .toMap
    untouched.map { case (id, way) => id.toLong -> way }.toMap ++ gen
  }

  private def groupLinks(links: List[Int]): List[List[Int]] = {
    val grouped = links
      .foldLeft(List(List.empty[Int]), links.head - 1) {
        case ((acc, prev), link) =>
          if (prev + 1 == link) {
            val head :: tail = acc
            ((link :: head) :: tail) -> link
          } else {
            (List(link) :: acc) -> link
          }
      }
      ._1
    grouped.map(_.reverse).reverse
  }

  def reduceOSM(osm: OSM, toRemove: Set[MyEdge]): OSM = {
//    val selectedNodes = mutable.HashSet.empty[Long]
    val waysToRemove: Map[Long, List[Int]] = toRemove
      .foldLeft(Map.empty[Long, List[Int]]) { (map, myEdge) =>
        val links = map.getOrElse(myEdge.wayId, Nil)
        map + (myEdge.wayId -> (myEdge.number :: links))
      }
      .mapValues(_.sorted)

    val result = new OSM(null)
    result.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    result.setReplicationTimestamp(secondsSinceEpoch)

    val livingWays = generateWays(osm, waysToRemove)
    val livingNodes = livingWays.values.flatMap(_.nodes).toSet
    livingWays.foreach {
      case (wayId, way) =>
        result.writeWay(wayId, way)
    }

    osm.nodes.asScala
      .foreach {
        case (nodeId: java.lang.Long, node) =>
          if (livingNodes.contains(nodeId.toLong))
            result.writeNode(nodeId, node)
      }

    osm.relations.asScala.foreach {
      case (relationId, relation) => result.writeRelation(relationId, relation)
    }

    result
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      println("Usage: program path/to/input/osm wayType path/to/output/osm")
      println(
        "example: program test/input/detroit/r5/detroit-big.osm.pbf residential" +
        " test/input/detroit/r5/detroit-big-reduced.osm.pbf"
      )
      System.exit(1)
    }
    val mapPath = args(0)
    val wayType = args(1)
    val outPath = args(2)

    val osm = readMap(mapPath)
    val types = getInfo(osm)

    if (!types.contains(wayType)) {
      println(s"This file doesn't contain ways of type '$wayType'")
      System.exit(2)
    }

    val canBeRemoved = findUnneededWayIds(osm, wayType)
    println(s"canBeRemoved.size = ${canBeRemoved.size}")
    println(s"canBeRemoved = ${canBeRemoved.size.toDouble / types(wayType)}")

    val reduced = reduceOSM(osm, canBeRemoved.toSet)

    logger.info("reduced map statistic")
    getInfo(reduced)
    reduced.writeToFile(outPath)

  }

  case class MyEdge(wayId: Long, number: Int)
}
