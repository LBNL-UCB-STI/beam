package beam.utils

import beam.router.RoutingModel.{BeamGraphPath, BeamLeg}
import io.circe.{Encoder, Json, JsonObject}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils
import io.circe.syntax._
import scala.xml.XML

/**
  * [[Encoder]]s, implicit encoders, and output serialization utilities
  * Created by sfeygin on 3/28/17.
  */
object JsonUtils {

  // Put global implicit encoders here. Can import wholesale in implementing code.
  object syntax {
    implicit val encodeLeg: Encoder[BeamLeg] = (a: BeamLeg) => {
      val jsonBuilder: Map[String, Json] = Map(
        "typ" -> Json.fromString("trajectory"), "mode" -> a.mode.asJson, "shp" -> a.graphPath.asJson)
      Json.fromJsonObject(JsonObject.fromMap(jsonBuilder))
    }

    implicit val encodeCoord: Encoder[Coord]=(a:Coord)=>{
      Json.fromValues(Seq(Json.fromDoubleOrNull(MathUtils.roundDouble(a.getX,5)),Json.fromDoubleOrNull(MathUtils.roundDouble(a.getY,5))))
    }
  }

  def processEventsFileVizData(inFile: String, outFile: String): Unit = {
    val xml = XML.load(IOUtils.getInputStream(inFile))
    val events = xml \\ "events" \ "event"
    val out = for {event <- events if event.attribute("type").get.toString() == "pathTraversal" | event.attribute("type").get.toString() == "pointProcess"
    } yield event.attribute("viz_data").get.toString().replace("&quot;", "\"")
    val jsonOutString = out.mkString("\n[", ",\n", "]\n")
    val writer = IOUtils.getBufferedWriter(outFile)
    writer.write(jsonOutString)
    writer.flush()
    writer.close()
  }

  //// Private Methods

  private[this] implicit val encodeGraphPath: Encoder[BeamGraphPath] = (a: BeamGraphPath) => {
    Json.fromValues(a.trajectory.map(_.asJson))
  }


  //~
}
