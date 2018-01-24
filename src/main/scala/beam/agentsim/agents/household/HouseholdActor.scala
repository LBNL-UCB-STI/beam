package beam.agentsim.agents.household

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceIdle, NotifyResourceInUse}
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.household.HouseholdActor._
import beam.agentsim.agents.modalBehaviors.ChoosesMode
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode.CAR
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.mutable


object HouseholdActor {

  type RankAssignment = Id[Person] => Option[Int]

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }

  def props(eventsManager: EventsManager, lookupMemberRank: RankAssignment, householdId: Id[Household],
            houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle], membersActors: Map[Id[Person], ActorRef],
            homeCoord: Coord): Props = {

    Props(new HouseholdActor(eventsManager, lookupMemberRank, householdId, houseHoldVehicles, membersActors, homeCoord))
  }

  case class MobilityStatusInquiry(inquiryId: Id[MobilityStatusInquiry], personId: Id[Person])

  object MobilityStatusInquiry {
    // Smart constructor for MSI
    def mobilityStatusInquiry(personId: Id[Person]) =
      MobilityStatusInquiry(Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[MobilityStatusInquiry])
        , personId)
  }

  case class ReleaseVehicleReservation(personId: Id[Person], vehId: Id[Vehicle])

  case class MobilityStatusReponse(streetVehicle: Vector[StreetVehicle])

  case class MemberWithRank(personId: Id[Person], rank: Option[Int])

  case class InitializeRideHailAgent(b: Id[Person])

}

/**
  * Implementation of intra-household interaction in BEAM using actors.
  *
  * Households group together individual agents to support vehicle allocation, escort (ride-sharing), and
  * joint travel decision-making.
  *
  * The [[HouseholdActor]] is the central arbiter for vehicle allocation during individual and joint mode choice events.
  * Any agent in a mode choice situation must send a [[MobilityStatusInquiry]] to the [[HouseholdActor]]. The
  *
  * @author dserdiuk/sfeygin
  * @param lookupMemberRank ranking function used to assign the priority to household members.
  * @param id               this [[Household]]'s unique identifier.
  * @param vehicles the [[BeamVehicle]]s managed by this [[Household]].
  *
  * @see [[ChoosesMode]]
  */
