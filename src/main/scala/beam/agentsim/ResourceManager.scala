package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

import scala.reflect.ClassTag

/**
  *
  * @author dserdiuk, saf
  * @since 7/17/2017
  */

trait Resource[R] extends Identifiable[R] {

  var manager: Option[ActorRef]


  /**
    * Ensures that outgoing messages from resource use the correct ID type.
    * @param whenWhere when and where the resource is.
    * @param e implicit conversion to the [[Resource]]'s type for the [[Id]]
    * @tparam T Any ID type
    */
  def informManagerResourceIsAvailable[T](whenWhere: SpaceTime)(implicit e: Id[T]=>Id[R]):  Unit = {
    val x = ResourceIsAvailableNotification(getId, whenWhere)
    manager.foreach(_ ! x)
  }

}


object Resource {

  case class TellManagerResourceIsAvailable(when: SpaceTime)

  case class ResourceIsAvailableNotification(resourceId: Id[_], when: SpaceTime)

  case class AssignManager(managerRef: ActorRef)

}

/**
  * Responsible for maintaining a grouping of resources and their current locations.
  *
  * @tparam R The type of resource being managed
  */
trait ResourceManager[R <: Resource[R]] {

  this: Actor =>

  val resources: Map[Id[R], R] = Map.empty

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
