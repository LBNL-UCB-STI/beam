package beam.agentsim.agents.household

import akka.actor.{ActorLogging, ActorRef, Props, Terminated}
import beam.agentsim.Resource.{CheckInResource, NotifyResourceInUse}
import beam.agentsim.ResourceManager.{NotifyVehicleResourceIdle, VehicleManager}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.{InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import beam.utils.plansampling.AvailableModeUtils.{isModeAvailableForPerson, _}
import com.conveyal.r5.transit.TransportNetwork
import com.eaio.uuid.UUIDGen
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Income.IncomePeriod
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

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

  case class HouseholdAttributes(
    householdIncome: Double,
    householdSize: Int,
    numCars: Int,
    numBikes: Int
  )

  case class AttributesOfIndividual(
    person: Person,
    householdAttributes: HouseholdAttributes,
    householdId: Id[Household],
    modalityStyle: Option[String],
    isMale: Boolean,
    availableModes: Seq[BeamMode],
    valueOfTime: BigDecimal
  ) {
    lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty
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
  class HouseholdActor(
    beamServices: BeamServices,
    modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator,
    schedulerRef: ActorRef,
    transportNetwork: TransportNetwork,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager,
    val population: org.matsim.api.core.v01.population.Population,
    id: Id[households.Household],
    val household: Household,
    vehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord
  ) extends VehicleManager
      with ActorLogging {

    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val pop: org.matsim.api.core.v01.population.Population = population
    override val resources: collection.mutable.Map[Id[BeamVehicle], BeamVehicle] =
      collection.mutable.Map[Id[BeamVehicle], BeamVehicle]()
    household.members.foreach { person =>
      // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
      //let's put here human body vehicle too, it should be clean up on each iteration
      val personId = person.getId

      val bodyVehicleIdFromPerson = BeamVehicle.createId(personId, Some("body"))

      val availableModes: Seq[BeamMode] = Option(
        personAttributes.getAttribute(
          person.getId.toString,
          beam.utils.plansampling.PlansSampler.availableModeString
        )
      ).fold(BeamMode.availableModes)(
        attr => availableModeParser(attr.toString)
      )

      val valueOfTime: Double =
        personAttributes.getAttribute(person.getId.toString, "valueOfTime") match {
          case null =>
            beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
          case specifiedVot =>
            specifiedVot.asInstanceOf[Double]
        }

      val attributes =
        AttributesOfIndividual(person, household, vehicles, availableModes, valueOfTime)

      person.getCustomAttributes.put("beam-attributes", attributes)

      val modeChoiceCalculator = modeChoiceCalculatorFactory(attributes)

      modeChoiceCalculator.valuesOfTime += (GeneralizedVot -> valueOfTime)

      val personRef: ActorRef = context.actorOf(
        PersonAgent.props(
          schedulerRef,
          beamServices,
          modeChoiceCalculator,
          transportNetwork,
          router,
          rideHailManager,
          parkingManager,
          eventsManager,
          personId,
          household,
          person.getSelectedPlan,
          bodyVehicleIdFromPerson
        ),
        personId.toString
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
      beamServices.personRefs += ((personId, personRef))

    }
    val personAttributes: ObjectAttributes = population.getPersonAttributes
    resources ++ vehicles

    /**
      * Available [[Vehicle]]s in [[Household]].
      */
    val _vehicles: Vector[Id[BeamVehicle]] =
      vehicles.keys.toVector //.map(x => Id.createVehicleId(x))

    /**
      * Concurrent [[MobilityStatusInquiry]]s that must receive responses before completing vehicle assignment.
      */
    val _pendingInquiries: Map[Id[MobilityStatusInquiry], Id[Vehicle]] =
      Map[Id[MobilityStatusInquiry], Id[Vehicle]]()

    /**
      * Current [[Vehicle]] assignments.
      */
    private val _availableVehicles: mutable.Set[Id[Vehicle]] = mutable.Set()

    /**
      * These [[Vehicle]]s cannot be assigned to other agents.
      */
    private val _reservedForPerson: mutable.Map[Id[Person], Id[Vehicle]] =
      mutable.Map[Id[Person], Id[Vehicle]]()

    /**
      * Vehicles that are currently checked out to traveling agents.
      */
    private val _checkedOutVehicles: mutable.Map[Id[Vehicle], Id[Person]] =
      mutable.Map[Id[Vehicle], Id[Person]]()

    /**
      * Mapping of [[Vehicle]] to [[StreetVehicle]]
      */
    private val _vehicleToStreetVehicle: mutable.Map[Id[BeamVehicle], StreetVehicle] =
      mutable.Map[Id[BeamVehicle], StreetVehicle]()

    // Initial vehicle assignments.
    initializeHouseholdVehicles()

    override def findResource(vehicleId: Id[BeamVehicle]): Option[BeamVehicle] =
      resources.get(vehicleId)

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

      case NotifyResourceInUse(vId, whenWhere) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        _vehicleToStreetVehicle += (vehId -> StreetVehicle(vehId, whenWhere, CAR, asDriver = true))

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
            if (existingDriver.isDefined) {
              val i = 0
            }
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
      if (id.toString.equals("025401-2013001385345-0") || id.toString.equals("032901-2015001323724-0")) {
        val i = 0
      }

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

  object AttributesOfIndividual {

    def apply(
      person: Person,
      household: Household,
      vehicles: Map[Id[BeamVehicle], BeamVehicle],
      valueOfTime: BigDecimal
    ): AttributesOfIndividual = {
      val modalityStyle =
        Option(person.getSelectedPlan.getAttributes.getAttribute("modality-style"))
          .map(_.asInstanceOf[String])
      AttributesOfIndividual(
        person,
        HouseholdAttributes(household, vehicles),
        household.getId,
        modalityStyle,
        new Random().nextBoolean(),
        BeamMode.availableModes,
        valueOfTime
      )
    }

    def apply(
      person: Person,
      household: Household,
      vehicles: Map[Id[BeamVehicle], BeamVehicle],
      availableModes: Seq[BeamMode],
      valueOfTime: BigDecimal
    ): AttributesOfIndividual = {
      val modalityStyle =
        Option(person.getSelectedPlan.getAttributes.getAttribute("modality-style"))
          .map(_.asInstanceOf[String])

      AttributesOfIndividual(
        person,
        HouseholdAttributes(household, vehicles),
        household.getId,
        modalityStyle,
        person.getCustomAttributes.get("sex").asInstanceOf[Boolean],
        availableModes,
        valueOfTime
      )
    }

  }

  object HouseholdAttributes {

    def apply(household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): HouseholdAttributes = {
      new HouseholdAttributes(
        Option(household.getIncome)
          .getOrElse(new IncomeImpl(0, IncomePeriod.year))
          .getIncome,
        household.getMemberIds.size(),
        household.getVehicleIds.asScala
          .map(id => vehicles(id))
          .count(_.beamVehicleType.vehicleCategory.toLowerCase.contains("car")),
        household.getVehicleIds.asScala
          .map(id => vehicles(id))
          .count(_.beamVehicleType.vehicleCategory.toLowerCase.contains("bike"))
      )
    }
  }

}
