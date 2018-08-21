package beam.agentsim

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.Resource._
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

import scala.concurrent.ExecutionContext

object Resource {

  case class RegisterResource(resourceId: Id[_])

  case class UnRegisterResource(resourceId: Id[_])

  case class CheckInResource(resourceId: Id[_], whenWhere: Option[SpaceTime])

  sealed trait CheckInResourceAck

  case object CheckInSuccess extends CheckInResourceAck

  case class CheckInFailure(msg: String) extends CheckInResourceAck

  case class CheckOutResource(resourceId: Id[_])

  case class NotifyResourceInUse(resourceId: Id[_], whenWhere: SpaceTime)

  case class NotifyResourceIdle(
    resourceId: Id[_],
    whenWhere: SpaceTime,
    passengerSchedule: PassengerSchedule
  )

  case class AssignManager(managerRef: ActorRef)

}

/**
  *
  * @author dserdiuk, saf
  * @since 7/17/2017
  */
trait Resource[R] extends Identifiable[R] {
  protected implicit val timeout: Timeout =
    akka.util.Timeout(5000, TimeUnit.SECONDS)

  var manager: Option[ActorRef] = None

  /**
    * Ensures that outgoing messages from resource use the correct ID type.
    *
    * @param whenWhere when and where the resource is.
    * @param e         implicit conversion to the [[Resource]]'s type for the [[Id]]
    * @tparam T Any ID type
    */
  def checkInResource[T](whenWhere: Option[SpaceTime], executionContext: ExecutionContext)(
    implicit e: Id[T] => Id[R]
  ): Unit = {
    manager match {
      case Some(managerRef) =>
        implicit val ec: ExecutionContext = executionContext
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
