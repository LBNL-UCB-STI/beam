package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.Collections

import beam.router.model.BeamPath
import beam.router.osm.TollCalculator.Toll
import beam.sim.common.Range
import beam.sim.config.BeamConfig
import com.conveyal.osmlib.OSM
import com.google.common.collect.Maps
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.apache.commons.collections4.MapUtils

import scala.collection.JavaConverters._
import scala.io.Source

class TollCalculator @Inject()(val config: BeamConfig) extends LazyLogging {

  private val tollsByLinkId: java.util.Map[Int, Array[Toll]] =
    readTollPrices(config.beam.agentsim.toll.file) //.withDefaultValue(Vector())
  private val tollsByWayId: java.util.Map[Int, Array[Toll]] = readFromCacheFileOrOSM() // .withDefaultValue(Vector())

  logger.info("tollsByLinkId size: {}", tollsByLinkId.size)
  logger.info("tollsByWayId size: {}", tollsByWayId.size)

  def calcTollByOsmIds(osmIds: IndexedSeq[Long]): Double = {
    if (osmIds.isEmpty || tollsByWayId.isEmpty) 0
    else {
      osmIds.map(tollsByWayId.get)
        .filter(toll => toll != null)
        .map(toll => applyTimeDependentTollAtTime(toll, 0)).sum

//      var i = 0
//      var sum: Double = 0
//      while (i < osmIds.size) {
//        val toll = tollsByLinkId.get(osmIds(i))
//        if (toll != null) {
//          sum += applyTimeDependentTollAtTime(toll, 0)
//        }
//        i += 1
//      }
//      sum
    }
  }

  def calcTollByLinkIds(path: BeamPath): Double = {
    val linkEnterTimes = path.linkTravelTime.scanLeft(path.startPoint.time)(_ + _)
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

//    var i: Int = 0
//    var total: Double = 0.0
//    while (i < tolls.length) {
//      val toll = tolls(i)
//      if (toll.timeRange.has(time)) total += toll.amount
//      i += 1
//    }
//    total
    // tolls.view.filter(toll => toll.timeRange.has(time)).map(toll => toll.amount).sum
  }

  private def readTollPrices(tollPricesFile: String): java.util.Map[Int, Array[Toll]] = {
    if (Files.exists(Paths.get(tollPricesFile))) {
      val rowList = Source
        .fromFile(tollPricesFile)
        .getLines()
        .drop(1) // table header
        .toArray
        .map(_.split(","))
        .groupBy(t => t(0).toInt)
        .map {
          case (linkId, lines) =>
            val tollWithRange = lines.map(t => Toll(t(1).toDouble, Range(t(2))))
            Maps.immutableEntry[Int, Array[Toll]](linkId, tollWithRange)
        }
        .toArray
      MapUtils.putAll(new util.HashMap[Int, Array[Toll]](), rowList.asInstanceOf[Array[AnyRef]])
    } else {
      Collections.emptyMap()
    }
  }

  def readFromCacheFileOrOSM(): java.util.Map[Int, Array[Toll]] = {
    val dataDirectory: Path = Paths.get(config.beam.routing.r5.directory)
    val cacheFile = dataDirectory.resolve("tolls.dat").toFile
    if (cacheFile.exists()) {
      new ObjectInputStream(new FileInputStream(cacheFile))
        .readObject()
        .asInstanceOf[java.util.Map[Int, Array[Toll]]]
    } else {
      val ways = fromDirectory()
      val stream = new ObjectOutputStream(new FileOutputStream(cacheFile))
      stream.writeObject(ways)
      stream.close()
      ways
    }
  }

  def fromDirectory(): java.util.Map[Int, Array[Toll]] = {
    def loadOSM(osmSourceFile: File): java.util.Map[Int, Array[Toll]] = {
      // Load OSM data into MapDB
      val dir = osmSourceFile.getParentFile
      val osm = new OSM(new File(dir, "osm.mapdb").getPath)
      osm.readFromFile(osmSourceFile.getAbsolutePath)

      val ways = readTolls(osm)
      osm.close()
      ways
    }

    def readTolls(osm: OSM): java.util.Map[Int, Array[Toll]] = {
      val arr = osm.ways.asScala.toSeq.flatMap {
        case (id, way) if way.tags != null =>
          val tolls = way.tags.asScala
            .find(_.key == "charge")
            .map(chargeTag => parseTolls(chargeTag.value))
            .getOrElse(Nil)
            .toArray
          if (tolls.nonEmpty) Some(Maps.immutableEntry[Int, Array[Toll]](id.toInt, tolls)) else None
        case _ => None
      }.toArray
      MapUtils.putAll(new util.HashMap[Int, Array[Toll]](), arr.asInstanceOf[Array[AnyRef]])
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
          .flatMap(token => Some(Toll(token.toDouble, Range("[:]"))))
      })
  }

}

object TollCalculator {
  case class Toll(amount: Double, timeRange: Range) extends Serializable
}
