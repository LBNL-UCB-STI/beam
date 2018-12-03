package beam.agentsim.agents.household

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import beam.agentsim.Resource.{CheckInResource, NotifyVehicleResourceIdle}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.{InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.Modes.BeamMode.CAR
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.plan.sampling.AvailableModeUtils.isModeAvailableForPerson
import com.conveyal.r5.transit.TransportNetwork
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

object HouseholdActor {

  def buildActorName(id: Id[households.Household], iterationName: Option[String] = None): String = {
    s"household-${id.toString}" + iterationName
      .map(i => s"_iter-$i")
      .getOrElse("")
  }

  def props(
    beamServices: BeamServices,
    modeChoiceCalculator: AttributesOfIndividual => ModeChoiceCalculator,
    schedulerRef: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    population: org.matsim.api.core.v01.population.Population,
    householdId: Id[Household],
    matSimHousehold: Household,
    houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord
  ): Props = {
    Props(
      new HouseholdActor(
        beamServices,
        modeChoiceCalculator,
        schedulerRef,
        transportNetwork,
        tollCalculator,
        router,
        rideHailManager,
        parkingManager,
        eventsManager,
        population,
        householdId,
        matSimHousehold,
        houseHoldVehicles,
        homeCoord
      )
    )
  }

  case class MobilityStatusInquiry(inquiryId: Id[MobilityStatusInquiry], personId: Id[Person])

  case class ReleaseVehicleReservation(personId: Id[Person], vehId: Id[Vehicle])

  case class MobilityStatusResponse(streetVehicle: Vector[StreetVehicle])

  case class InitializeRideHailAgent(b: Id[Person])

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
  class HouseholdActor(
    beamServices: BeamServices,
    modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator,
    schedulerRef: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    val population: org.matsim.api.core.v01.population.Population,
    id: Id[households.Household],
    val household: Household,
    vehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord
  ) extends Actor
      with ActorLogging {

    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val pop: org.matsim.api.core.v01.population.Population = population

    household.members.foreach { person =>
      val attributes = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]

      val modeChoiceCalculator = modeChoiceCalculatorFactory(attributes)

      val bodyVehicleIdFromPerson = BeamVehicle.createId(person.getId, Some("body"))

      modeChoiceCalculator.valuesOfTime += (GeneralizedVot -> attributes.valueOfTime)

      val personRef: ActorRef = context.actorOf(
        PersonAgent.props(
          schedulerRef,
          beamServices,
          modeChoiceCalculator,
          transportNetwork,
          tollCalculator,
          router,
          rideHailManager,
          parkingManager,
          eventsManager,
          person.getId,
          household,
          person.getSelectedPlan,
          bodyVehicleIdFromPerson
        ),
        person.getId.toString
      )
      context.watch(personRef)

      // Every Person gets a HumanBodyVehicle
      val newBodyVehicle = new BeamVehicle(
        bodyVehicleIdFromPerson,
        BeamVehicleType.powerTrainForHumanBody,
        None,
        BeamVehicleType.defaultHumanBodyBeamVehicleType
      )
      newBodyVehicle.registerResource(personRef)
      beamServices.vehicles += ((bodyVehicleIdFromPerson, newBodyVehicle))

      schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
      beamServices.personRefs += ((person.getId, personRef))

    }

    private val resources = mutable.Map() ++ vehicles

    /**
      * Available [[Vehicle]]s in [[Household]].
      */
    val _vehicles: Vector[Id[BeamVehicle]] =
      vehicles.keys.toVector //.map(x => Id.createVehicleId(x))

    /**
      * Current [[Vehicle]] assignments.
      */
    private val _availableVehicles: mutable.Set[Id[Vehicle]] = mutable.Set()

    /**
      * These [[Vehicle]]s cannot be assigned to other agents.
      */
    private val _reservedForPerson: mutable.Map[Id[Person], Id[Vehicle]] = mutable.Map()

    /**
      * Vehicles that are currently checked out to traveling agents.
      */
    private val _checkedOutVehicles: mutable.Map[Id[Vehicle], Id[Person]] = mutable.Map()

    /**
      * Mapping of [[Vehicle]] to [[StreetVehicle]]
      */
    private val _vehicleToStreetVehicle: mutable.Map[Id[BeamVehicle], StreetVehicle] = mutable.Map()

    // Initial vehicle assignments.
    initializeHouseholdVehicles()

    override def receive: Receive = {

      case NotifyVehicleResourceIdle(
          vId,
          whenWhere,
          _,
          _,
          _
          ) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        _vehicleToStreetVehicle += (vehId -> StreetVehicle(vehId, whenWhere.get, CAR, asDriver = true))
        log.debug("updated vehicle {} with location {}", vehId, whenWhere.get)

      case CheckInResource(vehicleId, _) =>
        checkInVehicleResource(vehicleId.asInstanceOf[Id[BeamVehicle]])

      case ReleaseVehicleReservation(personId, vehId) =>
        /*
         * Remove the mapping in _reservedForPerson if it exists. If the vehicle is not checked out, make available to all.
         */
        _reservedForPerson.get(personId) match {
          case Some(vehicleId) if vehicleId == vehId =>
            log.debug("Vehicle {} is now available for anyone in household {}", vehicleId, id)
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
            val anyAvailableVehicles = lookupAvailableVehicles()
            // Filter only by available modes
            anyAvailableVehicles
              .groupBy(_.mode)
              .map(_._2.head)
              .toVector

          } else {
            reservedVeh
          }
        } else {
          alreadyCheckedOutVehicle
        }

        // Assign to requesting individual if mode is available
        availableStreetVehicles
          .filter(
            veh => isModeAvailableForPerson(population.getPersons.get(personId), veh.id, veh.mode)
          )
          .filter { theveh =>
            // also make sure there isn't another driver using this vehicle
            val existingDriver = beamServices.vehicles(theveh.id).driver
            existingDriver.isEmpty || existingDriver.get.path.toString.contains(personId.toString)
          }
          .foreach { x =>
            _availableVehicles.remove(x.id)
            _checkedOutVehicles.put(x.id, personId)
          }
        sender() ! MobilityStatusResponse(availableStreetVehicles)

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

    def dieIfNoChildren(): Unit = {
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
          if (!_reservedForPerson.values.toSet.contains(vehicleId)) {
            _availableVehicles.add(vehicleId)
          }
      }
      log.debug("Resource {} is now available again", vehicleId)
    }

    // This will sort by rank in ascending order so #1 rank is first in the list, if rank is undefined, it will be last
    // in list

    private def lookupAvailableVehicles(): Vector[StreetVehicle] =
      Vector(
        for {
          availableVehicle       <- _availableVehicles
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

    private def initializeHouseholdVehicles(): Unit = {
      // Add the vehicles to resources managed by this ResourceManager.

      vehicles.foreach(idAndVeh => resources.put(idAndVeh._1, idAndVeh._2))
      // Initial assignments

      for (i <- _vehicles.indices.toSet ++ household.rankedMembers.indices.toSet) {
        if (i < _vehicles.size & i < household.rankedMembers.size) {

          val memberId: Id[Person] = household
            .rankedMembers(i)
            .memberId
          val vehicleId: Id[Vehicle] = _vehicles(i)
          val person = population.getPersons.get(memberId)

          // Should never reserve for person who doesn't have mode available to them
          val mode = BeamVehicleType.getMode(vehicles(vehicleId))

          if (isModeAvailableForPerson(person, vehicleId, mode)) {
            _reservedForPerson += (memberId -> vehicleId)
          }
        } else if (i < _vehicles.size) {
          _availableVehicles += _vehicles(i)
        }
      }

      //Initial locations and trajectories
      //Initialize all vehicles to have a stationary trajectory starting at time zero
      val initialLocation = SpaceTime(homeCoord.getX, homeCoord.getY, 0)

      for { veh <- _vehicles } yield {
        //TODO following mode should match exhaustively
        val mode = BeamVehicleType.getMode(vehicles(veh))

        _vehicleToStreetVehicle +=
          (veh -> StreetVehicle(veh, initialLocation, mode, asDriver = true))
      }

    }
  }

  object MobilityStatusInquiry {

    // Smart constructor for MSI
    def mobilityStatusInquiry(personId: Id[Person]) =
      MobilityStatusInquiry(
        Id.create(UUIDGen.createTime(UUIDGen.newTime()).toString, classOf[MobilityStatusInquiry]),
        personId
      )
  }

}
