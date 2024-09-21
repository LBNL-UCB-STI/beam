package beam.agentsim.events

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile
import beam.agentsim.events.ShiftEvent._
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.events.Event

import java.util

/**
  * Event capturing the details of a ride hail shift start/end.
  */
case class ShiftEvent(
  tick: Double,
  shiftEventType: ShiftEventType,
  driverId: String,
  vehicle: BeamVehicle,
  emissionsProfile: Option[EmissionsProfile] = None
) extends Event(tick)
    with ScalaEvent {

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_SHIFT_EVENT_TYPE, shiftEventType.toString)
    attributes.put(ATTRIBUTE_DRIVER, driverId)
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehicle.id.toString)
    attributes.put(ATTRIBUTE_VEHICLE_TYPE, vehicle.beamVehicleType.id.toString)
    attributes.put(ATTRIBUTE_FUEL_LEVEL, vehicle.primaryFuelLevelInJoules.toString)
    attributes.put(
      ATTRIBUTE_EMISSIONS_PROFILE,
      emissionsProfile.map(BeamVehicleUtils.buildEmissionsString).getOrElse("")
    )
    attributes
  }
}

object ShiftEvent {
  val EVENT_TYPE: String = "ShiftEvent"
  val ATTRIBUTE_SHIFT_EVENT_TYPE: String = "shiftEventType"
  val ATTRIBUTE_DRIVER: String = "driver"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleType"
  val ATTRIBUTE_FUEL_LEVEL: String = "primaryFuelLevel"
  val ATTRIBUTE_EMISSIONS_PROFILE: String = "emissions"

  sealed trait ShiftEventType
  case object EndShift extends ShiftEventType
  case object StartShift extends ShiftEventType
}
