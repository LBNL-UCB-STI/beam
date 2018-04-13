package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle

class ParkEvent(time: Double, stall: ParkingStall, distance: Double, vehId: Id[Vehicle]) extends Event(time){
  import ParkEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    //Veh id
    //distance to dest
    //parking Id
    //cost
    //location
    attr.put(VEHICLE_ID, vehId.toString)
    attr.put(DISTANCE, distance.toString)

    for{
      stallValues <- stall.stallValues
      parkingId <- stallValues.parkingId
    } yield{
      attr.put(PARKING_ID, parkingId.toString)
    }

    attr.put(COST, stall.cost.toString)
    attr.put(LOCATION, stall.location.toString)

//    attr.put(PARKING_TYPE, stall.attributes.parkingType.toString)
//    attr.put(PRICING_MODEL, stall.attributes.pricingModel.toString)
//    attr.put(CHARGING_TYPE, stall.attributes.chargingType.toString)

    attr
  }
}

object ParkEvent {
  val EVENT_TYPE = "ParkEvent"

  val VEHICLE_ID = "VehicleId"
  val DISTANCE = "Distance"
  val PARKING_ID = "ParkingId"
  val COST = "Cost"
  val LOCATION = "Location"

//  val PARKING_TYPE = "ParkingType"
//  val PRICING_MODEL = "PricingModel"
//  val CHARGING_TYPE = "ChargingType"

}
