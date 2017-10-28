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
    val outData = Json
      .toJson(data.asScala)
      .toString()

    val outDataTotals = data.asScala.groupBy(s => s.taz)
      .map{case (k, l) =>
      val totalDropoffs = l.foldLeft(0d){case (a, b) => a + b.dropoffs}
      val totalPickups = l.foldLeft(0d){case (a, b) => a + b.pickups}
      TazStatsTotals(k, l.head.day_of_week, totalDropoffs, totalPickups)
    }

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
