package beam.router.osm

import java.io._
import java.nio.file.{Files, Path, Paths}

import beam.router.osm.TollCalculator.{Charge, Toll}
import com.conveyal.osmlib.OSMEntity.Tag
import com.conveyal.osmlib.{OSM, Way}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable

class TollCalculator(val directory: String) extends LazyLogging {
  private val dataDirectory: Path = Paths.get(directory)
  private val cacheFile: File = dataDirectory.resolve("tolls.dat").toFile

  /**
    * agencies is a Map of FareRule by agencyId
    */
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
      Toll(tagToChange(w.tags.asScala.find(_.key == "charge")))
    }

    def tagToChange(tag: Option[Tag]) = {
      Charge(tag.getOrElse(new Tag("", "")).value)
    }

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasOSM(_)).map(_.getAbsolutePath).headOption.foreach(loadOSM)
    }

    ways
  }

  var maxOsmIdsLen: Long = Long.MinValue

  def calcToll(osmIds: Vector[Long]): Double = {
    if (osmIds.length > maxOsmIdsLen) {
      maxOsmIdsLen = osmIds.length
      logger.warn("Max OsmIDS encountered: {}", maxOsmIdsLen)
    }
    // TODO: Do we need faster lookup like hashset
    // TODO OSM data has no fee information, so using $1 as min toll, need to change with valid toll price
    ways.view.filter(w => osmIds.contains(w._1)).map(_._2.charges.map(_.amount).sum).sum
  }

  def main(args: Array[String]): Unit = {
    fromDirectory(Paths.get(args(0)))
  }
}

object TollCalculator {

  val MIN_TOLL = 1.0

  case class Toll(
    charges: Vector[Charge],
    vehicleTypes: Set[String] = Set(),
    isExclusionType: Boolean = false
  )

  case class Charge(
    amount: Double,
    currency: String,
    item: String = "",
    timeUnit: Option[String] = None,
    dates: Vector[ChargeDate] = Vector()
  )

  object Charge {

    def apply(charge: String): Vector[Charge] = {
      charge
        .split(";")
        .map(c => {
          val tokens = c.split(" ")
          val tts = tokens.length
          if (tts >= 2) {
            val sfxTokens = tokens(tts - 1).split("/")

            new Charge(
              tokens(tts - 2).toDouble,
              sfxTokens(0),
              sfxTokens(1),
              if (sfxTokens.length == 3) Option(sfxTokens(2)) else None,
              tts match {
                case 2 => Vector()
                case 3 => Vector(ChargeDate.apply(tokens(0)))
                case 4 => Vector(ChargeDate.apply(tokens(0)), ChargeDate.apply(tokens(1)))
                case 5 =>
                  Vector(
                    ChargeDate.apply(tokens(0)),
                    ChargeDate.apply(tokens(1)),
                    ChargeDate.apply(tokens(2))
                  )
              }
            )
          } else empty
        })
        .toVector
    }

    def empty: Charge = {
      Charge(0.0, "USD")
    }
  }

  trait ChargeDate {
    val dType: String
    val on: String
  }

  case class DiscreteDate(override val dType: String, override val on: String) extends ChargeDate

  case class DateRange(override val dType: String, override val on: String, till: String) extends ChargeDate

  object ChargeDate {
    private val months =
      Set("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val days = Set("mo", "tu", "we", "th", "fr", "sa", "su")
    private val events = Set("dawn", "sunrise", "sunset", "dusk")

    def apply(pattern: String): ChargeDate = {
      val dateTokens = pattern.split("-")
      val dType = if (isMonth(dateTokens(0))) {
        "m"
      } else if (isDay(dateTokens(0))) {
        "d"
      } else if (isHour(dateTokens(0))) {
        "h"
      } else {
        "y"
      }
      if (dateTokens.length == 1) DiscreteDate(dType, dateTokens(0))
      else DateRange(dType, dateTokens(0), dateTokens(1))
    }

    def isMonth(m: String): Boolean = months.contains(m.toLowerCase)

    def isDay(d: String): Boolean = days.contains(d.toLowerCase)

    def isHour(h: String): Boolean = h.contains(":") || events.exists(h.contains)
  }
}
