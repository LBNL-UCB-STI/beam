package beam.agentsim

import akka.actor.ActorRef
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id

object Resource {

  case class ReleaseParkingStall(stallId: Id[ParkingStall])

  case class NotifyVehicleIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule,
    beamVehicleState: BeamVehicleState,
    triggerId: Option[Long] // triggerId is included to facilitate debugging
  )

  case class NotifyVehicleOutOfService(vehicleId: Id[BeamVehicle])

  case class TryToBoardVehicle(what: Id[BeamVehicle], who: ActorRef)

  case class Boarded(vehicle: BeamVehicle)

  case object NotAvailable

}
