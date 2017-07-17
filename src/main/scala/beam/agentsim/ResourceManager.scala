package beam.agentsim

import akka.actor.{Actor, ActorRef}
import org.matsim.api.core.v01.{Id, Identifiable}

/**
  * Created by dserdiuk on 6/15/17.
  */

case class User(userId: Id[User] )

trait Resource

trait ResourceManager[IdType] {

  def findResource(resourceId : Id[IdType]) : Option[ActorRef]

}
