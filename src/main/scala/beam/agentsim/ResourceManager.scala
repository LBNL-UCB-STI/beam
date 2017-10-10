package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.concurrent.Future

/**
  * Created by dserdiuk on 6/15/17.
  */

trait Resource[R] {

  this: BeamAgent[BeamAgentData]=>

  override val id: Id[R]

  var manager: Option[ActorRef]

  def notifyManagerResourceIsAvailable(whenWhere:SpaceTime): Unit = {
    manager.foreach(_ ! ResourceIsAvailableNotification(self, id,whenWhere))
  }
}

object Resource {
  case class TellManagerResourceIsAvailable(when:SpaceTime)
  case class ResourceIsAvailableNotification[R](resourceRef: ActorRef, resourceId: Id[_],when:SpaceTime)
  case class AssignManager(managerRef:ActorRef)
}

trait ResourceManager[R] {
  val resources: Map[Id[R],ActorRef]=Map.empty
  def findResource(resourceId: Id[R]): Option[ActorRef]

}

object ResourceManager {
  trait VehicleManager extends Actor with ResourceManager[Vehicle]
}
