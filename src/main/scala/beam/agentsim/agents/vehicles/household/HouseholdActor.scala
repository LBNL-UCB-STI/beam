package beam.agentsim.agents.vehicles.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.VehicleManager
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.households
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
/**
  * @author dserdiuk
  */

object HouseholdActor {

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None) = {
    s"household-${id.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }
  def props(householdId: Id[Household], matSimHousehold: Household, houseHoldVehicles: Map[Id[Vehicle], ActorRef], membersActors: Map[Id[Person], ActorRef]) = {
    Props(classOf[HouseholdActor],
      householdId, matSimHousehold, houseHoldVehicles, membersActors)
  }
}

class HouseholdActor(id: Id[households.Household], matSimHouseHold : org.matsim.households.Household,
                     vehicleActors: Map[Id[Vehicle], ActorRef], membersActors: Map[Id[Person], ActorRef] )
  extends VehicleManager with ActorLogging {


  override def findResource(vehicleId: Id[Vehicle]): Option[ActorRef] = vehicleActors.get(vehicleId)

  override def receive: Receive = {
    case InitializeTrigger(_) =>
      log.debug(s"HouseHold ${self.path.name} has been initialized ")
    case _ => throw new UnsupportedOperationException
  }
}

