package beam.utils.beam_to_matsim.events

import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

object BeamPersonEntersVehicle {
  val EVENT_TYPE: String = "PersonEntersVehicle"
  val ATTRIBUTE_PERSON_ID = "person"
  val ATTRIBUTE_VEHICLE_ID = "vehicle"

  def apply(time: Double, personId: String, vehicleId: String): BeamPersonEntersVehicle =
    new BeamPersonEntersVehicle(time, personId, vehicleId)

  def apply(genericEvent: Event): BeamPersonEntersVehicle = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val time = genericEvent.getTime
    val vehicleId: String = attr(ATTRIBUTE_VEHICLE_ID)
    val personId: String = attr(ATTRIBUTE_PERSON_ID)

    BeamPersonEntersVehicle(time, personId, vehicleId)
  }
}

case class BeamPersonEntersVehicle(time: Double, personId: String, vehicleId: String) extends BeamEvent
