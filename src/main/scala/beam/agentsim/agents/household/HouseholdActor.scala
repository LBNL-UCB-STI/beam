package beam.agentsim.agents.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.CheckInResource
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.household.HouseholdActor._
import beam.agentsim.agents.vehicles.VehicleProtocol.{AppendToTrajectory, StreetVehicle}
import beam.agentsim.agents.vehicles.{BeamVehicle, Trajectory}
import beam.agentsim.events.SpaceTime
import beam.router.DefinedTrajectoryHolder
import beam.router.Modes.BeamMode.CAR
import beam.router.RoutingModel.BeamPath
import beam.sim.{BeamServices, HasServices}
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * @author dserdiuk
  */

object HouseholdActor {


  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }

  def props(beamServices: BeamServices, eventsManager: EventsManager, population: Population, householdId: Id[Household], matSimHousehold: Household,
            houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle], membersActors: Map[Id[Person], ActorRef],
            homeCoord: Coord): Props = {
    Props(new HouseholdActor(beamServices, eventsManager, population, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord))
  }

  case class MobilityStatusInquiry(inquiryId: Id[MobilityStatusInquiry], personId: Id[Person])

  object MobilityStatusInquiry {
    // Smart constructor for MSI
    def mobilityStatusInquiry(personId: Id[Person]) =
      MobilityStatusInquiry(Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[MobilityStatusInquiry])
        , personId)
  }

  case class ReleaseVehicleReservation(personId: Id[Person], vehId: Id[Vehicle])

  case class NotifyNewVehicleLocation(vehId: Id[Vehicle], whenWhere: SpaceTime)

  case class MobilityStatusReponse(streetVehicle: Vector[StreetVehicle])

  case class MemberWithRank(personId: Id[Person], rank: Option[Int])

  case class InitializeRideHailAgent(b: Id[Person])

}

