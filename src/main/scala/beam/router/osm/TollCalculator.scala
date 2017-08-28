package beam.router.osm

import java.io.File
import java.nio.file.{Files, Path, Paths}

import com.conveyal.osmlib.{OSM, Way}

import scala.collection.JavaConverters._
import scala.collection.mutable.Map

object TollCalculator {

  val MIN_TOLL = 1.0
  var ways: Map[java.lang.Long, Way] = _

  def fromDirectory(directory: Path): Unit = {
    /**
      * Checks whether its a osm.pbf feed and has fares data.
      *
      * @param file specific file to check.
      * @return true if a valid pbf.
      */
    def hasOSM(file: File): Boolean = file.getName.endsWith(".pbf")

    def loadOSM(osmSourceFile: String): Unit = {
      val dir = new File(osmSourceFile).getParentFile

      // Load OSM data into MapDB
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile)
      readTolls(osm)
      osm.close()
    }

    def readTolls(osm: OSM) = {
      ways = osm.ways.asScala.filter(ns => ns._2.tags != null && ns._2.tags.asScala.exists(t => (t.key == "toll" && t.value != "no") || t.key.startsWith("toll:")))
      //osm.nodes.values().asScala.filter(ns => ns.tags != null && ns.tags.size() > 1 && ns.tags.asScala.exists(t => (t.key == "fee" && t.value == "yes") || t.key == "charge") && ns.tags.asScala.exists(t => t.key == "toll" || (t.key == "barrier" && t.value == "toll_booth")))
    }

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasOSM(_)).map(_.getAbsolutePath).headOption.foreach(loadOSM(_))
    }
  }

  def calcToll(osmIds: Vector[Long]): Double = {
    // TODO OSM data has no fee information, so using $1 as min toll, need to change with valid toll price
    ways.count(w => osmIds.contains(w._1)) * MIN_TOLL
  }

  def main(args: Array[String]): Unit = {
    fromDirectory(Paths.get(args(0)))
  }
}
