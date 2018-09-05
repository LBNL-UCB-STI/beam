package beam.agentsim

import akka.actor.Actor
import beam.agentsim.Resource.NotifyResourceIdle
import beam.agentsim.agents.vehicles.BeamVehicle.BeamVehicleState
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id

/*
 * Some clarification on nomenclature:
 *
 * Registered: resource is managed by the ResourceManager
 * CheckedIn / CheckedOut: resource is available / unavailable for use
 * InUse / Idle: resource is actively being used or not, but this does not signify available to other users
 *
 * E.g. in a vehicle sharing setting, a vehicle would be Checked Out once the user's session starts (key card is swiped)
 * but if the user parks the vehicle while shopping, it would be Idle. Only when the user checks the vehicle back in
 * does it become available to other users.
 */

/**
  * Responsible for maintaining a grouping of resources and their current locations.
  *
  * @tparam R The type of resource being managed
  */
trait ResourceManager[R <: Resource[R]] {

  this: Actor =>

  val resources: collection.mutable.Map[Id[R], R]

  def findResource(resourceId: Id[R]): Option[R] = {
    resources.get(resourceId)
  }

}

object ResourceManager {

  /**
    * Concrete implementation that manages Resources of type [[BeamVehicle]]
    */
  trait VehicleManager extends Actor with ResourceManager[BeamVehicle]

  case class NotifyVehicleResourceIdle(
    override val resourceId: Id[_],
    override val whenWhere: Option[SpaceTime],
    val passengerSchedule: PassengerSchedule,
    val beamVehicleState: BeamVehicleState,
    val triggerId: Option[Long] // triggerId is included to facilitate debugging
  ) extends NotifyResourceIdle

}