class HouseholdActor(services: BeamServices,
                     eventsManager: EventsManager,
                     population: Population,
                     id: Id[households.Household],
                     matSimHouseHold: org.matsim.households.Household,
                     vehicles: Map[Id[BeamVehicle], BeamVehicle],
                     memberActors: Map[Id[Person], ActorRef],
                     homeCoord: Coord
                    )
  extends VehicleManager with ActorLogging with HasServices {

  override val beamServices: BeamServices = services
  override val resources: Map[Id[BeamVehicle], BeamVehicle] = vehicles

  /**
    * Available [[Vehicle]]s in [[Household]]
    */
  var _vehicles: Vector[Id[Vehicle]] = vehicles.keys.toVector.map(x => Id.createVehicleId(x))

  /**
    * Household members sorted by rank
    */
  val _members: Vector[MemberWithRank] = memberActors.keys.toVector.map(memb => MemberWithRank(memb, lookupMemberRank
  (memb)))

  /**
    * Concurrent inquiries
    */
  var _pendingInquiries: Map[Id[MobilityStatusInquiry], Id[Vehicle]] = Map[Id[MobilityStatusInquiry], Id[Vehicle]]()

  /**
    * Current [[Vehicle]] assignments
    */
  var _availableVehicles: mutable.Set[Id[Vehicle]] = mutable.Set()
  var _reservedForPerson: mutable.Map[Id[Person], Id[Vehicle]] = mutable.Map[Id[Person], Id[Vehicle]]()
  var _checkedOutVehicles: mutable.Map[Id[Vehicle], Id[Person]] = mutable.Map[Id[Vehicle], Id[Person]]()

  /**
    * Mapping of [[Vehicle]] to [[StreetVehicle]]
    */
  var _vehicleToStreetVehicle: Map[Id[Vehicle], StreetVehicle] = Map()

  initializeHouseholdVehicles()

  override def findResource(vehicleId: Id[BeamVehicle]): Option[BeamVehicle] = resources.get(vehicleId)

  override def receive: Receive = {

    case NotifyNewVehicleLocation(vehId, whenWhere) =>
      _vehicleToStreetVehicle = _vehicleToStreetVehicle + (vehId -> StreetVehicle(vehId, whenWhere, CAR, asDriver =
        true))

    case CheckInResource(vehicleId: Id[Vehicle]) =>
      /*
       * If the resource is checked out, remove. If the resource is not reserved to an individual, make available to all.
       */
      val personIDOpt = _checkedOutVehicles.remove(vehicleId)
      personIDOpt match {
        case Some(personId) =>
          _reservedForPerson.get(personId) match {
            case None =>
              _availableVehicles.add(vehicleId)
            case Some(_) =>
          }
        case None =>
      }
      log.info(s"Resource $vehicleId is now available again")

    case ReleaseVehicleReservation(personId, vehId) =>
      /*
       * Remove the mapping in _reservedForPerson if it exists. If the vehicle is not checked out, make available to all.
       */
      _reservedForPerson.get(personId) match {
        case Some(vehicleId) if vehicleId == vehId =>
          log.info(s"Vehicle $vehicleId is now available for anyone in household $id")
          _reservedForPerson.remove(personId)
        case _ =>
      }
      if(!_checkedOutVehicles.contains(vehId))_availableVehicles.add(vehId)

    case MobilityStatusInquiry(_, personId) =>
      // We give first priority to an already checkout out vehicle
      val alreadyCheckedOutVehicle = lookupCheckedOutVehicle(personId)

      val availableStreetVehicles = if(alreadyCheckedOutVehicle.isEmpty){
        // Second priority is a reserved vehicle
        val reservedVeh = lookupReservedVehicle(personId)
        if(reservedVeh.isEmpty){
          // Lastly we search for available vehicles but limit to one per mode
          val anyAvailableVehs = lookupAvailableVehicles()
          anyAvailableVehs.groupBy(_.mode).map(_._2.head).toVector
        }else{
          reservedVeh
        }
      }else{
        alreadyCheckedOutVehicle
      }

      // Assign to requesting individual
      availableStreetVehicles.foreach { x =>
        _availableVehicles.remove(x.id)
        _checkedOutVehicles.put(x.id, personId)
      }
      sender() ! MobilityStatusReponse(availableStreetVehicles)


    case msg@_ =>
      log.warning(s"Unrecognized message $msg")
  }

  def lookupMemberRank(member: Id[Person]): Option[Int] = {

    population.getPersonAttributes.getAttribute(member.toString, "rank")
    match {
      case rank: Integer =>
        Some(rank)
      case _ =>
        None
    }
  }

  // This will sort by rank in ascending order so #1 rank is first in the list, if rank is undefined, it will be last
  // in list
  def sortByRank(r2: MemberWithRank, r1: MemberWithRank): Boolean = {
    r1.rank.isEmpty || (r2.rank.isDefined && r1.rank.get > r2.rank.get)
  }

  private def initializeHouseholdVehicles(): Unit = {
    // Initial assignments
    val sortedMembers = _members.sortWith(sortByRank)
    for (i <- _vehicles.indices.toSet ++ sortedMembers.indices.toSet) {
      if (i < _vehicles.size & i < sortedMembers.size) {
        _reservedForPerson = _reservedForPerson + (sortedMembers(i).personId -> _vehicles(i))
      }
    }
    // Initial locations and trajectories
    //Initialize all vehicles to have a stationary trajectory starting at time zero
    val initialLocation = SpaceTime(homeCoord.getX, homeCoord.getY, 0L)
    val initialBeamPath = BeamPath(Vector(), None, DefinedTrajectoryHolder(Trajectory(Vector(initialLocation))))

    for {veh <- _vehicles} yield {
      services.vehicles(veh).driver match {
        case Some(driver) =>
          driver ! AppendToTrajectory(initialBeamPath)
        case None =>
      }
      //TODO following mode should come from the vehicle
      _vehicleToStreetVehicle = _vehicleToStreetVehicle +
        (veh -> StreetVehicle(veh, initialLocation, CAR, asDriver = true))
    }
  }

  def lookupAvailableVehicles(): Vector[StreetVehicle] = Vector(
    for {
      availableVehicle <- _availableVehicles
      availableStreetVehicle <- _vehicleToStreetVehicle.get(availableVehicle)
    } yield availableStreetVehicle
  ).flatten

  def lookupReservedVehicle(person: Id[Person]): Vector[StreetVehicle] = {
    _reservedForPerson.get(person) match {
      case Some(availableVehicle) =>
        Vector(_vehicleToStreetVehicle.get(availableVehicle).get)
      case None =>
        Vector()
    }
  }
  def lookupCheckedOutVehicle(person: Id[Person]): Vector[StreetVehicle] = {
    (for((veh,per) <- _checkedOutVehicles if per == person)yield{
      _vehicleToStreetVehicle.get(veh).get
    }).toVector
  }

}

