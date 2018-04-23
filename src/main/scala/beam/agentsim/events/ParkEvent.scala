package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

/**HasPersonId is added as Matsim ScoringFunction for population requires it**/
class ParkEvent(time: Double, stall: ParkingStall, distance: Double, vehId: Id[Vehicle]) extends Event(time) with ParkEventAttrs with HasPersonId{


  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])

  override def getEventType: String = ParkEventAttrs.EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    attr.put(ParkEventAttrs.ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_DISTANCE, distance.toString)

//    for{
//      stallValues <- stall.stallValues
//      parkingId <- stallValues.parkingId
//    } yield{
//      attr.put(ParkEventAttrs.ATTRIBUTE_PARKING_ID, parkingId.toString)
//    }

    attr.put(ParkEventAttrs.ATTRIBUTE_COST, stall.cost.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_LOCATION, stall.location.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PARKING_TYPE, stall.attributes.parkingType.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PRICING_MODEL, stall.attributes.pricingModel.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_CHARGING_TYPE, stall.attributes.chargingType.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PARKING_TAZ, stall.attributes.tazId.toString)

    attr
  }
}

//object ParkEvent {
//  val EVENT_TYPE = "ParkEvent"
//
//  val ATTRIBUTE_VEHICLE_ID = "vehicleId"
//  val ATTRIBUTE_DISTANCE = "distance"
//  val ATTRIBUTE_PARKING_ID = "parkingId"
//  val ATTRIBUTE_COST = "cost"
//  val ATTRIBUTE_LOCATION = "location"

//  val PARKING_TYPE = "ParkingType"
//  val PRICING_MODEL = "PricingModel"
//  val CHARGING_TYPE = "ChargingType"

//}
