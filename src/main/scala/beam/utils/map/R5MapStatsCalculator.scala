package beam.utils.map

import com.conveyal.osmlib.{OSM, Way}
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.streets.EdgeStore.EdgeFlag
import com.conveyal.r5.transit.TransportNetwork

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

object R5MapStatsCalculator {

  def main(args: Array[String]): Unit = {
    //    LoggerFactory.getLogger("com.conveyal").asInstanceOf[Logger].setLevel(Level.INFO)
    //val pathToOsm = args(0)
    val pathToOsm = "/mnt/data/work/beam/beam-new-york/test/input/newyork/r5-prod/newyork-14-counties.osm.pbf"

    println(s"OSM file: $pathToOsm")
    val tn = TransportNetwork.fromFiles(
      pathToOsm,
      new java.util.ArrayList[String](),
      TNBuilderConfig.defaultConfig,
      true,
      false
    )
    val cursor = tn.streetLayer.edgeStore.getCursor
    val it = new Iterator[EdgeStore#Edge] {
      override def hasNext: Boolean = {
        val movedToNext = cursor.advance()
        if (movedToNext) {
          cursor.retreat()
          true
        } else false
      }
      override def next(): EdgeStore#Edge = {
        cursor.advance()
        cursor
      }
    }

    val flagToCnt = it
      .foldLeft(Map[EdgeFlag, Int]()) {
        case (acc, c) =>
          c.getFlags.asScala.foldLeft(acc) {
            case (acc, flag) =>
              acc.updated(flag, acc.getOrElse(flag, 0) + 1)
          }
      }
      .toSeq
      .filter {
        case (flag, _) =>
          flag == EdgeFlag.ALLOWS_PEDESTRIAN || flag == EdgeFlag.ALLOWS_BIKE || flag == EdgeFlag.ALLOWS_CAR
      }
      .sortBy { case (flag, _) => flag.toString }

    println("Edge flag to number of edges:")
    flagToCnt.foreach {
      case (flag, cnt) =>
        println(s"$flag => $cnt")
    }
    println(s"Number of edges in R5: ${tn.streetLayer.edgeStore.nEdges()}")
    println(s"Number of vertices in R5: ${tn.streetLayer.edgeStore.vertexStore.getVertexCount}")
    val osm = new OSM(null)
    try {
      osm.readFromFile(pathToOsm)

      println(s"Number of OSM nodes: ${osm.nodes.size()}")
      val waysCnt = osm.ways.size()
      val waysWithoutSpeedCnt = osm.ways.asScala.count(w => w._2.getTag("maxspeed") == null)
      println(s"Number of OSM ways: $waysCnt")
      println(
        s"Number of OSM ways without speed: $waysWithoutSpeedCnt, which is ${1.0 * waysWithoutSpeedCnt / waysCnt * 100.0}"
      )

      def printWays(ways: mutable.Map[java.lang.Long, Way]): Unit = {
        ways
          .groupBy { case (_, way) => way.getTag("maxspeed") == null }
          .foreach {
            case (doesNotHaveSpeedTag, ways) =>
              if (doesNotHaveSpeedTag) {
                println(s"\t\t${ways.size} does not have speed tag")
              } else {
                println(s"\t\t${ways.size} has speed tag")
              }
              ways
          }
      }

      osm.ways.asScala
        .groupBy { case (_, way) => way.getTag("highway") }
        .foreach {
          case (null, w) =>
            println(s"unclassified: ${w.size}")
            printWays(w)
          case (tag, w) =>
            println(s"$tag: ${w.size}")
            printWays(w)
        }

    } finally {
      Try(osm.close())
    }
  }
}
