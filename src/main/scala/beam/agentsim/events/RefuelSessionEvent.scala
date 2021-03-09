package beam.agentsim.events

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.RefuelSessionEvent.{NotApplicable, ShiftStatus}
import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

import java.util

case class RefuelSessionEvent(
  tick: Double,
  stall: ParkingStall,
  energyInJoules: Double,
  sessionStartingFuelLevelInJoules: Double,
  sessionDuration: Double,
  val vehId: Id[Vehicle],
  vehicleType: BeamVehicleType,
  shiftStatus: ShiftStatus = NotApplicable
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {

  import RefuelSessionEvent._

  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])
  override def getEventType: String = EVENT_TYPE

  val parkingZoneId = stall.parkingZoneId

  val pricingModelString = stall.pricingModel.map { _.toString }.getOrElse("None")
  val chargingPointString = stall.chargingPointType.map { _.toString }.getOrElse("None")
  val parkingType: String = stall.parkingType.toString

  val shiftStatusString = shiftStatus match {
    case NotApplicable =>
      ""
    case status =>
      status.toString
  }

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_ENERGY_DELIVERED, energyInJoules.toString)
    attributes.put(ATTRIBUTE_SESSION_DURATION, sessionDuration.toString)
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attributes.put(ATTRIBUTE_PRICE, stall.costInDollars.toString)
    attributes.put(ATTRIBUTE_PARKING_ZONE_ID, parkingZoneId.toString)
    attributes.put(ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attributes.put(ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attributes.put(ATTRIBUTE_PARKING_TYPE, parkingType)
    attributes.put(ATTRIBUTE_PRICING_MODEL, pricingModelString)
    attributes.put(ATTRIBUTE_CHARGING_TYPE, chargingPointString)
    attributes.put(ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)
    attributes.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType.id.toString)
    attributes.put(ATTRIBUTE_SHIFT_STATUS, shiftStatusString)
    attributes
  }
}

object RefuelSessionEvent {
  val EVENT_TYPE: String = "RefuelSessionEvent"
  val ATTRIBUTE_SESSION_DURATION: String = "duration"
  val ATTRIBUTE_ENERGY_DELIVERED: String = "fuel"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_PRICE: String = "price"
  val ATTRIBUTE_PARKING_ZONE_ID: String = "parkingZoneId"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleType"
  val ATTRIBUTE_SHIFT_STATUS: String = "shiftStatus"

  sealed trait ShiftStatus
  case object OnShift extends ShiftStatus
  case object OffShift extends ShiftStatus
  case object NotApplicable extends ShiftStatus
}
