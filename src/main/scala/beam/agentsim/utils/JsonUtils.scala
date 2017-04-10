package beam.agentsim.utils

import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.{BeamGraphPath, BeamLeg}
import io.circe.{Encoder, Json, JsonObject}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils
import io.circe.syntax._
import scala.xml.XML

/**
  * Created by sfeygin on 3/28/17.
  */
object JsonUtils {

  implicit val encodeLeg: Encoder[BeamLeg] = (a: BeamLeg) => {
    val jsonBuilder: Map[String, Json] = Map(
      "typ" -> Json.fromString(a.mode), "shp" -> a.graphPath.asJson)
    Json.fromJsonObject(JsonObject.fromMap(jsonBuilder))
  }

  implicit val encodeGraphPath: Encoder[BeamGraphPath] = (a: BeamGraphPath) => {
    Json.fromValues(a.trajectory.map(t => coordTimeToJson(t._1, t._2)))
  }

  def coordTimeToJson(c: Coord, t: Long): Json = {
    Json.fromValues(Seq[Json](
      Json.fromDoubleOrNull(MathUtils.roundDouble(c.getX, 5)),
      Json.fromDoubleOrNull(MathUtils.roundDouble(c.getY, 5)),
      Json.fromLong(t)))
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
}
