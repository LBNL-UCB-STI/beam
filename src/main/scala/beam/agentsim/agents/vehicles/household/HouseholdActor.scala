package beam.agentsim.agents.vehicles.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.BeamVehicle.{StreetVehicle, UpdateTrajectory}
import beam.agentsim.agents.vehicles.household.HouseholdActor.{MemberWithRank, MobilityStatusInquiry, MobilityStatusReponse}
import beam.agentsim.agents.vehicles.{Trajectory, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.BeamStreetPath
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

/**
  * @author dserdiuk
  */

object HouseholdActor {

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }

  def props(beamServices: BeamServices, householdId: Id[Household], matSimHousehold: Household, houseHoldVehicles: Map[Id[Vehicle], ActorRef], membersActors: Map[Id[Person], ActorRef], homeCoord: Coord): Props = {
    Props(new HouseholdActor(beamServices, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord))
  }

  case class MobilityStatusInquiry(personId: Id[Person])

  case class MobilityStatusReponse(streetVehicle: Vector[StreetVehicle])

  case class MemberWithRank(personId: Id[Person], rank: Option[Int])

}

class HouseholdActor(services: BeamServices,
                     id: Id[households.Household],
                     matSimHouseHold: org.matsim.households.Household,
                     vehicleActors: Map[Id[Vehicle], ActorRef],
                     memberActors: Map[Id[Person], ActorRef],
                     homeCoord: Coord
                    )
  extends VehicleManager with ActorLogging with HasServices {

  override val beamServices: BeamServices = services

  /**
    * Available [[Vehicle]]s in [[Household]]
    */
  val _vehicles: Vector[Id[Vehicle]] = vehicleActors.keys.toVector

  /**
    * Household members sorted by rank
    */
  val _members: Vector[MemberWithRank] = memberActors.keys.toVector.map(memb => MemberWithRank(memb, lookupMemberRank(memb)))

  /**
    * Current [[Vehicle]] assignments
    */
  var _vehicleAssignments: Map[Id[Person], Id[Vehicle]] = Map[Id[Person], Id[Vehicle]]()

  /**
    * Mapping of [[Vehicle]] to [[StreetVehicle]]
    */
  var _vehicleToStreetVehicle: Map[Id[Vehicle], StreetVehicle] = Map()

  override def findResource(vehicleId: Id[Vehicle]): Option[ActorRef] = vehicleActors.get(vehicleId)


  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(tick), triggerId) =>
      //TODO this needs to be updated to differentiate between CAR and BIKE and allow individuals to get assigned one of each
      initializeHouseholdVehicles()
      log.debug(s"Household ${self.path.name} has been initialized ")
      sender() ! CompletionNotice(triggerId)
    case MobilityStatusInquiry(personId) =>
      // Query available vehicles
      val availableVehicles = lookupAvailableStreetVehicles(personId)

      if(availableVehicles.isEmpty)
        // Send back response of empty [[Vector]]
        sender() ! MobilityStatusReponse(availableVehicles)
      else{
        // Assign to requesting individual

        sender() ! MobilityStatusReponse(availableVehicles)
      }



    case msg@_ =>
      log.warning(s"Unrecognized message $msg")
  }

  def lookupMemberRank(member: Id[Person]): Option[Int] = {

    beamServices.matsimServices.getScenario.getPopulation.getPersonAttributes.getAttribute(member.toString, "rank") match {
      case rank: Integer =>
        Some(rank)
      case _ =>
        None
    }
  }

  // This will sort by rank in ascending order so #1 rank is first in the list, if rank is undefined, it will be last in list
  def sortByRank(r2: MemberWithRank, r1: MemberWithRank): Boolean = {
    r1.rank.isEmpty || (r2.rank.isDefined && r1.rank.get > r2.rank.get)
  }

  private def initializeHouseholdVehicles(): Unit = {
    // Initial assignments
    val sortedMembers = _members.sortWith(sortByRank)
    for (i <- _vehicles.indices.toSet ++ sortedMembers.indices.toSet) {
      if (i < _vehicles.size & i < sortedMembers.size) {
        _vehicleAssignments = _vehicleAssignments + (sortedMembers(i).personId -> _vehicles(i))
      }
    }
    // Initial locations and trajectories
    //Initialize all vehicles to have a stationary trajectory starting at time zero
    val initialLocation = SpaceTime(homeCoord.getX, homeCoord.getY, 0L)
    val initialTrajectory = Trajectory(BeamStreetPath(Vector(""), None, Some(Vector())))
    _vehicles.foreach { veh =>
      services.vehicleRefs(veh) ! UpdateTrajectory(initialTrajectory)
      //TODO following mode should come from the vehicle
      _vehicleToStreetVehicle = _vehicleToStreetVehicle + (veh -> StreetVehicle(veh, initialLocation, CAR, asDriver = true))
    }
  }

  def lookupAvailableStreetVehicles(person: Id[Person]): Vector[StreetVehicle] = Vector(
    for {
      availableVehicle <- _vehicleAssignments.get(person)
      availableStreetVehicle <- _vehicleToStreetVehicle.get(availableVehicle)
    } yield availableStreetVehicle
  ).flatten

}