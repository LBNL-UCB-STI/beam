package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

case class ChargingPlugInEvent(
  tick: Double,
  stall: ParkingStall,
  locationWGS: Coord,
  vehId: Id[Vehicle],
  primaryFuelLevel: Double,
  secondaryFuelLevel: Option[Double]
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {

  import ChargingPlugInEvent._
  import ScalaEvent._

  override def getEventType: String = EVENT_TYPE
  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])

  val pricingModelString: String = stall.pricingModel.map { _.toString }.getOrElse("None")
  val chargingPointString: String = stall.chargingPointType.map { _.toString }.getOrElse("None")

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_VEHICLE, vehId.toString)
    attributes.put(ATTRIBUTE_PRIMARY_FUEL_LEVEL, primaryFuelLevel.toString)
    attributes.put(ATTRIBUTE_SECONDARY_FUEL_LEVEL, secondaryFuelLevel.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_COST, stall.costInDollars.toString)
    attributes.put(ATTRIBUTE_LOCATION_X, locationWGS.getX.toString)
    attributes.put(ATTRIBUTE_LOCATION_Y, locationWGS.getY.toString)
    attributes.put(ATTRIBUTE_PARKING_TYPE, stall.parkingType.toString)
    attributes.put(ATTRIBUTE_PRICING_MODEL, pricingModelString)
    attributes.put(ATTRIBUTE_CHARGING_TYPE, chargingPointString)
    attributes.put(ATTRIBUTE_PARKING_TAZ, stall.tazId.toString)
    attributes
  }

}

object ChargingPlugInEvent {
  val EVENT_TYPE: String = "ChargingPlugInEvent"
}
