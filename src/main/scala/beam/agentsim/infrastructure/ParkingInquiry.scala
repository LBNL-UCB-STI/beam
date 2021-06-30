package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.parking.ParkingMNL
import beam.agentsim.scheduler.HasTriggerId
import beam.utils.ParkingManagerIdGenerator
import org.matsim.api.core.v01.Id

/**
  * message sent from a ChoosesParking agent to a Parking Manager to request parking
  *
  * @param destinationUtm  the location where we are seeking nearby parking
  * @param activityType    the activity that the agent will partake in after parking
  * @param beamVehicle     an optional vehicle type (if applicable)
  * @param remainingTripData if vehicle can charge, this has the remaining range/tour distance data
  * @param valueOfTime     the value of time for the requestor
  * @param parkingDuration the duration an agent is parking for
  * @param reserveStall    whether or not we reserve a stall when we send this inquiry. used when simply requesting a cost estimate for parking.
  * @param requestId       a unique ID generated for this inquiry
  */
case class ParkingInquiry(
  destinationUtm: SpaceTime,
  activityType: String,
  vehicleManagerId: Id[VehicleManager],
  beamVehicle: Option[BeamVehicle] = None,
  remainingTripData: Option[ParkingMNL.RemainingTripData] = None,
  valueOfTime: Double = 0.0,
  parkingDuration: Double = 0,
  reserveStall: Boolean = true,
  requestId: Int = ParkingManagerIdGenerator.nextId, // note, this expects all Agents exist in the same JVM to rely on calling this singleton
  triggerId: Long,
) extends HasTriggerId {
  val activityTypeLowerCased: String = activityType.toLowerCase

  def isChargingRequestOrEV: Boolean = {
    beamVehicle match {
      case Some(vehicle) => vehicle.isPHEV || vehicle.isBEV
      case _             => activityTypeLowerCased == "charge"
    }
  }
}
