package beam.agentsim.agents

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.replanning.AddSupplementaryTrips
import beam.router.RouteHistory
import beam.router.osm.TollCalculator
import beam.sim.{BeamScenario, BeamServices}
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household

import scala.collection.JavaConverters

class Population(
  val scenario: Scenario,
  val beamScenario: BeamScenario,
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val router: ActorRef,
  val rideHailManager: ActorRef,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val sharedVehicleFleets: Seq[ActorRef],
  val eventsManager: EventsManager,
  val routeHistory: RouteHistory,
  boundingBox: Envelope
) extends Actor
    with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }
  initHouseholds()

  override def receive: PartialFunction[Any, Unit] = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId, Vector())
    case Terminated(_) =>
    // Do nothing
    case Finish =>
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
      context.become {
        case Terminated(_) =>
          dieIfNoChildren()
      }
  }

  def dieIfNoChildren(): Unit = {
    if (context.children.isEmpty) {
      context.stop(self)
    } else {
      log.debug("Remaining: {}", context.children)
    }
  }

  private def initHouseholds(iterId: Option[String] = None): Unit = {
    scenario.getHouseholds.getHouseholds.values().forEach { household =>
      //TODO a good example where projection should accompany the data
      if (scenario.getHouseholds.getHouseholdAttributes
            .getAttribute(household.getId.toString, "homecoordx") == null) {
        log.error(
          s"Cannot find homeCoordX for household ${household.getId} which will be interpreted at 0.0"
        )
      }
      if (scenario.getHouseholds.getHouseholdAttributes
            .getAttribute(household.getId.toString.toLowerCase(), "homecoordy") == null) {
        log.error(
          s"Cannot find homeCoordY for household ${household.getId} which will be interpreted at 0.0"
        )
      }
      val homeCoord = new Coord(
        scenario.getHouseholds.getHouseholdAttributes
          .getAttribute(household.getId.toString, "homecoordx")
          .asInstanceOf[Double],
        scenario.getHouseholds.getHouseholdAttributes
          .getAttribute(household.getId.toString, "homecoordy")
          .asInstanceOf[Double]
      )

      val householdVehicles: Map[Id[BeamVehicle], BeamVehicle] = JavaConverters
        .collectionAsScalaIterable(household.getVehicleIds)
        .map { vid =>
          val bvid = BeamVehicle.createId(vid)
          bvid -> beamScenario.privateVehicles(bvid)
        }
        .toMap
      val householdActor = context.actorOf(
        HouseholdActor.props(
          beamServices,
          beamScenario,
          beamServices.modeChoiceCalculatorFactory,
          scheduler,
          transportNetwork,
          tollCalculator,
          router,
          rideHailManager,
          parkingManager,
          chargingNetworkManager,
          eventsManager,
          scenario.getPopulation,
          household,
          householdVehicles,
          homeCoord,
          sharedVehicleFleets,
          routeHistory,
          boundingBox
        ),
        household.getId.toString
      )
      context.watch(householdActor)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
    }
    log.info(s"Initialized ${scenario.getHouseholds.getHouseholds.size} households")
  }

}

object Population {
  val defaultVehicleRange = 500e3
  val refuelRateLimitInWatts: Option[_] = None

  def getVehiclesFromHousehold(
    household: Household,
    beamScenario: BeamScenario
  ): Map[Id[BeamVehicle], BeamVehicle] = {
    val houseHoldVehicles = JavaConverters.collectionAsScalaIterable(household.getVehicleIds)
    houseHoldVehicles.map(i => Id.create(i, classOf[BeamVehicle]) -> beamScenario.privateVehicles(i)).toMap
  }

  def personInitialLocation(person: Person): Coord =
    person.getSelectedPlan.getPlanElements
      .iterator()
      .next()
      .asInstanceOf[Activity]
      .getCoord

  def props(
    scenario: Scenario,
    beamScenario: BeamScenario,
    services: BeamServices,
    scheduler: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    chargingNetworkManager: ActorRef,
    sharedVehicleFleets: Seq[ActorRef],
    eventsManager: EventsManager,
    routeHistory: RouteHistory,
    boundingBox: Envelope
  ): Props = {
    Props(
      new Population(
        scenario,
        beamScenario,
        services,
        scheduler,
        transportNetwork,
        tollCalculator,
        router,
        rideHailManager,
        parkingManager,
        chargingNetworkManager,
        sharedVehicleFleets,
        eventsManager,
        routeHistory,
        boundingBox
      )
    )
  }
}
