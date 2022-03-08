package json.converter

import java.io.PrintWriter

import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json._

import scala.util.Try

object TazOutput {

  //Output structure
  case class TazGeometry(`type`: String, coordinates: Array[Array[Array[Array[Double]]]])
  case class TazViz(gid: Long, taz: Long, nhood: String, sq_mile: Double, geometry: String)

  //Structure for in memory processing
  case class Coordinates(lat: Double, lon: Double)
  case class Geometry(`type`: String, coordinates: Array[Coordinates])
  case class TazStructure(gid: Long, taz: Long, nhood: String, sq_mile: Double, geometry: Geometry)

  //Structure for statistics
  case class TazStats(taz: Long, day_of_week: Int, time: String, dropoffs: Double, pickups: Double)
  case class TazStatsTotals(taz: Long, day_of_week: Int, dropoffs: Double, pickups: Double)

  implicit val tazGeometryWrites: OFormat[TazGeometry] = Json.format[TazGeometry]
  implicit val tazVizWrites: OFormat[TazViz] = Json.format[TazViz]

  implicit val tazStatsFormat: OFormat[TazStats] = Json.format[TazStats]
  implicit val tazStatsTotalsFormat: OFormat[TazStatsTotals] = Json.format[TazStatsTotals]
}

//Converter from https://data.sfgov.org/Geographic-Locations-and-Boundaries/Traffic-Analysis-Zones/j4sj-j2nf/data#revert
// to expected format for https://github.com/sfcta/tncstoday
object SfGovTazConverter extends App with LazyLogging {

  import TazOutput._

  //Input structure
  case class Properties(area: String, name: String, taz: String)
  case class Coordinates(lat: Double, lon: Double)
  case class Geometry(`type`: String, coordinates: Seq[Coordinates])
  case class Features(properties: Properties, geometry: Geometry)

  //Reads
  implicit val propertiesReads: Reads[Properties] = Json.reads[Properties]

  implicit val featuresReads: Reads[Seq[Features]] = (json: JsValue) => {
    try {
      val featuresArray = (json \ "features").as[JsArray]
      val features = featuresArray.value.map { featureJson =>
        val properties = (featureJson \ "properties").validate[Properties].get
        val geometryJson = featureJson \ "geometry"
        val _type = (geometryJson \ "type").as[String]
        val coordinatesArray = (geometryJson \ "coordinates").as[JsArray].apply(0).as[JsArray]

        val coordinates = coordinatesArray.value.map { coordinatesItem =>
          val coordinatesJson = coordinatesItem.as[JsArray]
          val lat = coordinatesJson.apply(0).as[Double]
          val lon = coordinatesJson.apply(1).as[Double]
          Coordinates(lat, lon)
        }

        val geometry = Geometry(_type, coordinates)

        Features(properties, geometry)
      }
      JsSuccess(features)
    } catch {
      case e: Exception =>
        logger.error("exception occurred due to ", e)
        JsError()
    }
  }

  println("Taz Converter")

  def parseArgs() = {
    args
      .sliding(2, 1)
      .toList
      .collect {
        case Array("--input", inputName: String) if inputName.trim.nonEmpty => ("input", inputName)
        //case Array("--anotherParamName", value: String)  => ("anotherParamName", value)
        case arg @ _ => throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  val argsMap = parseArgs()

  val inputFilePath = argsMap.get("input")

  val mContent = inputFilePath.map { p =>
    val source = scala.io.Source.fromFile(p, "UTF-8")
    val lines =
      try source.mkString
      finally source.close()
    lines
  }

  mContent.map { s =>
    val res = Json.parse(s)
    val featuresRes = res.validate[Seq[Features]].get

    var i = 1
    val tazVizArray = featuresRes.map { f =>
      val gid: Long = i
      val taz: Long = i
      i = i + 1

//        try{
//        f.properties.taz.toLong
//      } catch {
//        case e: Exception =>
//          println(s"--------Wrong taz ${f.properties.taz}")
//          0l
//      }
      val nhood = "East Bay"
      val sq_mile = Try(f.properties.area.toDouble).getOrElse(1d)
      val coordinates = Array(Array(f.geometry.coordinates.map { coordinates =>
        Array(coordinates.lat, coordinates.lon)
      }.toArray))
      val geometry = TazGeometry("MultiPolygon", coordinates)
      val geoJsonString = Json.toJson(geometry).toString()
      TazViz(gid, taz, nhood, sq_mile, geoJsonString)
    }

    val tazVizJson = Json.toJson(tazVizArray.filter(i => i.taz > 0L))

    new PrintWriter("d:\\output.json") { write(tazVizJson.toString()); close() }

  }

}
