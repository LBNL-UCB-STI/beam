package beam.agentsim

import akka.actor.Actor
import beam.agentsim.agents.vehicles.BeamVehicle
import org.matsim.api.core.v01.Id

/*
 * Some clarification on nomenclature:
 *
 * Registered: resource is managed by the ResourceManager
 * CheckedIn / CheckedOut: resource is available / unavailable for use
 * InUse / Idle: resource is actively being used or not, but this does not signify available to other users
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

}
