package beam.agentsim.agents.household

import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceIdle, NotifyResourceInUse}
import beam.agentsim.ResourceManager.VehicleManager
import beam.agentsim.agents.BeamAgent.Finish

import beam.agentsim.agents.modalBehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle.{MatsimHumanBodyVehicleType, createId, powerTrainForHumanBody}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.{InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Income.IncomePeriod
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.vehicles.{Vehicle, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

object HouseholdActor {


  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName.map(i => s"_iter-$i").getOrElse("")
  }


  def props(beamServices: BeamServices, modeChoiceCalculator: AttributesOfIndividual => ModeChoiceCalculator, schedulerRef: ActorRef, transportNetwork: TransportNetwork, router: ActorRef, rideHailingManager: ActorRef, eventsManager: EventsManager, population: org.matsim.api.core.v01.population.Population, householdId: Id[Household], matSimHousehold: Household,
            houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle],
            homeCoord: Coord): Props = {
    Props(new HouseholdActor(beamServices, modeChoiceCalculator, schedulerRef, transportNetwork, router, rideHailingManager, eventsManager, population, householdId, matSimHousehold, houseHoldVehicles, homeCoord))
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


  case class InitializeRideHailAgent(b: Id[Person])

  case class HouseholdAttributes(householdIncome: Double, householdSize: Int, numCars: Int, numBikes: Int)

  case class AttributesOfIndividual(person: Person, householdAttributes: HouseholdAttributes, householdId: Id[Household], modalityStyle: Option[String], isMale: Boolean) {
    lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty
  }

  object AttributesOfIndividual {
    def apply(person: Person, household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): AttributesOfIndividual = {
      val modalityStyle = Option(person.getSelectedPlan.getAttributes.getAttribute("modality-style")).map(_.asInstanceOf[String])
      AttributesOfIndividual(person, HouseholdAttributes(household, vehicles), household.getId, modalityStyle, new Random().nextBoolean())
    }
  }

  object HouseholdAttributes {
    def apply(household: Household,
              vehicles: Map[Id[Vehicle], Vehicle]) = new HouseholdAttributes(
      Option(household.getIncome).getOrElse(new IncomeImpl(0, IncomePeriod.year)).getIncome,
      household.getMemberIds.size(),
      household.getVehicleIds.asScala.map(vehicles).count(_.getType.getDescription.toLowerCase.contains("car")),
      household.getVehicleIds.asScala.map(vehicles).count(_.getType.getDescription.toLowerCase.contains("bike"))
    )
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
    * @param id       this [[Household]]'s unique identifier.
    * @param vehicles the [[BeamVehicle]]s managed by this [[Household]].
    * @see [[ChoosesMode]]
    */
  class HouseholdActor(beamServices: BeamServices,
                       modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator,
                       schedulerRef: ActorRef,
                       transportNetwork: TransportNetwork,
                       router: ActorRef,
                       rideHailingManager: ActorRef,
                       eventsManager: EventsManager,
                       val population: org.matsim.api.core.v01.population.Population,
                       id: Id[households.Household],
                       val household: Household,
                       vehicles: Map[Id[BeamVehicle], BeamVehicle],
                       homeCoord: Coord)
    extends VehicleManager with ActorLogging {

    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val pop: org.matsim.api.core.v01.population.Population = population

    household.members.foreach { person =>
      val bodyVehicleIdFromPerson = createId(person.getId)
      val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson, MatsimHumanBodyVehicleType)
      // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
      //let's put here human body vehicle too, it should be clean up on each iteration
      val attributes = AttributesOfIndividual(person, household, vehicles)
      person.getCustomAttributes.put("beam-attributes", attributes)
      val personRef: ActorRef = context.actorOf(PersonAgent.props(schedulerRef, beamServices, modeChoiceCalculatorFactory(attributes),
        transportNetwork, router, rideHailingManager, eventsManager, person.getId, household, person.getSelectedPlan, bodyVehicleIdFromPerson), person.getId.toString)
      context.watch(personRef)
      // Every Person gets a HumanBodyVehicle
      val newBodyVehicle = new BeamVehicle(powerTrainForHumanBody(), matsimBodyVehicle, None, HumanBodyVehicle)
      newBodyVehicle.registerResource(personRef)
      beamServices.vehicles += ((bodyVehicleIdFromPerson, newBodyVehicle))
      schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personRef)
      beamServices.personRefs += ((person.getId, personRef))
    }


    override val resources: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] = collection.mutable.Map[Id[BeamVehicle], BeamVehicle]()
    resources ++ vehicles

    /**
      * Available [[Vehicle]]s in [[Household]].
      */
    var _vehicles: Vector[Id[Vehicle]] = vehicles.keys.toVector.map(x => Id.createVehicleId(x))

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

      case Finish =>
        context.children.foreach(_ ! Finish)
        dieIfNoChildren()
        context.become {
          case Terminated(_) =>
            dieIfNoChildren()
        }

      case Terminated(_) =>
      // Do nothing
    }

    def dieIfNoChildren() = {
      if (context.children.isEmpty) {
        context.stop(self)
      } else {
        log.debug("Remaining: {}", context.children)
      }
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


    private def initializeHouseholdVehicles(): Unit = {
      // Add the vehicles to resources managed by this ResourceManager.

      resources ++ vehicles
      // Initial assignments

      for (i <- _vehicles.indices.toSet ++ household.rankedMembers.indices.toSet) {
        if (i < _vehicles.size & i < household.rankedMembers.size) {
          _reservedForPerson = _reservedForPerson + (household.rankedMembers(i).memberId -> _vehicles(i))
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

}

