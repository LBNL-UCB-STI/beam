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
  vehicleId: Id[Vehicle],
  vehicleType: BeamVehicleType,
  personId: Id[Person],
  activityType: String,
  shiftStatus: ShiftStatus = NotApplicable
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {

  import RefuelSessionEvent._
  import ScalaEvent._

  override def getPersonId: Id[Person] = Id.create(vehicleId, classOf[Person])
  override def getEventType: String = EVENT_TYPE

  private val parkingZoneId = stall.parkingZoneId

  private val pricingModelString = stall.pricingModel.map { _.toString }.getOrElse("None")
  val chargingPointString: String = stall.chargingPointType.map { _.toString }.getOrElse("None")
  val parkingType: String = stall.parkingType.toString

  private val shiftStatusString = shiftStatus match {
    case NotApplicable =>
      ""
    case status =>
      status.toString
  }

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_FUEL_DELIVERED, energyInJoules.toString)
    attributes.put(ATTRIBUTE_DURATION, sessionDuration.toString)
    attributes.put(ATTRIBUTE_VEHICLE, vehicleId.toString)
    attributes.put(ATTRIBUTE_COST, stall.costInDollars.toString)
    attributes.put(ATTRIBUTE_PARKING_ZONE_ID, parkingZoneId.toString)
    attributes.put(ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attributes.put(ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attributes.put(ATTRIBUTE_PARKING_TYPE, parkingType)
    attributes.put(ATTRIBUTE_PRICING_MODEL, pricingModelString)
    attributes.put(ATTRIBUTE_CHARGING_TYPE, chargingPointString)
    attributes.put(ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)
    attributes.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType.id.toString)
    attributes.put(ATTRIBUTE_PERSON, personId.toString)
    attributes.put(ATTRIBUTE_ACTTYPE, activityType)
    attributes.put(ATTRIBUTE_SHIFT_STATUS, shiftStatusString)
    attributes
  }
}

object RefuelSessionEvent {
  val EVENT_TYPE: String = "RefuelSessionEvent"

  sealed trait ShiftStatus
  case object OnShift extends ShiftStatus
  case object OffShift extends ShiftStatus
  case object NotApplicable extends ShiftStatus
}
