package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}

import beam.sim.config.BeamConfig
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.io.Source

class TollCalculator(val config: BeamConfig, val directory: String) extends LazyLogging {

  private val tollsByLinkId: Map[Int, Double] = readTollPrices(config.beam.agentsim.toll.file).withDefaultValue(0.0)
  private val tollsByWayId: Map[Long, Double] = readFromCacheFileOrOSM().withDefaultValue(0.0)

  logger.info("Ways keys size: {}", tollsByWayId.keys.size)

  def calcTollByOsmIds(osmIds: Seq[Long]): Double = osmIds.view.map(tollsByWayId).sum
  def calcTollByLinkIds(linkIds: Seq[Int]): Double = linkIds.view.map(tollsByLinkId).sum * config.beam.agentsim.tuning.tollPrice

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

  def readFromCacheFileOrOSM(): Map[Long, Double] = {
    val dataDirectory: Path = Paths.get(directory)
    val cacheFile = dataDirectory.resolve("tolls.dat").toFile
    if (cacheFile.exists()) {
      new ObjectInputStream(new FileInputStream(cacheFile))
        .readObject()
        .asInstanceOf[Map[Long, Double]]
    } else {
      val ways = fromDirectory()
      val stream = new ObjectOutputStream(new FileOutputStream(cacheFile))
      stream.writeObject(ways)
      stream.close()
      ways
    }
  }

  def fromDirectory(): Map[Long, Double] = {

    def loadOSM(osmSourceFile: File): Map[Long, Double] = {
      // Load OSM data into MapDB
      val dir = osmSourceFile.getParentFile
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile.getAbsolutePath)

      val ways = readTolls(osm)
      osm.close()
      ways
    }

    def readTolls(osm: OSM): Map[Long, Double] = {
      osm.ways.asScala.view.flatMap {
        case (id, way) =>
          val charge = way.tags.asScala.find(_.key == "charge")
            .map(chargeTag => parseCharge(chargeTag.value))
            .getOrElse(0.0)
          if (charge != 0.0) Some(Long2long(id) -> charge) else None
      }.toMap
    }

    Paths.get(directory).toFile.listFiles(_.getName.endsWith(".pbf")).headOption.map(loadOSM).getOrElse(Map())
  }

  private def parseCharge(charge: String): Double = {
    charge
      .split(";")
      .map(c => {
        val tokens = c.split(" ")
        val tts = tokens.length
        if (tts >= 2) tokens(tts - 2).toDouble else 0.0
      })
      .head
  }

}
