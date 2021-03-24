package beam.agentsim

import akka.actor.ActorRef
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.Token
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import beam.sim.Geofence
import org.matsim.api.core.v01.Id

object Resource {

  case class ReleaseParkingStall(stall: ParkingStall)

  case class NotifyVehicleIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule,
    beamVehicleState: BeamVehicleState,
    geofence: Option[Geofence],
    triggerId: Option[Long] // triggerId is included to facilitate debugging
  )

  // Optional triggerId and beamVehicleState are only used if the vehicle is completing a Refuel and needs to communicate SOC back
  case class NotifyVehicleOutOfService(vehicleId: Id[BeamVehicle])

  case class NotifyVehicleDoneRefuelingAndOutOfService(
    vehicleId: Id[BeamVehicle],
    whenWhere: SpaceTime,
    triggerId: Long,
    tick: Int,
    beamVehicleState: BeamVehicleState
  )

  case class TryToBoardVehicle(token: Token, who: ActorRef)

  case class Boarded(vehicle: BeamVehicle)

  case object NotAvailable

}
