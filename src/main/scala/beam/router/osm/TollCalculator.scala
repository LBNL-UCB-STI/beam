package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.Collections
import javax.inject.Inject

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

import beam.router.model.BeamPath
import beam.router.osm.TollCalculator.Toll
import beam.sim.common.Range
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import com.conveyal.osmlib.OSM
import com.google.common.collect.Maps
import com.typesafe.scalalogging.LazyLogging
import gnu.trove.map.hash.TIntObjectHashMap
import org.apache.commons.collections4.MapUtils

class TollCalculator @Inject() (val config: BeamConfig) extends LazyLogging {
  import beam.utils.FileUtils._

  private val tollsByLinkId: TIntObjectHashMap[Array[Toll]] = {
    val map: util.Map[Int, Array[Toll]] = readTollPrices(config.beam.agentsim.toll.filePath)
    val intHashMap = new TIntObjectHashMap[Array[Toll]]()
    map.asScala.foreach { case (k, v) =>
      intHashMap.put(k, v)
    }
    intHashMap
  }
  private val tollsByWayId: java.util.Map[Long, Array[Toll]] = readFromCacheFileOrOSM()

  logger.info("tollsByLinkId size: {}", tollsByLinkId.size)
  logger.info("tollsByWayId size: {}", tollsByWayId.size)

  def calcTollByOsmIds(osmIds: IndexedSeq[Long]): Double = {
    if (osmIds.isEmpty || tollsByWayId.isEmpty) 0
    else {
      osmIds
        .map(tollsByWayId.get)
        .filter(toll => toll != null)
        .map(toll => applyTimeDependentTollAtTime(toll, 0))
        .sum
    }
  }

  def calcTollByLinkIds(path: BeamPath): Double = {
    val linkEnterTimes =
      path.linkTravelTime.scanLeft(path.startPoint.time.toDouble)(_ + _).map(time => math.round(time.toFloat))
    path.linkIds
      .zip(linkEnterTimes)
      .map((calcTollByLinkId _).tupled)
      .sum
  }

  def calcTollByLinkId(linkId: Int, time: Int): Double = {
    val tolls = tollsByLinkId.get(linkId)
    if (tolls == null) 0
    else {
      applyTimeDependentTollAtTime(tolls, time) * config.beam.agentsim.tuning.tollPrice
    }
  }

  private def applyTimeDependentTollAtTime(tolls: Array[Toll], time: Int): Double = {
    tolls.filter(toll => toll.timeRange.has(time)).map(toll => toll.amount).sum
  }

  private def readTollPrices(tollPricesFile: String): java.util.Map[Int, Array[Toll]] = {
    if (Files.exists(Paths.get(tollPricesFile))) {
      val rowList = FileUtils
        .readAllLines(tollPricesFile)
        .drop(1) // table header
        .toArray
        .map(_.split(","))
        .groupBy(t => t(0).toInt)
        .map { case (linkId, lines) =>
          val tollWithRange = lines.map(t => Toll(t(1).toDouble, Range(t(2))))
          Maps.immutableEntry[Int, Array[Toll]](linkId, tollWithRange)
        }
        .toArray
      MapUtils.putAll(new util.HashMap[Int, Array[Toll]](), rowList.asInstanceOf[Array[AnyRef]])
    } else {
      Collections.emptyMap()
    }
  }

  def readFromCacheFileOrOSM(): java.util.Map[Long, Array[Toll]] = {
    val dataDirectory: Path = Paths.get(config.beam.routing.r5.directory)
    val cacheFile = dataDirectory.resolve("tolls.dat").toFile
    val chachedWays = if (cacheFile.exists()) {
      try {
        using(new ObjectInputStream(new FileInputStream(cacheFile))) { stream =>
          Some(stream.readObject().asInstanceOf[java.util.Map[Long, Array[Toll]]])
        }
      } catch {
        case NonFatal(ex) =>
          logger.warn(s"Could not read cached data from '${cacheFile.getAbsolutePath}. Going to re-create cache", ex)
          Some(readFromDatAndCreateCache(cacheFile))
      }
    } else None
    chachedWays.getOrElse(readFromDatAndCreateCache(cacheFile))
  }

  def readFromDatAndCreateCache(cacheFile: File): util.Map[Long, Array[Toll]] = {
    Try(cacheFile.delete())
    val ways = fromDirectory()
    using(new ObjectOutputStream(new FileOutputStream(cacheFile))) { stream =>
      stream.writeObject(ways)
    }
    ways
  }

  def fromDirectory(): java.util.Map[Long, Array[Toll]] = {
    def loadOSM(osmSourceFile: File): java.util.Map[Long, Array[Toll]] = {
      // Load OSM data into MapDB
      val dir = osmSourceFile.getParentFile
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile.getAbsolutePath)

      val ways = readTolls(osm)
      osm.close()
      ways
    }

    def readTolls(osm: OSM): java.util.Map[Long, Array[Toll]] = {
      val arr = osm.ways.asScala.toSeq.flatMap {
        case (id, way) if way.tags != null =>
          val tolls = way.tags.asScala
            .find(_.key == "charge")
            .map(chargeTag => parseTolls(chargeTag.value))
            .getOrElse(Nil)
            .toArray
          if (tolls.nonEmpty) Some(Maps.immutableEntry[Long, Array[Toll]](id, tolls)) else None
        case _ => None
      }.toArray
      MapUtils.putAll(new util.HashMap[Long, Array[Toll]](), arr.asInstanceOf[Array[AnyRef]])
    }

    Paths
      .get(config.beam.routing.r5.directory)
      .toFile
      .listFiles(_.getName.endsWith(".pbf"))
      .headOption
      .map(loadOSM)
      .getOrElse(Collections.emptyMap())
  }

  private def parseTolls(charge: String): Seq[Toll] = {
    charge
      .split(";")
      .flatMap(c => {
        c.split(" ")
          .headOption
          .flatMap(token =>
            try { Some(token.toDouble) }
            catch { case _: Throwable => None }
          )
          .flatMap(token => Some(Toll(token, Range("[:]"))))
      })
  }

}

object TollCalculator {
  case class Toll(amount: Double, timeRange: Range) extends Serializable
}
