package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.BeamAgent.BeamAgentData
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle


case class  RideHailingCompanyData(name: String) extends BeamAgentData

class RideHailingCompany(info: RideHailingCompanyData) extends VehicleManager {

  override def findResource(resourceId: Id[Vehicle]): Option[ActorRef] = ???

  override def getAllResources(): Iterable[ActorRef] = ???

  override def receive: Receive = ???
}
