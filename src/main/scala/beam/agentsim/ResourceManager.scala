package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.vehicles.TempVehicle
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}
import org.matsim.vehicles.Vehicle

/**
  *
  * @author dserdiuk
  * @since 7/17/2017
  */

trait Resource[R] extends Identifiable[R] {

  /**
    * The [[beam.agentsim.ResourceManager]] who is currently managing this vehicle. Must
    * not ever be None ([[TempVehicle]]s start out with a manager even if no driver is initially assigned.
    * There is usually only ever one manager for a vehicle.
    *
    * @todo consider adding owner as an attribute of the vehicle as well, since this is somewhat distinct
    *       from driving... (SAF 11/17)
    */
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

trait ResourceManager[R <: Resource[R]] {
  this: Actor =>

  val resources: Map[Id[R], R] = Map.empty

  def findResource(resourceId: Id[R]): Option[_<:R]

}

object ResourceManager {

  trait VehicleManager extends Actor with ResourceManager[TempVehicle]{

  }

}
