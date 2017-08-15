package beam.agentsim.agents.vehicles.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle.{GetVehicleLocationEvent, StreetVehicle}
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusReponse}
import beam.sim.{BeamServices, HasServices}
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
  def props(beamServices: BeamServices, householdId: Id[Household], matSimHousehold: Household, houseHoldVehicles: Map[Id[Vehicle], ActorRef], membersActors: Map[Id[Person], ActorRef]) = {
    Props(classOf[HouseholdActor], beamServices, householdId, matSimHousehold, houseHoldVehicles, membersActors)
  }
  case class MobilityStatusInquiry(personId: Id[Person])
  case class MobilityStatusReponse(streetVehicle: StreetVehicle)
}

class HouseholdActor(services: BeamServices,
                     id: Id[households.Household],
                     matSimHouseHold : org.matsim.households.Household,
                     vehicleActors: Map[Id[Vehicle], ActorRef],
                     memberActors: Map[Id[Person], ActorRef] )
  extends VehicleManager with ActorLogging with HasServices{
  override val beamServices: BeamServices = services

  val _vehicles: Vector[Id[Vehicle]] = vehicleActors.keys.toVector
  val _members: Vector[Id[Person]] = memberActors.keys.toVector
  var _vehicleAssignments: Map[Id[Person],Id[Vehicle]] = Map[Id[Person],Id[Vehicle]]()
  var _vehicleToStreetVehicle: Map[Id[Vehicle],StreetVehicle] = Map()

  override def findResource(vehicleId: Id[Vehicle]): Option[ActorRef] = vehicleActors.get(vehicleId)

  override def receive: Receive = {
    case InitializeTrigger(_) =>
      log.debug(s"Household ${self.path.name} has been initialized ")
      for(i <- (_vehicles.indices.toSet ++ _members.indices.toSet)){
        if(i < _vehicles.size & i < _members.size){
          _vehicleAssignments = _vehicleAssignments + (_members(i) -> _vehicles(i))
        }
      }
      _vehicles.foreach(veh => services.vehicleRefs.get(veh) ! GetVehicleLocationEvent)
    case MobilityStatusInquiry(personId) =>
      val streetVehicle = _vehicleAssignments.get(personId) match {
        case Some(vehId) =>
          StreetVehicle(vehId, )
      }

      sender() ! MobilityStatusReponse()
    case _ => throw new UnsupportedOperationException
  }

}

