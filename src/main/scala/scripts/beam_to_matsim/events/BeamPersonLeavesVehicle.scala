package beam.utils.beam_to_matsim.events

import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

object BeamPersonLeavesVehicle {
  val EVENT_TYPE: String = "PersonLeavesVehicle"
  val ATTRIBUTE_PERSON_ID = "person"
  val ATTRIBUTE_VEHICLE_ID = "vehicle"

  def apply(time: Double, personId: String, vehicleId: String): BeamPersonLeavesVehicle =
    new BeamPersonLeavesVehicle(time, personId, vehicleId)

  def apply(genericEvent: Event): BeamPersonLeavesVehicle = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val time = genericEvent.getTime
    val vehicleId: String = attr(ATTRIBUTE_VEHICLE_ID)
    val personId: String = attr(ATTRIBUTE_PERSON_ID)

    BeamPersonLeavesVehicle(time, personId, vehicleId)
  }
}

case class BeamPersonLeavesVehicle(time: Double, personId: String, vehicleId: String) extends BeamEvent
