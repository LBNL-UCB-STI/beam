package beam.agentsim.events

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle

import java.util
import scala.collection.JavaConverters._

case class OvernightParkingEvent(
  time: Double,
  vehicleId: Id[Vehicle],
  vehicleTypeId: Option[String],
  emissionsProfile: Option[EmissionsProfile]
) extends Event(time)
    with ScalaEvent {
  import OvernightParkingEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_VEHICLE_TYPE, optionalToString(vehicleTypeId))
    attr.put(ATTRIBUTE_EMISSIONS_PROFILE, emissionsProfile.map(BeamVehicleUtils.buildEmissionsString).getOrElse(""))
    attr
  }
}

object OvernightParkingEvent {

  private def optionalToString[T](opt: Option[T]): String =
    opt match {
      case None        => "None"
      case Some(value) => value.toString
    }

  val EVENT_TYPE: String = "OvernightParkingEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleTypeId"
  val ATTRIBUTE_EMISSIONS_PROFILE: String = "emissions"

  def apply(
    time: Double,
    vehicleId: Id[Vehicle],
    activityData: IndexedSeq[BeamVehicle.VehicleActivityData],
    emissionProfile: EmissionsProfile
  ): OvernightParkingEvent = {
    val vehicleType = activityData.headOption.map(_.vehicleType.id.toString)
    new OvernightParkingEvent(time, vehicleId, vehicleType, Some(emissionProfile))
  }

  def apply(genericEvent: Event): OvernightParkingEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val vehicleTypeId: Option[String] = attr.get(ATTRIBUTE_VEHICLE_TYPE)
    val emissionsProfile = attr.get(ATTRIBUTE_EMISSIONS_PROFILE).flatMap(BeamVehicleUtils.parseEmissionsString(_))

    new OvernightParkingEvent(
      time,
      vehicleId,
      vehicleTypeId,
      emissionsProfile
    )
  }
}
