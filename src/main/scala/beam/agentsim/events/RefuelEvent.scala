package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle
import scala.collection.JavaConverters._

class RefuelEvent(
  tick: Double,
  stall: ParkingStall,
  energyInJoules: Double,
  sessionDuration: Double,
  vehId: Id[Vehicle]
) extends Event(tick)
    with RefuelEventAttrs
    with HasPersonId {

  val attributes: scala.collection.mutable.Map[String, String] = scala.collection.mutable.Map()

  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])

  override def getEventType: String = RefuelEventAttrs.EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    if (attributes.isEmpty) {
      super.getAttributes.asScala.foreach { case (key, value) => attributes.put(key, value) }
      attributes.put(RefuelEventAttrs.ATTRIBUTE_ENERGY_DELIVERED, energyInJoules.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_SESSION_DURATION, sessionDuration.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_VEHICLE_ID, vehId.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_COST, stall.cost.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_PARKING_TYPE, stall.parkingType.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_PRICING_MODEL, stall.pricingModel.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_CHARGING_TYPE, stall.chargingPoint.toString)
      attributes.put(RefuelEventAttrs.ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)
    }
    attributes.asJava
  }
}

object RefuelEvent extends RefuelEventAttrs
