package beam.agentsim.agents

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.household.HouseholdActor.{GetVehicleTypes, VehicleTypesResponse}
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.FleetStoredElectricityEvent
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.RouteHistory
import beam.router.osm.TollCalculator
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.MathUtils
import beam.utils.logging.LoggingMessageActor
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household

import java.util.concurrent.TimeUnit
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
  val routeHistory: RouteHistory
) extends LoggingMessageActor
    with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }

  override def loggedReceive: PartialFunction[Any, Unit] = { case TriggerWithId(InitializeTrigger(_), triggerId) =>
    implicit val timeout: Timeout = Timeout(120, TimeUnit.SECONDS)
    sharedVehicleFleets.foreach(_ ! GetVehicleTypes(triggerId))
    contextBecome(getVehicleTypes(triggerId, sharedVehicleFleets.size, Set.empty))
  }

  def getVehicleTypes(triggerId: Long, responsesLeft: Int, vehicleTypes: Set[BeamVehicleType]): Receive = {
    if (responsesLeft <= 0) {
      finishInitialization(triggerId, vehicleTypes)
    } else { case VehicleTypesResponse(sharedVehicleTypes, _) =>
      contextBecome(getVehicleTypes(triggerId, responsesLeft - 1, vehicleTypes ++ sharedVehicleTypes))
    }
  }

  def finishInitialization(triggerId: Long, vehicleTypes: Set[BeamVehicleType]): Receive = {
    initHouseholds(vehicleTypes)
    eventsManager.processEvent(createStoredElectricityEvent(0))
    scheduler ! CompletionNotice(triggerId, Vector())
    val awaitFinish: Receive = {
      case Terminated(_) =>
      // Do nothing
      case Finish =>
        eventsManager.processEvent(
          createStoredElectricityEvent(Time.parseTime(beamServices.beamConfig.beam.agentsim.endTime).toInt)
        )
        context.children.foreach(_ ! Finish)
        dieIfNoChildren()
        contextBecome { case Terminated(_) =>
          dieIfNoChildren()
        }
    }
    awaitFinish
  }

  def dieIfNoChildren(): Unit = {
    if (context.children.isEmpty) {
      context.stop(self)
    } else {
      log.debug("Remaining: {}", context.children)
    }
  }

  private def initHouseholds(sharedVehicleTypes: Set[BeamVehicleType]): Unit = {
    scenario.getHouseholds.getHouseholds.values().forEach { household =>
      //TODO a good example where projection should accompany the data
      if (
        scenario.getHouseholds.getHouseholdAttributes
          .getAttribute(household.getId.toString, "homecoordx") == null
      ) {
        log.error(
          s"Cannot find homeCoordX for household ${household.getId} which will be interpreted at 0.0"
        )
      }
      if (
        scenario.getHouseholds.getHouseholdAttributes
          .getAttribute(household.getId.toString, "homecoordy") == null
      ) {
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
          val bv = beamScenario.privateVehicles(BeamVehicle.createId(vid))
          val reservedFor =
            VehicleManager.createOrGetReservedFor(household.getId.toString, VehicleManager.TypeEnum.Household)
          bv.vehicleManagerId.set(reservedFor.managerId)
          bv.id -> bv
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
          sharedVehicleTypes,
          routeHistory
        ),
        household.getId.toString
      )
      context.watch(householdActor)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), householdActor)
    }
    log.info(s"Initialized ${scenario.getHouseholds.getHouseholds.size} households")
  }

  private def createStoredElectricityEvent(tick: Int) = {
    val (storedElectricityInJoules, storageCapacityInJoules) = beamServices.beamScenario.privateVehicles.values
      .filter(_.beamVehicleType.primaryFuelType == Electricity)
      .foldLeft(0.0, 0.0) { case ((fuelLevel, fuelCapacity), vehicle) =>
        val primaryFuelCapacityInJoule = vehicle.beamVehicleType.primaryFuelCapacityInJoule
        (
          fuelLevel + MathUtils.clamp(vehicle.primaryFuelLevelInJoules, 0, primaryFuelCapacityInJoule),
          fuelCapacity + primaryFuelCapacityInJoule
        )
      }
    new FleetStoredElectricityEvent(tick, "all-private-vehicles", storedElectricityInJoules, storageCapacityInJoules)
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
    routeHistory: RouteHistory
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
        routeHistory
      )
    )
  }
}
