package beam.agentsim.agents.household

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.{InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Household

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
        matSimHousehold,
        houseHoldVehicles,
        homeCoord
      )
    )
  }

  case class MobilityStatusInquiry()
  case class ReleaseVehicle(vehicle: BeamVehicle)

  case class MobilityStatusResponse(streetVehicle: Vector[BeamVehicle])

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
    val household: Household,
    vehicles: Map[Id[BeamVehicle], BeamVehicle],
    homeCoord: Coord,
    sharedVehicleFleets: Vector[ActorRef] = Vector()
  ) extends Actor
      with ActorLogging {

    override val supervisorStrategy: OneForOneStrategy =
      OneForOneStrategy(maxNrOfRetries = 0) {
        case _: Exception      => Stop
        case _: AssertionError => Stop
      }

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
      newBodyVehicle.taken = true
      newBodyVehicle.registerResource(personRef)
      beamServices.vehicles += ((bodyVehicleIdFromPerson, newBodyVehicle))

      schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
      beamServices.personRefs += ((person.getId, personRef))
    }

    private var availableVehicles: List[BeamVehicle] = vehicles.values.toList
    for (veh <- vehicles.values) {
      veh.manager = Some(self)
      veh.spaceTime = SpaceTime(homeCoord.getX, homeCoord.getY, 0)
    }

    override def receive: Receive = {

      case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
        val vehId = vId.asInstanceOf[Id[BeamVehicle]]
        vehicles(vehId).spaceTime = whenWhere
        log.debug("updated vehicle {} with location {}", vehId, whenWhere)

      case ReleaseVehicle(vehicle) =>
        vehicle.taken = false
        availableVehicles = vehicle :: availableVehicles
        log.debug("Vehicle {} is now available for anyone in household {}", vehicle.id, household.getId)

      case MobilityStatusInquiry() =>
        availableVehicles = availableVehicles match {
          case firstVehicle :: rest =>
            firstVehicle.taken = true
            sender() ! MobilityStatusResponse(Vector(firstVehicle))
            rest
          case Nil =>
            sender() ! MobilityStatusResponse(Vector())
            Nil
        }

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
  }

}
