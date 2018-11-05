package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}

import beam.router.model.BeamPath
import beam.router.osm.TollCalculator.Toll
import beam.sim.config.BeamConfig
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging
import beam.agentsim.agents.choice.mode.Range

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.io.Source

class TollCalculator(val config: BeamConfig, val directory: String) extends LazyLogging {

  private val tollsByLinkId: Map[Int, Seq[Toll]] = readTollPrices(config.beam.agentsim.toll.file).withDefaultValue(Vector())
  private val tollsByWayId:  scala.collection.Map[Long, Seq[Toll]] = {
    val map: collection.Map[Long, Seq[Toll]] = readFromCacheFileOrOSM()
    new scala.collection.Map.WithDefault[Long, Seq[Toll]] (map, x => Vector()) {
      override def +[V1 >: Seq[Toll]](
        kv: (Long, V1)
      ): collection.Map[Long, V1] = ???
      override def -(
        key: Long
      ): collection.Map[Long, Seq[Toll]] = ???
    }
  }

  logger.info("Ways keys size: {}", tollsByWayId.keys.size)

  def calcTollByOsmIds(osmIds: Seq[Long]): Double = osmIds.view.map(tollsByWayId).map(toll => applyTimeDependentTollAtTime(toll, 0)).sum

  def calcTollByLinkIds(path: BeamPath): Double = {
    val linkEnterTimes = path.linkTravelTime.scanLeft(path.startPoint.time)(_ + _)
    path
      .linkIds
      .zip(linkEnterTimes)
      .map(calcTollByLinkId _ tupled)
      .sum
  }

  def calcTollByLinkId(linkId: Int, time: Int): Double = applyTimeDependentTollAtTime(tollsByLinkId(linkId), time) * config.beam.agentsim.tuning.tollPrice

  private def applyTimeDependentTollAtTime(tolls: Seq[Toll], time: Int) = {
    tolls.view.filter(toll => toll.timeRange.has(time)).map(toll => toll.amount).sum
  }

  private def readTollPrices(tollPricesFile: String): Map[Int, Seq[Toll]] = {
    if (Files.exists(Paths.get(tollPricesFile))) {
      val rowList = Source
        .fromFile(tollPricesFile)
        .getLines()
        .drop(1) // table header
        .toList
      rowList
        .view
        .map(_.split(","))
        .groupBy(t => t(0).toInt)
        .mapValues(lines => lines.map(t => Toll(t(1).toDouble, Range(t(2)))))
    } else {
      Map()
    }
  }

  def readFromCacheFileOrOSM(): scala.collection.Map[Long, Seq[Toll]] = {
    val dataDirectory: Path = Paths.get(directory)
    val cacheFile = dataDirectory.resolve("tolls.dat").toFile
    if (cacheFile.exists()) {
      val obj = new ObjectInputStream(new FileInputStream(cacheFile))
        .readObject()
      obj.asInstanceOf[mutable.HashMap[Long, Seq[Toll]]]
    } else {
      val ways = fromDirectory()
      val stream = new ObjectOutputStream(new FileOutputStream(cacheFile))
      stream.writeObject(ways)
      stream.close()
      ways
    }
  }

  def fromDirectory(): Map[Long, Seq[Toll]] = {

    def loadOSM(osmSourceFile: File): Map[Long, Seq[Toll]] = {
      // Load OSM data into MapDB
      val dir = osmSourceFile.getParentFile
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile.getAbsolutePath)

      val ways = readTolls(osm)
      osm.close()
      ways
    }

    def readTolls(osm: OSM): Map[Long, Seq[Toll]] = {
      osm.ways.asScala.view.flatMap {
        case (id, way) if way.tags != null =>
          val tolls = way.tags.asScala.find(_.key == "charge")
            .map(chargeTag => parseTolls(chargeTag.value))
            .getOrElse(Nil)
          if (tolls.nonEmpty) Some(Long2long(id) -> tolls) else None
        case _ => None
      }.toMap
    }

    Paths.get(directory).toFile.listFiles(_.getName.endsWith(".pbf")).headOption.map(loadOSM).getOrElse(Map())
  }

  private def parseTolls(charge: String): Seq[Toll] = {
    charge
      .split(";")
      .flatMap(c => {
        c.split(" ")
          .headOption
          .flatMap(token =>
            Some(Toll(token.toDouble, Range("[:]"))))
      })
  }

}

object TollCalculator {
  case class Toll(amount: Double, timeRange: Range) extends Serializable
}

