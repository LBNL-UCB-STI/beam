package beam.utils.osm

import java.util.Objects

import beam.sim.common.GeoUtils
import beam.utils.csv.CsvWriter
import com.conveyal.osmlib.{OSM, Way}
import com.typesafe.scalalogging.StrictLogging
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.{Graph, GraphPath}
import org.matsim.api.core.v01.Coord

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
    val types = typeMap
      .toList
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

    def testRemoval(myEdge: MyEdge, lengthMap: Map[MyEdge, Double]): Boolean = {
      counter += 1
      if (counter % 100 == 0) logger.info(s"Processed $counter edges, to be removed = $removeCounter" +
        s", single path = $singlePathCounter")
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
      val dijkstraAlg = new DijkstraShortestPath[Long, MyEdge](g)
      val iPaths = dijkstraAlg.getPaths(source)
      val path: GraphPath[Long, MyEdge] = iPaths.getPath(target)
      val pathLength: Double = if (path != null) path.getEdgeList.asScala.map(lengthMap).sum else {
        singlePathCounter += 1
        Double.MaxValue
      }
      if (pathLength < 10 * lengthMap(myEdge)) {
        removeCounter += 1
        true
      } else {
        g.addEdge(source, target, myEdge)
        false
      }
    }

    def length(way: Way) = {
      val n1 = osm.nodes.get(way.nodes.head)
      val n2 = osm.nodes.get(way.nodes.last)
      if (n1 != null && n2 != null) {
        geoUtils.distLatLon2Meters(new Coord(n1.getLat, n1.getLon), new Coord(n2.getLat, n2.getLon))
      } else {
        Double.MaxValue
      }
    }

    val lengthMap: Map[MyEdge, Double] = g
      .edgeSet()
      .asScala
      .map { myEdge =>
        val way = osm.ways.get(myEdge.wayId)
        myEdge -> length(way)
      }
      .toMap
    g.edgeSet().asScala
      .filter(myEdge => osm.ways.get(myEdge.wayId).getTag("highway") == wayType)
      .filter(testRemoval(_, lengthMap))
  }

  private def toGraph(osm: OSM): Graph[Long, MyEdge] = {
    val g = emptyGraph
    osm.ways.forEach { (wayId, way) =>
      val allNodesOk = way.nodes.forall(osm.nodes.containsKey(_))
      if (allNodesOk) {
        way.nodes.zip(way.nodes.tail).zipWithIndex.foreach { case ((source, target), ind) =>
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

  def reduceOSM(osm: OSM, toRemove: List[Long]): OSM = {
    val selectedNodes = mutable.HashSet.empty[Long]

    val reduced = new OSM(null)
    reduced.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    reduced.setReplicationTimestamp(secondsSinceEpoch)

    val livingWays = osm.ways.asScala.filter {
      case (wayId, _) => !toRemove.contains(wayId)
    }
    val livingNodes = livingWays.values.flatMap(_.nodes).toSet
    livingWays.foreach {
      case (wayId, way) =>
        reduced.writeWay(wayId, way)
    }

    osm.nodes.asScala
      .foreach {
        case (nodeId, node) if livingNodes.contains(nodeId) => reduced.writeNode(nodeId, node)
      }

    osm.relations.asScala.foreach {
      case (relationId, relation) => reduced.writeRelation(relationId, relation)
    }

    reduced
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      println("Usage: program path/to/input/osm wayType path/to/output/osm")
      println("example: program test/input/detroit/r5/detroit-big.osm.pbf residential" +
        " test/input/detroit/r5/detroit-big-reduced.osm.pbf")
      System.exit(1)
    }
    val mapPath = args(0)
    val wayType = args(1)
    val outPath = args(2)

    val map = readMap(mapPath)
    val types = getInfo(map)

    if(!types.contains(wayType)) {
      println(s"This file doesn't contain ways of type '$wayType'")
      System.exit(2)
    }

    val canBeRemoved = findUnneededWayIds(map, wayType)
    println(s"canBeRemoved.size = ${canBeRemoved.size}")
    println(s"canBeRemoved = ${canBeRemoved.size.toDouble / types(wayType)}")


  }

  case class MyEdge(wayId: Long, number: Int)
}
