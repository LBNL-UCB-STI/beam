package beam.agentsim

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id

object Resource {

  case class CheckInResource(resourceId: Id[_], whenWhere: Option[SpaceTime])

  case class NotifyVehicleIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule,
    beamVehicleState: BeamVehicleState,
    triggerId: Option[Long] // triggerId is included to facilitate debugging
  )

  case class TryToBoardVehicle(what: Id[BeamVehicle], who: ActorRef)

  case object Boarded

  case object NotAvailable

}
