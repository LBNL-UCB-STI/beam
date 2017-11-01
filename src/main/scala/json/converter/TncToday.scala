package json.converter

import java.io.{BufferedWriter, File, FileWriter}

import scala.collection.JavaConverters._
import json.converter.TazOutput._
import play.api.libs.json.Json

object TncToday {

  def processJson(inputJson: String): Seq[TazStructure] = {
    val res = Json.parse(inputJson)
    val tazViz = res.as[Seq[TazViz]]

    tazViz.map{ tv =>
      val tmpGeometry = Json.parse(tv.geometry).as[TazGeometry]
      val coordinates = tmpGeometry.coordinates(0)(0).map{ array => TazOutput.Coordinates(array(0), array(1))}
      val geometry = TazOutput.Geometry(tmpGeometry.`type`, coordinates)
      TazStructure(tv.gid, tv.taz, tv.nhood, tv.sq_mile, geometry)
    }
  }

  def processJsonJava(inputJson: String): java.util.List[TazStructure] = {
    processJson(inputJson).asJava
  }


  def saveJsonStructure(data: java.util.List[TazStats], statsOut: String, statsTotalsOut: String) = {

    val groupedByTaz = data.asScala.groupBy(_.taz)
    val allData = groupedByTaz.map{case (tazId, statsByTaz) =>
      val byDay = statsByTaz.map(e => (e.day_of_week, e)).toMap
      val allDays = (0 to 6).map(i => byDay.get(i).getOrElse(TazStats(tazId, i, "00:00:00", 0d, 0d)))

      val groupedByDay = allDays.groupBy(_.day_of_week)
      val res = groupedByDay.map{case (day, statsByDay) =>
          val byHours = statsByDay.map(e => (e.time, e)).toMap
          val allHours = (0 to 23).map { h =>
            val hs = "%02d:00:00".format(h)
            byHours.get(hs).getOrElse(TazStats(tazId, day, hs, 0d, 0d))
          }
          (day, allHours)
      }

      (tazId, res.values.flatten)
    }.values.flatten

    val outData = Json
      .toJson(allData)
      .toString()

    val outDataTotals = allData.groupBy(s => s.taz)
      .map{case (taz, statsByTaz) =>
        val byDay = statsByTaz.groupBy(_.day_of_week)
        byDay.map{case (day, statsByDay) =>
          val totalDropoffs = statsByDay.foldLeft(0d){case (a, b) => a + b.dropoffs}
          val totalPickups = statsByDay.foldLeft(0d){case (a, b) => a + b.pickups}
          TazStatsTotals(taz, day, totalDropoffs, totalPickups)
        }
    }.flatten

    val outDataTotalsJson = Json
      .toJson(outDataTotals)
      .toString()

    saveTo(statsOut, outData)
    saveTo(statsTotalsOut, outDataTotalsJson)
  }

  def saveTo(filePath: String, data:String): Unit ={
    val file = new File(filePath)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(data)
    bw.close()
  }

}
