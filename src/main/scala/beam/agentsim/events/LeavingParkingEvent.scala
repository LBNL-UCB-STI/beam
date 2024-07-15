package beam.agentsim.events

import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile.EmissionsProfile
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle

import java.util
import scala.collection.JavaConverters._

case class LeavingParkingEvent(
  time: Double,
  driverId: String,
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  parkingDuration: Double,
  score: Double,
  parkingType: ParkingType,
  pricingModel: Option[PricingModel],
  ChargingPointType: Option[ChargingPointType],
  emissionsProfile: Option[EmissionsProfile]
) extends Event(time)
    with ScalaEvent {
  import LeavingParkingEvent._
  import ScalaEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_SCORE, score.toString)
    attr.put(ATTRIBUTE_DRIVER, driverId)
    attr.put(ATTRIBUTE_VEHICLE, vehicleId.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, optionalToString(pricingModel))
    attr.put(ATTRIBUTE_CHARGING_TYPE, optionalToString(ChargingPointType))
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)
    attr.put(ATTRIBUTE_EMISSIONS_PROFILE, emissionsProfile.map(BeamVehicleUtils.buildEmissionsString).getOrElse(""))
    attr.put(ATTRIBUTE_DURATION, parkingDuration.toString)
    attr.put(ATTRIBUTE_COST, pricingModel.map(_.costInDollars.toString).getOrElse("0"))
    attr
  }
}

object LeavingParkingEvent {
  import ScalaEvent._

  private def optionalToString[T](opt: Option[T]): String =
    opt match {
      case None        => "None"
      case Some(value) => value.toString
    }

  def getParkingDuration(stall: ParkingStall, parkingDepartureTime: Double): Double = {
    if (parkingDepartureTime >= stall.getParkingTime) {
      parkingDepartureTime - stall.getParkingTime
    } else {
      0.0 // Return 0 if departure time is before arrival time
    }
  }

  val EVENT_TYPE: String = "LeavingParkingEvent"

  def apply(
    time: Double,
    stall: ParkingStall,
    score: Double,
    driverId: String,
    vehId: Id[Vehicle],
    emissionsProfile: Option[EmissionsProfile]
  ): LeavingParkingEvent = {
    new LeavingParkingEvent(
      time,
      driverId,
      vehId,
      stall.tazId,
      getParkingDuration(stall, time),
      score,
      stall.parkingType,
      stall.pricingModel,
      stall.chargingPointType,
      emissionsProfile
    )
  }

  def apply(genericEvent: Event): LeavingParkingEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val personId: String = attr(ATTRIBUTE_DRIVER)
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val score: Double = attr(ATTRIBUTE_SCORE).toDouble
    val parkingType: ParkingType = ParkingType(attr(ATTRIBUTE_PARKING_TYPE))
    // TODO: cost (fee) should be an attribute of this event, but adding it will break a lot of tests
    val pricingModel: Option[PricingModel] =
      attr.get(ATTRIBUTE_PRICING_MODEL).flatMap(PricingModel(_, attr.getOrElse(ATTRIBUTE_COST, "0")))
    val chargingPointType: Option[ChargingPointType] = attr.get(ATTRIBUTE_CHARGING_TYPE).flatMap(ChargingPointType(_))
    val emissionsProfile = attr.get(ATTRIBUTE_EMISSIONS_PROFILE).flatMap(BeamVehicleUtils.parseEmissionsString(_))
    val duration: Double = attr(ATTRIBUTE_DURATION).toDouble
    LeavingParkingEvent(
      time,
      personId,
      vehicleId,
      tazId,
      duration,
      score,
      parkingType,
      pricingModel,
      chargingPointType,
      emissionsProfile
    )
  }
}
