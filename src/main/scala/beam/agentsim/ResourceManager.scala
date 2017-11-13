package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}
import org.matsim.vehicles.Vehicle

/**
  *
  * @author dserdiuk
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

  case class ResourceIsAvailableNotification[R](resourceId: Id[_<:R], when: SpaceTime)

  case class AssignManager(managerRef: ActorRef)

}

trait ResourceManager[R] {
  val resources: Map[Id[R], ActorRef] = Map.empty

  def findResource(resourceId: Id[R]): Option[ActorRef]

}

object ResourceManager {

  trait VehicleManager extends Actor with ResourceManager[Vehicle]

}
