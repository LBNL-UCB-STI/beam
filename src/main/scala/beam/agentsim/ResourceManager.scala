package beam.agentsim

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import beam.agentsim.Resource._
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

import scala.concurrent.ExecutionContext

/**
  *
  * @author dserdiuk, saf
  * @since 7/17/2017
  */

trait Resource[R] extends Identifiable[R] {
  protected implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  var manager: Option[ActorRef] = None

  /**
    * Ensures that outgoing messages from resource use the correct ID type.
    *
    * @param whenWhere when and where the resource is.
    * @param e         implicit conversion to the [[Resource]]'s type for the [[Id]]
    * @tparam T Any ID type
    */
  def checkInResource[T](whenWhere: Option[SpaceTime], executionContext: ExecutionContext)(implicit e: Id[T] => Id[R]): Unit = {
    manager match {
      case Some(managerRef) =>
        implicit val ec = executionContext
        val response = managerRef ? CheckInResource(getId, whenWhere)
        response.mapTo[CheckInResourceAck].map {
          case CheckInSuccess =>
          case CheckInFailure(msg) =>
            throw new RuntimeException(s"Resource could not be checked in: $msg")
        }
      case None =>
        throw new RuntimeException(s"Resource manager not defined for resource $getId")
    }
  }
  def registerResource[T](newManager: ActorRef)(implicit e: Id[T] => Id[R]): Unit = {
    manager = Some(newManager)
    manager.foreach(_ ! RegisterResource(getId))
  }

}

/*
 * Some clarification on nomenclature:
 *
 * Registered: resource is managed by the ResourceManager
 * CheckedIn / CheckedOut: resource is available / unavailable for use
 * InUse / Idle: resource is actively being used or not, but this does not signify available to other users
 */

object Resource {

  case class RegisterResource(resourceId: Id[_])

  case class UnRegisterResource(resourceId: Id[_])

  case class CheckInResource(resourceId: Id[_], whenWhere: Option[SpaceTime])
  sealed trait CheckInResourceAck
  case object CheckInSuccess extends CheckInResourceAck
  case class CheckInFailure(msg: String) extends CheckInResourceAck

  case class CheckOutResource(resourceId: Id[_])

  case class NotifyResourceInUse(resourceId: Id[_], whenWhere: SpaceTime)

  case class NotifyResourceIdle(resourceId: Id[_], whenWhere: SpaceTime)

  case class AssignManager(managerRef: ActorRef)


}

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
