package beam.utils.data.synthpop

import java.io.Closeable

import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable

class FipsCodes (val stateToCountyToName: Map[Int, Map[Int, String]], private val pathToFipsCodes: String) extends LazyLogging {

  def getCountyNameInLowerCase(geoId: String): String = {
    try {
      val state = geoId.slice(0, 2).toInt
      val county = geoId.slice(3, 6).toInt

      stateToCountyToName.get(state) match {
        case None =>
          logger.error(s"Can't find state $state information in map build from $pathToFipsCodes")
          "??"
        case Some(countyToName) =>
          countyToName.get(county) match {
            case None =>
              logger.error(s"Can't find state:county $state:$county information in map build from $pathToFipsCodes")
              "??"
            case Some(name) => name.toLowerCase
          }
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Got $e while trying to read state and county from geoId $geoId")
        "???"
    }
  }
}

object FipsCodes {

  def apply(pathToFipsCodes: String): FipsCodes = {
    new FipsCodes(readFipsCodes(pathToFipsCodes), pathToFipsCodes)
  }

  private case class FipsRow(state: Int, county: Int, name: String)

  private object FipsRow extends LazyLogging {

    def apply(csvRow: java.util.Map[String, String]): FipsRow = {
      def getStr(key: String): String =
        if (csvRow.containsKey(key)) csvRow.get(key)
        else {
          logger.error(s"FIPS codes file missing '$key' value. Using '??' as replacement.")
          logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
          "??"
        }

      def getInt(key: String): Int =
        try { getStr(key).toInt } catch {
          case _: java.lang.NumberFormatException =>
            logger.error(s"Can't parse ${getStr(key)} as Int. Got: java.lang.NumberFormatException")
            logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
            -1
          case e: Throwable =>
            logger.error(s"Can't parse ${getStr(key)} as Int. Got: $e")
            logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
            throw e
        }

      new FipsRow(getInt("state"), getInt("county"), getStr("long_name"))
    }
  }

  private def readFipsCodes(pathToFipsCodes: String): Map[Int, Map[Int, String]] = {
    def readFipsRows(relativePath: String): Map[Int, Map[Int, String]] = {
      val (iter: Iterator[FipsRow], toClose: Closeable) =
        GenericCsvReader.readAs[FipsRow](relativePath, FipsRow.apply, _ => true)
      try {
        iter
          .foldLeft(mutable.Map.empty[Int, mutable.Map[Int, String]]) {
            case (stateToCountyToName, fipsRow) =>
              val countyToName = stateToCountyToName.getOrElse(fipsRow.state, mutable.Map.empty[Int, String])
              countyToName(fipsRow.county) = fipsRow.name
              stateToCountyToName(fipsRow.state) = countyToName
              stateToCountyToName
          }
          .map { case (key, mutmap) => key -> mutmap.toMap }
          .toMap
      } finally {
        toClose.close()
      }
    }

    readFipsRows(pathToFipsCodes)
  }
}
