package beam.agentsim.events

import java.util

import beam.agentsim.events.PointProcessEvent.PointProcessType
import beam.agentsim.utils.GeoUtils
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import enumeratum._
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.internal.HasPersonId

import scala.collection.immutable
import scala.math._

/**
  * BEAM
  */
class PointProcessEvent(time: Double, id: Id[Person],
                        pointProcessType: PointProcessType,
                        location: Coord,
                        intensity: Double = 1.0)
  extends Event(time) with HasPersonId {

  import beam.agentsim.events.PointProcessEvent._
  import beam.agentsim.events.PointProcessEvent.PointProcessConfig._

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = id

  def createStarBurst(time: Double, location: Coord, intensity: Double, pointProcessType: PointProcessType): Json = {
    val vizData  = Json.fromValues(0 until NUM_RAYS flatMap
      (rayIndex => FRAME_INDICES map (frameIndex => createRay(rayIndex, frameIndex))))

    val jsonBuilder:Map[String,Json] =Map(
      "typ"->Json.fromString(EVENT_TYPE),
      "kind"->Json.fromString(PointProcessType.Choice.name),
      "val"->Json.fromDoubleOrNull(intensity),
      "startTime"->Json.fromDoubleOrNull(time),
      "endTime"->vizData.asArray.get.last.asArray.get.last,
      "shp"->vizData.asJson
    )
    Json.fromJsonObject(JsonObject.fromMap(jsonBuilder))
  }


  def createRay(rayIndex: Int, frameIndex: Int): Json = {
    val len = RADIUS_FROM_ORIGIN(frameIndex)
    val x = location.getX + len * cos(DELTA_RADIANS * rayIndex)
    val y = location.getY + len * sin(DELTA_RADIANS * rayIndex)
    val thePos = GeoUtils.transform(new Coord(x, y))
    SpaceTime(thePos,(time + PACE_IN_TICKS_PER_FRAME * frameIndex).toLong).asJson
  }

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_VIZ_DATA, createStarBurst(time, location, intensity, pointProcessType).noSpaces)
    attr
  }
}

object PointProcessEvent {

  val EVENT_TYPE = "pointProcess"
  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_LOCATION: String = "location"
  val ATTRIBUTE_INTENSITY: String = "intensity"
  val ATTRIBUTE_POINT_PROCESS_TYPE: String = "type"
  val ATTRIBUTE_AGENT_ID: String = "agent_id"

  object PointProcessConfig {
    val NUM_RAYS = 10
    val PACE_IN_TICKS_PER_FRAME = 25
    val RADIAL_LENGTH: Double = 350
    val DIRECTION_OUT: Boolean = true
    val NUM_FRAMES: Int = 4
    val FRAME_INDICES: Range = if (DIRECTION_OUT) {
      0 until NUM_FRAMES
    } else {
      NUM_FRAMES - 1 to 0
    }
    val RADIUS_FROM_ORIGIN: Vector[Double] = (0 until NUM_FRAMES).map(i => RADIAL_LENGTH * i / (NUM_FRAMES - 1)).toVector
    val DELTA_RADIANS: Double = 2.0 * Pi / NUM_RAYS
  }


  sealed abstract class PointProcessType(val name: String) extends EnumEntry
  case object PointProcessType extends Enum[PointProcessType] with CirceEnum[PointProcessType] {
    val values: immutable.IndexedSeq[PointProcessType] = findValues
    case object Choice extends PointProcessType("CHOICE")
  }

}
