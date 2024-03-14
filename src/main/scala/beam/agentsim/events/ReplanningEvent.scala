package beam.agentsim.events

import beam.agentsim.events.ReplanningEvent._
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import java.util
import scala.jdk.CollectionConverters.mapAsScalaMapConverter

/**
  * Event capturing the details of a trip replanning at runtime.
  */
case class ReplanningEvent(
  tick: Double,
  personId: Id[Person],
  reason: String,
  vehicleId: Option[Id[Vehicle]] = None,
  legMode: Option[BeamMode] = None,
  start: Coord,
  end: Option[Coord] = None
) extends Event(tick)
    with ScalaEvent {

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr = super.getAttributes
    attr.put(ATTRIBUTE_PERSON, personId.toString)
    attr.put(ATTRIBUTE_VEHICLE, vehicleId.fold("")(_.toString))
    attr.put(ATTRIBUTE_LEG_MODE, legMode.fold("")(_.value))
    attr.put(ATTRIBUTE_REPLANNING_REASON, reason)
    attr.put(ATTRIBUTE_START_COORDINATE_X, start.getX.toString)
    attr.put(ATTRIBUTE_START_COORDINATE_Y, start.getY.toString)
    attr.put(ATTRIBUTE_END_COORDINATE_X, end.fold("")(_.getX.toString))
    attr.put(ATTRIBUTE_END_COORDINATE_Y, end.fold("")(_.getY.toString))
    attr
  }
}

object ReplanningEvent {
  val EVENT_TYPE = "Replanning"
  val ATTRIBUTE_PERSON = "person"
  val ATTRIBUTE_VEHICLE = "vehicle"
  val ATTRIBUTE_REPLANNING_REASON = "reason"
  val ATTRIBUTE_LEG_MODE = "mode"
  val ATTRIBUTE_START_COORDINATE_X = "startX"
  val ATTRIBUTE_START_COORDINATE_Y = "startY"
  val ATTRIBUTE_END_COORDINATE_X = "endX"
  val ATTRIBUTE_END_COORDINATE_Y = "endY"

  def apply(genericEvent: Event): ReplanningEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val personId: Id[Person] = Id.createPersonId(attr(ATTRIBUTE_PERSON))
    val reason: String = attr.getOrElse(ATTRIBUTE_REPLANNING_REASON, "")
    val vehicleId: Option[Id[Vehicle]] = attr.get(ATTRIBUTE_VEHICLE).map(Id.createVehicleId)
    val legMode: Option[BeamMode] = BeamMode.fromString(attr.getOrElse(ATTRIBUTE_LEG_MODE, ""))
    val startX: Double = attr.getOrElse(ATTRIBUTE_START_COORDINATE_X, "0.0").replaceFirst("^$", "0.0").toDouble
    val startY: Double = attr.getOrElse(ATTRIBUTE_START_COORDINATE_Y, "0.0").replaceFirst("^$", "0.0").toDouble
    val endX: Option[Double] = attr.get(ATTRIBUTE_END_COORDINATE_X).map(_.replaceFirst("^$", "0.0").toDouble)
    val endY: Option[Double] = attr.get(ATTRIBUTE_END_COORDINATE_Y).map(_.replaceFirst("^$", "0.0").toDouble)
    ReplanningEvent(
      time,
      personId,
      reason,
      vehicleId,
      legMode,
      new Coord(startX, startY),
      for {
        x <- endX
        y <- endY
      } yield new Coord(x, y)
    )
  }
}
