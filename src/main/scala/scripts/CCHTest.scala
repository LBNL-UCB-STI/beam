package scripts

import beam.sim.common.GeoUtils
import beam.utils.FileUtils
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils
import sun.nio.ch.IOUtil

import scala.collection.JavaConverters._

object CCHTest extends App {
  new CCHTest().run(args(0))
}

class CCHTest extends LazyLogging {
  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:2808"
  }

  private def readOsm(filePath: String): OSM = {
    val osm = new OSM(null)
    osm.readFromFile(filePath)
    osm
  }

  private def getNodeCoord(osm: OSM, nodeId: Long): Coord = {
    val node = osm.nodes.get(nodeId)
    new Coord(node.getLon, node.getLat)
  }

  private def getDistance(osm: OSM, nodeId1: Long, nodeId2: Long): Double = {
    val coord1 = getNodeCoord(osm, nodeId1)
    val coord2 = getNodeCoord(osm, nodeId2)
    geo.distLatLon2Meters(coord1, coord2)
  }

  private def printOsmStats(osm: OSM): Unit = {
    logger.info(s"count of nodes is: ${osm.nodes.size()}")
    logger.info(s"count of ways is: ${osm.ways.size()}")
    logger.info(s"count of relations is: ${osm.relations.size()}")
  }

  def run1(inputFilePath: String): Unit = {
    val quote = "\""
    val osm = readOsm(inputFilePath)
    FileUtils.using(IOUtils.getBufferedReader("/home/crixal/Downloads/cch_native_report.csv")) { br =>
      FileUtils.using(IOUtils.getBufferedWriter("/home/crixal/Downloads/cch_native_report_with_lines.csv.gz")) { bw =>
        bw.write(br.readLine())
        bw.newLine()
        var line = br.readLine()
        while (line != null) {
          val parts = line.split(",")
          if (parts.size == 10) {
            val pointsStr = parts(parts.size - 1).trim
              .split(" ")
              .map(_.toLong)
              .map { nodeId =>
                getNodeCoord(osm, nodeId)
              }
              .map { c =>
                s"${c.getX} ${c.getY}"
              }

            bw.write(parts.take(parts.size - 1).mkString(","))
            bw.write(s",${quote}linestring(${pointsStr.mkString(",")})${quote}")

            line = br.readLine()
          } else {
            bw.write(line)
          }
          bw.newLine()
        }
      }
    }
  }

  def run(inputFilePath: String) {
    //    val route = "62935079,62935080,62599570,62937696,2498502260,62937697,4966044132,62937698,62671104,62911235,473369327,62912088,1313766119,1313766120,1313766121,62937701,62937702,62937703,62673650,62674690,62705267,62765900,62651221,62937705,62884502,62847273,62713400,62937706,62937707,5592097121,62934060,62934062,62736858,62934064,4218049049,62919480,62867122,62919478,62722926,62655476,62919467,62806480"
    val route =
      "152547977,152617974,152651104,152651106,4346167514,152550836,4345054671,152398687,152550844,152550848,431000918,1651050304,1651050287,1366269463,1366269466,1366269467,315426970,4344958695,1782365509,152502995,1782365533,7081471879,4344958697,152412762,152551118,4344958698,152551114,152696211,1851192571,152398676,1401881101,152622948,152622956,1401881078,152622968,152622972,152622975,152622977,152373777,152664451,152380946,152380959,152380968,152375892,152470835,1632823742,2583772239,152384374,152373830,152383899,308713014,308713013,152381640,4281149631,6146656872,152383855,152701606,4281149635,4281149638,152381027,152381058,152700487,152381477,1339127936,152374481,1619638908,1775174955,152374490,1831101657,1619638997,152368994,152383267,152379547,152625857,1230199382,152382494,3330078179,152382507,864918498"
    val osm = readOsm(inputFilePath)
    osm.ways.asScala.foreach { case (id, value) => if (value.nodes.contains(152464969L)) println(id) }
    val pointsStr = route
      .split(",")
      .map { nodeId =>
        getNodeCoord(osm, nodeId.toLong)
      }
      .map { c =>
        s"${c.getX} ${c.getY}"
      }
    val linestring = s"linestring(${pointsStr.mkString(",")})"
    println(linestring)
  }
}
