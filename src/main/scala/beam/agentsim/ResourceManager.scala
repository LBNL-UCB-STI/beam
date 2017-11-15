package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.vehicles.TempVehicle
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

/**
  *
  * @author dserdiuk, saf
  * @since 7/17/2017
  */

trait Resource[R] extends Identifiable[R] {


  var manager: Option[ActorRef]

  def notifyManagerResourceIsAvailable(whenWhere: SpaceTime): Unit = {
    manager.foreach(_ ! ResourceIsAvailableNotification(getId, whenWhere))
  }
}

object Resource {

  case class TellManagerResourceIsAvailable(when: SpaceTime)

  case class ResourceIsAvailableNotification[R](resourceId: Id[_ <: R], when: SpaceTime)

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

  def findResource(resourceId: Id[R]): Option[_ <: R]

}

object ResourceManager {

  /**
    * Concrete implementation that manages Resources of type [[TempVehicle]]
    */
  trait VehicleManager extends Actor with ResourceManager[TempVehicle]

}