class HouseholdActor(eventsManager: EventsManager,
                     lookupMemberRank: RankAssignment,
                     id: Id[Household],
                     vehicles: Map[Id[BeamVehicle], BeamVehicle],
                     memberActors: Map[Id[Person], ActorRef],
                     homeCoord: Coord)

  extends VehicleManager with ActorLogging {

  override val resources: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] = collection.mutable.Map[Id[BeamVehicle], BeamVehicle]()

  /**
    * Available [[Vehicle]]s in [[Household]].
    */
  var _vehicles: Vector[Id[Vehicle]] = vehicles.keys.toVector.map(x => Id.createVehicleId(x))

  /**
    * [[Household]] members sorted by rank.
    */
  val _members: Vector[MemberWithRank] = memberActors.keys.toVector.map(member =>
    MemberWithRank(member, lookupMemberRank(member)))

  /**
    * Concurrent [[MobilityStatusInquiry]]s that must receive responses before completing vehicle assignment.
    */
  var _pendingInquiries: Map[Id[MobilityStatusInquiry], Id[Vehicle]] = Map[Id[MobilityStatusInquiry], Id[Vehicle]]()

  /**
    * Current [[Vehicle]] assignments.
    */
  var _availableVehicles: mutable.Set[Id[Vehicle]] = mutable.Set()

  /**
    * These [[Vehicle]]s cannot be assigned to other agents.
    */
  var _reservedForPerson: mutable.Map[Id[Person], Id[Vehicle]] = mutable.Map[Id[Person], Id[Vehicle]]()

  /**
    * Vehicles that are currently checked out to traveling agents.
    */
  var _checkedOutVehicles: mutable.Map[Id[Vehicle], Id[Person]] = mutable.Map[Id[Vehicle], Id[Person]]()

  /**
    * Mapping of [[Vehicle]] to [[StreetVehicle]]
    */
  var _vehicleToStreetVehicle: Map[Id[Vehicle], StreetVehicle] = Map()


  // Initial vehicle assignments.
  initializeHouseholdVehicles()

  override def findResource(vehicleId: Id[BeamVehicle]): Option[BeamVehicle] = resources.get(vehicleId)

  override def receive: Receive = {

    case NotifyResourceIdle(vehId: Id[Vehicle], whenWhere) =>
      _vehicleToStreetVehicle += (vehId -> StreetVehicle(vehId, whenWhere, CAR, asDriver = true))

    case NotifyResourceInUse(vehId: Id[Vehicle], whenWhere) =>
      _vehicleToStreetVehicle += (vehId -> StreetVehicle(vehId, whenWhere, CAR, asDriver = true))

    case CheckInResource(vehicleId: Id[Vehicle], _) => checkInVehicleResource(vehicleId)

    case ReleaseVehicleReservation(personId, vehId) =>
      /*
       * Remove the mapping in _reservedForPerson if it exists. If the vehicle is not checked out, make available to all.
       */
      _reservedForPerson.get(personId) match {
        case Some(vehicleId) if vehicleId == vehId =>
          log.debug(s"Vehicle $vehicleId is now available for anyone in household $id")
          _reservedForPerson.remove(personId)
        case _ =>
      }
      if (!_checkedOutVehicles.contains(vehId)) _availableVehicles.add(vehId)

    case MobilityStatusInquiry(_, personId) =>
      // We give first priority to an already checkout out vehicle
      val alreadyCheckedOutVehicle = lookupCheckedOutVehicle(personId)

      val availableStreetVehicles = if (alreadyCheckedOutVehicle.isEmpty) {
        // Second priority is a reserved vehicle
        val reservedVeh = lookupReservedVehicle(personId)
        if (reservedVeh.isEmpty) {
          // Lastly we search for available vehicles but limit to one per mode
          val anyAvailableVehs = lookupAvailableVehicles()
          anyAvailableVehs.groupBy(_.mode).map(_._2.head).toVector
        } else {
          reservedVeh
        }
      } else {
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

  private def checkInVehicleResource(vehicleId: Id[Vehicle]): Unit = {
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
    log.debug(s"Resource $vehicleId is now available again")
  }

  // This will sort by rank in ascending order so #1 rank is first in the list, if rank is undefined, it will be last
  // in list
  private def sortByRank(r2: MemberWithRank, r1: MemberWithRank): Boolean = {
    r1.rank.isEmpty || (r2.rank.isDefined && r1.rank.get > r2.rank.get)
  }

  private def initializeHouseholdVehicles(): Unit = {
    // Add the vehicles to resources managed by this ResourceManager.

    resources ++ vehicles
    // Initial assignments
    val sortedMembers = _members.sortWith(sortByRank)
    for (i <- _vehicles.indices.toSet ++ sortedMembers.indices.toSet) {
      if (i < _vehicles.size & i < sortedMembers.size) {
        _reservedForPerson = _reservedForPerson + (sortedMembers(i).personId -> _vehicles(i))
      }
    }

    //Initial locations and trajectories
    //Initialize all vehicles to have a stationary trajectory starting at time zero
    val initialLocation = SpaceTime(homeCoord.getX, homeCoord.getY, 0L)

    for {veh <- _vehicles} yield {
      //TODO following mode should come from the vehicle
      _vehicleToStreetVehicle = _vehicleToStreetVehicle +
        (veh -> StreetVehicle(veh, initialLocation, CAR, asDriver = true))
    }
  }

  private def lookupAvailableVehicles(): Vector[StreetVehicle] = Vector(
    for {
      availableVehicle <- _availableVehicles
      availableStreetVehicle <- _vehicleToStreetVehicle.get(availableVehicle)
    } yield availableStreetVehicle
  ).flatten

  private def lookupReservedVehicle(person: Id[Person]): Vector[StreetVehicle] = {
    _reservedForPerson.get(person) match {
      case Some(availableVehicle) =>
        Vector(_vehicleToStreetVehicle(availableVehicle))
      case None =>
        Vector()
    }
  }

  private def lookupCheckedOutVehicle(person: Id[Person]): Vector[StreetVehicle] = {
    (for ((veh, per) <- _checkedOutVehicles if per == person) yield {
      _vehicleToStreetVehicle(veh)
    }).toVector
  }

}

