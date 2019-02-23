package beam.agentsim.agents.household

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.VehicleOrToken
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.GeneralizedVot
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, ModeChoiceCalculator}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.{InitializeTrigger, PersonAgent}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households
import org.matsim.households.Household

import scala.concurrent.ExecutionContext

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
    homeCoord: Coord,
    sharedVehicleFleets: Seq[ActorRef] = Vector()
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
        homeCoord,
        sharedVehicleFleets
      )
    )
  }

  case class MobilityStatusInquiry(whereWhen: SpaceTime)
  case class ReleaseVehicle(vehicle: BeamVehicle)
  case class ReleaseVehicleAndReply(vehicle: BeamVehicle)

  case class MobilityStatusResponse(streetVehicle: Vector[VehicleOrToken])

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
    sharedVehicleFleets: Seq[ActorRef] = Vector()
  ) extends Actor
      with ActorLogging {

    private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
    private implicit val executionContext: ExecutionContext = context.dispatcher
    override val supervisorStrategy: OneForOneStrategy =
      OneForOneStrategy(maxNrOfRetries = 0) {
        case _: Exception      => Stop
        case _: AssertionError => Stop
      }

    schedulerRef ! ScheduleTrigger(InitializeTrigger(0), self)

    if (beamServices.beamConfig.beam.experimental.optimizer.enabled) {
      //Create the solver actor
      val solver = context.actorOf(beam.agentsim.agents.household.HouseholdSolverActor.props)
      //Tell it to begin solving
      solver ! beam.agentsim.agents.household.BeginSolving
      //Watch for it to die so that it allows this parent to complete
      context.watch(solver)
    }

    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val pop: org.matsim.api.core.v01.population.Population = population

    override def receive: Receive = {

      case TriggerWithId(InitializeTrigger(_), triggerId) =>
        val fleetManager =
          context.actorOf(Props(new HouseholdFleetManager(parkingManager, vehicles, homeCoord)), "household-fleet")
        context.watch(fleetManager)
        schedulerRef ! ScheduleTrigger(InitializeTrigger(0), fleetManager)
        household.members.foreach { person =>
          val attributes = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]
          val modeChoiceCalculator = modeChoiceCalculatorFactory(attributes)
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
              fleetManager +: sharedVehicleFleets
            ),
            person.getId.toString
          )
          context.watch(personRef)
          schedulerRef ! ScheduleTrigger(InitializeTrigger(0), personRef)
          schedulerRef ! CompletionNotice(triggerId, Vector())
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
