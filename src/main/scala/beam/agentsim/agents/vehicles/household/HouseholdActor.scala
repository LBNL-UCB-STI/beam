package beam.agentsim.agents.vehicles.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle.{StreetVehicle, UpdateTrajectory, VehicleLocationRequest, VehicleLocationResponse}
import beam.agentsim.agents.vehicles.{Trajectory, VehicleManager}
import beam.agentsim.agents.vehicles.household.HouseholdActor.{MemberWithRank, MobilityStatusInquiry, MobilityStatusReponse}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.BeamStreetPath
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.{Coord, Id}
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
  def props(beamServices: BeamServices, householdId: Id[Household], matSimHousehold: Household, houseHoldVehicles: Map[Id[Vehicle], ActorRef], membersActors: Map[Id[Person], ActorRef], homeCoord: Coord) = {
    Props(classOf[HouseholdActor], beamServices, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord)
  }
  case class MobilityStatusInquiry(personId: Id[Person])
  case class MobilityStatusReponse(streetVehicle: Vector[StreetVehicle])
  case class MemberWithRank(personId: Id[Person], rank: Option[Int])
}

class HouseholdActor(services: BeamServices,
                     id: Id[households.Household],
                     matSimHouseHold : org.matsim.households.Household,
                     vehicleActors: Map[Id[Vehicle], ActorRef],
                     memberActors: Map[Id[Person], ActorRef],
                     homeCoord: Coord
                    )
  extends VehicleManager with ActorLogging with HasServices {

  import scala.concurrent.ExecutionContext.Implicits.global

  override val beamServices: BeamServices = services

  val _vehicles: Vector[Id[Vehicle]] = vehicleActors.keys.toVector
  val _members: Vector[MemberWithRank] = memberActors.keys.toVector.map(memb => MemberWithRank(memb,lookupMemberRank(memb)))
  var _vehicleAssignments: Map[Id[Person], Id[Vehicle]] = Map[Id[Person], Id[Vehicle]]()
  var _vehicleToStreetVehicle: Map[Id[Vehicle], StreetVehicle] = Map()

  override def findResource(vehicleId: Id[Vehicle]): Option[ActorRef] = vehicleActors.get(vehicleId)

  MobilityStatusReponse

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(tick),triggerId) =>
      //TODO this needs to be updated to differentiate between CAR and BIKE and allow individuals to get assigned one of each
      log.debug(s"Household ${self.path.name} has been initialized ")
      val sortedMembers = _members.sortWith(sortByRank)
      for (i <- (_vehicles.indices.toSet ++ sortedMembers.indices.toSet)) {
        if (i < _vehicles.size & i < sortedMembers.size) {
          _vehicleAssignments = _vehicleAssignments + (sortedMembers(i).personId -> _vehicles(i))
        }
      }
      //Initialize all vehicles to have a stationary trajectory starting at time zero
      val initialLocation = SpaceTime(homeCoord.getX, homeCoord.getY, 0L)
      val initialTrajectory = Trajectory(BeamStreetPath(Vector(""),None,Some(Vector())))
      _vehicles.foreach { veh =>
        services.vehicleRefs.get(veh).get ! UpdateTrajectory(initialTrajectory)
        //TODO following mode should come from the vehicle
        _vehicleToStreetVehicle = _vehicleToStreetVehicle + (veh -> StreetVehicle(veh, initialLocation, CAR, true))
      }
      sender() ! CompletionNotice(triggerId)
    case MobilityStatusInquiry(personId) =>
      _vehicleAssignments.get(personId) match {
        case Some(vehId) =>
          val streetVehicles = _vehicleToStreetVehicle.get(vehId) match {
            case Some(streetVehicle) =>
              Vector(streetVehicle)
            case None =>
              Vector()
          }
          sender() ! MobilityStatusReponse(streetVehicles)
        case None =>
          sender() ! MobilityStatusReponse(Vector())
      }
    case msg@_ =>
      log.warning(s"Unrecognized message ${msg}")
  }

  def lookupMemberRank(member: Id[Person]): Option[Int] ={
    val theRank = beamServices.matsimServices.getScenario.getPopulation.getPersonAttributes.getAttribute(member.toString, "rank")
    theRank match{
      case null =>
        None
      case rank: Integer =>
        Some(rank)
    }
  }
  // This will sort by rank in ascending order so #1 rank is first in the list, if rank is undefined, it will be last in list
  def sortByRank(r2: MemberWithRank, r1: MemberWithRank): Boolean = {
    r1.rank.isEmpty || (!r2.rank.isEmpty && r1.rank.get > r2.rank.get)
  }
}