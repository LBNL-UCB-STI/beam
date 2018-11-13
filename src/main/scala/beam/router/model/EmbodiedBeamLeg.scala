package beam.router.model

import beam.agentsim.agents.vehicles.{BeamVehicleType, PassengerSchedule}
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

case class EmbodiedBeamLeg(
  beamLeg: BeamLeg,
  beamVehicleId: Id[Vehicle],
  asDriver: Boolean,
  passengerSchedule: Option[PassengerSchedule],
  cost: Double,
  unbecomeDriverOnCompletion: Boolean,
  isPooledTrip: Boolean = false
) {

  val isHumanBodyVehicle: Boolean =
    BeamVehicleType.isHumanVehicle(beamVehicleId)
  val isRideHail: Boolean = BeamVehicleType.isRidehailVehicle(beamVehicleId)
}
