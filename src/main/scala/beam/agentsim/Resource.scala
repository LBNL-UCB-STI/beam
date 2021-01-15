package beam.agentsim

import akka.actor.ActorRef
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, Token}
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.Geofence
import org.matsim.api.core.v01.Id

object Resource {

  case class ReleaseParkingStall(stallId: Int, geoId: Id[_])

  case class NotifyVehicleIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule,
    beamVehicleState: BeamVehicleState,
    geofence: Option[Geofence],
    triggerId: Option[Long] // triggerId is included to facilitate debugging
  )

  case class NotifyVehicleOutOfService(vehicleId: Id[BeamVehicle])

  case class TryToBoardVehicle(token: Token, who: ActorRef)

  case class Boarded(vehicle: BeamVehicle)

  case object NotAvailable

}
