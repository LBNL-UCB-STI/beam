package beam.agentsim.agents.vehicles.household

import akka.actor.{ActorLogging, ActorRef}
import beam.agentsim.agents.vehicles.VehicleManager
import org.matsim.api.core.v01.Id
import org.matsim.households
import org.matsim.vehicles.Vehicle
/**
  * @author dserdiuk
  */

object HouseholdActor {

  def buildActorName(id: Id[households.Household]) = {
    s"household-${id.toString}"
  }
}

class HouseholdActor(id: Id[households.Household], matSimHouseHold : org.matsim.households.Household,
                     vehicleActors: Map[Id[Vehicle], ActorRef])
  extends VehicleManager with ActorLogging {


  override def findResource(vehicleId: Id[Vehicle]): Option[ActorRef] = vehicleActors.get(vehicleId)

  override def getAllResources(): Iterable[ActorRef] = vehicleActors.values

  override def receive: Receive = {
    case _ => throw new UnsupportedOperationException
  }
}

