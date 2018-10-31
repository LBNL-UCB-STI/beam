package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}

import beam.router.osm.TollCalculator.{Charge, Toll}
import beam.sim.config.BeamConfig
import com.conveyal.osmlib.OSMEntity.Tag
import com.conveyal.osmlib.{OSM, Way}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

class TollCalculator(val config: BeamConfig, val directory: String) extends LazyLogging {
  private val dataDirectory: Path = Paths.get(directory)
  private val cacheFile: File = dataDirectory.resolve("tolls.dat").toFile
  private val tollPrices = readTollPrices(config.beam.agentsim.toll.file).withDefaultValue(0.0)

  val ways: mutable.Map[java.lang.Long, Toll] = if (cacheFile.exists()) {
    new ObjectInputStream(new FileInputStream(cacheFile))
      .readObject()
      .asInstanceOf[mutable.Map[java.lang.Long, Toll]]
  } else {
    val ways = fromDirectory(dataDirectory)
    val stream = new ObjectOutputStream(new FileOutputStream(cacheFile))
    stream.writeObject(ways)
    stream.close()
    ways
  }

  logger.info("Ways keys size: {}", ways.keys.size)

  def fromDirectory(directory: Path): mutable.Map[java.lang.Long, Toll] = {
    var ways: mutable.Map[java.lang.Long, Toll] = mutable.Map()

    /**
      * Checks whether its a osm.pbf feed and has fares data.
      */
    val hasOSM: FileFilter = _.getName.endsWith(".pbf")

    def loadOSM(osmSourceFile: String): Unit = {
      val dir = new File(osmSourceFile).getParentFile

      // Load OSM data into MapDB
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile)
      ways = readTolls(osm)
      osm.close()
    }

    def readTolls(osm: OSM) = {
      val ways = osm.ways.asScala.filter(
        ns =>
          ns._2.tags != null && ns._2.tags.asScala.exists(
            t => (t.key == "toll" && t.value != "no") || t.key.startsWith("toll:")
          ) && ns._2.tags.asScala.exists(_.key == "charge")
      )
      //osm.nodes.values().asScala.filter(ns => ns.tags != null && ns.tags.size() > 1 && ns.tags.asScala.exists(t => (t.key == "fee" && t.value == "yes") || t.key == "charge") && ns.tags.asScala.exists(t => t.key == "toll" || (t.key == "barrier" && t.value == "toll_booth")))
      ways.map(w => (w._1, wayToToll(w._2)))
    }

    def wayToToll(w: Way) = {
      Toll(tagToCharge(w.tags.asScala.find(_.key == "charge")))
    }

    def tagToCharge(tag: Option[Tag]) = {
      Charge(tag.getOrElse(new Tag("", "")).value)
    }

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasOSM).map(_.getAbsolutePath).headOption.foreach(loadOSM)
    }

    ways
  }

  var maxOsmIdsLen: Long = Long.MinValue

  def calcTollByOsmIds(osmIds: Vector[Long]): Double = {
    if (osmIds.length > maxOsmIdsLen) {
      maxOsmIdsLen = osmIds.length
      logger.debug("Max OsmIDS encountered: {}", maxOsmIdsLen)
    }
    // TODO: Do we need faster lookup like hashset
    // TODO OSM data has no fee information, so using $1 as min toll, need to change with valid toll price
    ways.view.filter(w => osmIds.contains(w._1)).map(_._2.charges.map(_.amount).sum).sum
  }

  def calcTollByLinkIds(linkIds: IndexedSeq[Int]): Double = linkIds.view.map(tollPrices).sum * config.beam.agentsim.tuning.tollPrice

  private def readTollPrices(tollPricesFile: String): Map[Int, Double] = {
    if (Files.exists(Paths.get(tollPricesFile))) {
      Source
        .fromFile(tollPricesFile)
        .getLines()
        .map(_.split(","))
        .filterNot(_(0).equalsIgnoreCase("linkId"))
        .map(t => t(0).toInt -> t(1).toDouble)
        .toMap

    } else {
      Map()
    }
  }

}

object TollCalculator {

  case class Toll(charges: Vector[Charge])
  case class Charge(amount: Double)

  object Charge {

    def apply(charge: String): Vector[Charge] = {
      charge
        .split(";")
        .map(c => {
          val tokens = c.split(" ")
          val tts = tokens.length
          if (tts >= 2) {
            new Charge(
              tokens(tts - 2).toDouble
            )
          } else empty
        })
        .toVector
    }

    def empty: Charge = {
      Charge(0.0)
    }
  }

}
