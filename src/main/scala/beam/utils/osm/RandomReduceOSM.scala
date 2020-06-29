package beam.utils.osm

import java.util.Objects

import beam.sim.common.GeoUtils
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._
import scala.util.{Random, Try}

/**
  * @author Dmitry Openkov
  */
object RandomReduceOSM extends StrictLogging {

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

  def reduceOSM(osm: OSM, wayTypes: Set[String], ratio: Double, random: Random): OSM = {
    val result = new OSM(null)
    result.writeBegin()

    val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
    result.setReplicationTimestamp(secondsSinceEpoch)

    val livingWays = osm.ways.asScala.filter {
      case (_, way) =>
        !wayTypes.contains(way.getTag("highway")) || random.nextDouble() < ratio
    }
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
    if (args.length != 4) {
      println("Usage: program path/to/input/osm wayTypes ration path/to/output/osm")
      println(
        "Ratio: how many ways of type 'wayTypes' should be left in the output map" +
        ", residential 0.3 means 30% of ways will be written to the output map"
      )
      println(
        "example: program test/input/detroit/r5/detroit-big.osm.pbf residential 0.4" +
        " test/input/detroit/r5/detroit-big-reduced.osm.pbf"
      )
      System.exit(1)
    }
    val mapPath = args(0)
    val wayTypes = args(1)
    val ratioArg = args(2)
    val outPath = args(3)

    val ratioT = Try(ratioArg.toDouble)
    if (ratioT.isFailure) {
      println(s"Cannot parse a number from $ratioArg")
      System.exit(1)
    }
    val ratio = ratioT.get
    if (ratio < 0 || ratio > 1) {
      println(s"Ration must be in the range 0.0 to 1.0")
      System.exit(1)
    }

    val osm = readMap(mapPath)
    val types = getInfo(osm)

    val wt = wayTypes.split(',').toSet
    if (!wt.forall(types.contains)) {
      println(s"This file doesn't contain ways of all types '$wayTypes'")
      System.exit(2)
    }

    val reduced = reduceOSM(osm, wt, ratio, new Random(11788))

    logger.info("reduced map statistic")
    getInfo(reduced)
    reduced.writeToFile(outPath)

  }

  case class MyEdge(wayId: Long, number: Int)
}
