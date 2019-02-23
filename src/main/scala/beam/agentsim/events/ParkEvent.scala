package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

/**HasPersonId is added as Matsim ScoringFunction for population requires it**/
class ParkEvent(time: Double, stall: ParkingStall, vehId: Id[Vehicle], driverId: String)
    extends Event(time)
    with ParkEventAttrs {

  def getDriverId: String = driverId

  override def getEventType: String = ParkEventAttrs.EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    attr.put(ParkEventAttrs.ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_COST, stall.cost.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PARKING_TYPE, stall.parkingType.toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PRICING_MODEL, stall.pricingModel.getOrElse("none").toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_CHARGING_TYPE, stall.chargingPoint.getOrElse("none").toString)
    attr.put(ParkEventAttrs.ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)

    attr
  }
}

object ParkEvent extends ParkEventAttrs
