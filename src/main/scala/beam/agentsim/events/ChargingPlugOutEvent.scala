package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

case class ChargingPlugOutEvent(
  tick: Double,
  stall: ParkingStall,
  vehId: Id[Vehicle],
  primaryFuelLevel: Double,
  secondaryFuelLevel: Option[Double]
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {

  import ChargingPlugOutEvent._

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])

  val pricingModelString: String = stall.pricingModel
    .map {
      _.toString
    }
    .getOrElse("None")

  val chargingPointString: String = stall.chargingPointType
    .map {
      _.toString
    }
    .getOrElse("None")

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attributes.put(ATTRIBUTE_PRICE, stall.costInDollars.toString)
    attributes.put(ATTRIBUTE_PRIMARY_FUEL, primaryFuelLevel.toString)
    attributes.put(ATTRIBUTE_SECONDARY_FUEL, secondaryFuelLevel.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attributes.put(ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attributes.put(ATTRIBUTE_PARKING_TYPE, stall.parkingType.toString)
    attributes.put(ATTRIBUTE_PRICING_MODEL, pricingModelString)
    attributes.put(ATTRIBUTE_CHARGING_TYPE, chargingPointString)
    attributes.put(ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)
    attributes
  }

}

object ChargingPlugOutEvent {
  val EVENT_TYPE: String = "ChargingPlugOutEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_PRICE: String = "price"
  val ATTRIBUTE_PRIMARY_FUEL: String = "primaryFuelLevel"
  val ATTRIBUTE_SECONDARY_FUEL: String = "secondaryFuelLevel"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingPointType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"

}
