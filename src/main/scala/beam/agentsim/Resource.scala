package beam.agentsim

import akka.actor.ActorRef
import beam.agentsim.Resource._
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

object Resource {

  case class RegisterResource(resourceId: Id[_])

  case class CheckInResource(resourceId: Id[_], whenWhere: Option[SpaceTime])

  case class NotifyVehicleResourceIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule,
    beamVehicleState: BeamVehicleState,
    triggerId: Option[Long] // triggerId is included to facilitate debugging
  )

}

/**
  *
  * @author dserdiuk, saf
  * @since 7/17/2017
  */
trait Resource[R] extends Identifiable[R] {

  var manager: Option[ActorRef] = None

  def registerResource(newManager: ActorRef): Unit = {
    manager = Some(newManager)
    manager.foreach(_ ! RegisterResource(getId))
  }

}
