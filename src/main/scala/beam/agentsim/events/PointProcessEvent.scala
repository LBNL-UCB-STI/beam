package beam.agentsim.events

import java.util

import beam.agentsim.events.PointProcessEvent.PointProcessType
import beam.agentsim.events.PointProcessEvent.PointProcessType.Choice
import beam.utils.JsonUtils.syntax._
import enumeratum._
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.internal.HasPersonId

import scala.collection.immutable

/**
  * Events that encode vizualization data for beam-viz that take the form of points that display for a specified period of time
  */
class PointProcessEvent(
  time: Double,
  id: Id[Person],
  pointProcessType: PointProcessType,
  location: Coord,
  intensity: Double = 1.0
) extends Event(time)
    with HasPersonId {

  import beam.agentsim.events.PointProcessEvent._

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = id

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(
      ATTRIBUTE_VIZ_DATA,
      if (this.pointProcessType.equals(Choice)) createStarBurst(time, location, intensity).noSpaces
      else ""
    )
    attr
  }

}

object PointProcessEvent {

  val EVENT_TYPE = "pointProcess"
  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_INTENSITY: String = "intensity"
  val ATTRIBUTE_POINT_PROCESS_TYPE: String = "type"
  val ATTRIBUTE_AGENT_ID: String = "agent_id"

  sealed abstract class PointProcessType(val name: String) extends EnumEntry

  case object PointProcessType extends Enum[PointProcessType] with CirceEnum[PointProcessType] {
    val values: immutable.IndexedSeq[PointProcessType] = findValues
    case object Choice extends PointProcessType("CHOICE")

  }

  private def createStarBurst(newTime: Double, newLocation: Coord, newIntensity: Double): Json = {
    val jsonBuilder: Map[String, Json] = Map(
      "typ"       -> Json.fromString(EVENT_TYPE),
      "kind"      -> Json.fromString(PointProcessType.Choice.name),
      "startTime" -> Json.fromLong(newTime.toLong),
      "shp"       -> newLocation.asJson,
      "attrib"    -> Json.fromJsonObject(JsonObject.fromMap(Map("val" -> newIntensity.asJson)))
    )
    Json.fromJsonObject(JsonObject.fromMap(jsonBuilder))
  }

}
