package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle

/**HasPersonId is added as Matsim ScoringFunction for population requires it**/
class ParkEvent(time: Double, stall: ParkingStall, vehId: Id[Vehicle], driverId: String)
    extends Event(time){
  import ParkEvent._

  def getDriverId: String = driverId

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    attr.put(ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attr.put(ATTRIBUTE_COST, stall.cost.toString)
    attr.put(ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attr.put(ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, stall.attributes.parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, stall.attributes.pricingModel.toString)
    attr.put(ATTRIBUTE_CHARGING_TYPE, stall.attributes.chargingType.toString)
    attr.put(ATTRIBUTE_PARKING_TAZ, stall.attributes.tazId.toString)

    attr
  }
}

object ParkEvent  {
  val EVENT_TYPE: String = "ParkEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_COST: String = "cost"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
}