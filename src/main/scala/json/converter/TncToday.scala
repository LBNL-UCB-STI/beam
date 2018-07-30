package json.converter

import java.io.{BufferedWriter, File, FileWriter}

import json.converter.TazOutput._
import play.api.libs.json.Json

import scala.collection.JavaConverters._

object TncToday {

  def processJson(inputJson: String): Seq[TazStructure] = {
    val res = Json.parse(inputJson)
    val tazViz = res.as[Seq[TazViz]]

    tazViz.map { tv =>
      val tmpGeometry = Json.parse(tv.geometry).as[TazGeometry]
      val coordinates = tmpGeometry.coordinates(0)(0).map { array =>
        TazOutput.Coordinates(array(0), array(1))
      }
      val geometry = TazOutput.Geometry(tmpGeometry.`type`, coordinates)
      TazStructure(tv.gid, tv.taz, tv.nhood, tv.sq_mile, geometry)
    }
  }

  def processJsonJava(inputJson: String): java.util.List[TazStructure] = {
    processJson(inputJson).asJava
  }

  def completeStats(data: Seq[TazStats]): Seq[TazStats] = {
    val groupedByTaz = data.groupBy(_.taz)
    def generateDataForDay(day: Int, tazId: Long): Seq[TazStats] = {
      (0 to 23).map { h =>
        val hs = "%02d:00:00".format(h)
        TazStats(tazId, day, hs, 0d, 0d)
      }
    }

    groupedByTaz
      .map {
        case (tazId, statsByTaz) =>
          val groupedByDay = statsByTaz.groupBy(_.day_of_week)
          val groupedByDayWithAllData = groupedByDay.map {
            case (day, statsByDay) =>
              val byHours = statsByDay.map(e => (e.time, e)).toMap
              val allHours = (0 to 23).map { h =>
                val hs = "%02d:00:00".format(h)
                byHours.getOrElse(hs, TazStats(tazId, day, hs, 0d, 0d))
              }
              (day, allHours)
          }
          val allDaysWithAllHours = (0 to 6)
            .map(i => (i, groupedByDayWithAllData.getOrElse(i, generateDataForDay(i, tazId))))
            .toMap
          (tazId, allDaysWithAllHours.values.flatten)
      }
      .values
      .flatten
      .toSeq
  }

  def roundAt(p: Int)(n: Double): Double = { val s = math pow (10, p); (math round n * s) / s }

  def generateTotals(data: Seq[TazStats]): Seq[TazStatsTotals] = {

    val groupedByTaz = data.groupBy(_.taz)
    val roundAtThree = roundAt(3) _
    groupedByTaz.flatMap {
      case (taz, statsByTaz) =>
        val byDay = statsByTaz.groupBy(_.day_of_week)
        byDay.map {
          case (day, statsByDay) =>
            val totalDropoffs = statsByDay.foldLeft(0d) {
              case (a, b) => roundAtThree(a + b.dropoffs)
            }
            val totalPickups = statsByDay.foldLeft(0d) {
              case (a, b) => roundAtThree(a + b.pickups)
            }
            TazStatsTotals(taz, day, totalDropoffs, totalPickups)
        }
    }.toSeq
  }

  def statsAndTotalsToJson(data: Seq[TazStats]): (String, String) = {
    val allData = completeStats(data)

    val statsOutData = Json
      .toJson(allData)
      .toString()

    val outDataTotals = generateTotals(allData)

    val outDataTotalsJson = Json
      .toJson(outDataTotals)
      .toString()
    (statsOutData, outDataTotalsJson)
  }

  def saveJsonStructure(
    data: java.util.List[TazStats],
    statsOut: String,
    statsTotalsOut: String
  ): Unit = {
    val (outData, outDataTotalsJson) = statsAndTotalsToJson(data.asScala)

    saveTo(statsOut, outData)
    saveTo(statsTotalsOut, outDataTotalsJson)
  }

  def saveTo(filePath: String, data: String): Unit = {
    val file = new File(filePath)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(data)
    bw.close()
  }
}
