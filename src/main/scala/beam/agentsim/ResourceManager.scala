package beam.agentsim

import akka.actor.{Actor, ActorRef}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.Id

import scala.concurrent.Future

/**
  * Created by dserdiuk on 6/15/17.
  */

trait Resource[R] {

  this: BeamAgent[BeamAgentData]=>

  override val id: Id[R]

  val resourceManager: Option[ActorRef] = None

  def notifyResourceAvailable(when: Future[SpaceTime]): Unit = {
    resourceManager.foreach(_ ! ResourceIsAvailableNotification(self, id, when))
  }
}

object Resource {

  case class ResourceIsAvailableNotification[R](resourceRef: ActorRef, resourceId: Id[R], when: Future[SpaceTime])

}

trait ResourceManager[R] {

  def findResource(resourceId: Id[R]): Option[ActorRef]

}
