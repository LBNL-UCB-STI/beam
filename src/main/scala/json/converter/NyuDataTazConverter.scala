package json.converter

import java.io.PrintWriter

import com.typesafe.scalalogging.LazyLogging
import play.api.libs.json._

import scala.util.Try

//Converter from https://geo.nyu.edu/catalog/stanford-hq850hh1120 to expected format for
// https://github.com/sfcta/tncstoday
object NyuDataTazConverter extends App with LazyLogging {

  import TazOutput._

  //Input structure
  case class Properties(id: Long, taz: String)
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
        val coordinatesArray =
          (geometryJson \ "coordinates").as[JsArray].apply(0).as[JsArray].apply(0).as[JsArray]

        println(s"-----------Size ${coordinatesArray.value.size}")

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
    val lines = try source.mkString
    finally source.close()
    lines
  }

  mContent.map { s =>
    val res = Json.parse(s)
    val featuresRes = res.validate[Seq[Features]].get

    val tazVizArray = featuresRes.map { f =>
      val gid = f.properties.id
      val taz: Long = Try(f.properties.taz.toLong).getOrElse(0l)
      val nhood = "East Bay"
      val sq_mile = 1
      val coordinates = Array(Array(f.geometry.coordinates.map { coordinates =>
        Array(coordinates.lat, coordinates.lon)
      }.toArray))
      val geometry = TazGeometry("MultiPolygon", coordinates)
      val geoJsonString = Json.toJson(geometry).toString()
      TazViz(gid, taz, nhood, sq_mile, geoJsonString)
    }

    println("Res:")
    println(s"$featuresRes")

    val tazVizJson = Json.toJson(tazVizArray.filter(_.taz > 0l))
    println(s"Converted: ${tazVizJson.toString()}")

    new PrintWriter("d:\\output.json") { write(tazVizJson.toString()); close() }

  }

}
