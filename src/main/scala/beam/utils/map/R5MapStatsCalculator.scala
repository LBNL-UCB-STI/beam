package beam.utils.map

import com.conveyal.osmlib.OSM
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.streets.EdgeStore.EdgeFlag
import com.conveyal.r5.transit.TransportNetwork

import scala.collection.JavaConverters._
import scala.util.Try

object R5MapStatsCalculator {

  def main(args: Array[String]): Unit = {
//    LoggerFactory.getLogger("com.conveyal").asInstanceOf[Logger].setLevel(Level.INFO)
    val pathToOsm = args(0)

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
      println(s"Number of OSM ways: ${osm.ways.size()}")
    } finally {
      Try(osm.close())
    }
  }
}
