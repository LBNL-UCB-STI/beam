package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, Identify, OneForOneStrategy, Props, Terminated}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.Vehicles

import scala.collection.JavaConverters
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}

class Population(val scenario: Scenario, val beamServices: BeamServices, val scheduler: ActorRef, val transportNetwork: TransportNetwork, val router: ActorRef, val rideHailingManager: ActorRef, val eventsManager: EventsManager) extends Actor with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
  OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
    case _: AssertionError => Stop
  }
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  import context.dispatcher

  private var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
  scenario.getHouseholds.getHouseholds.forEach { (householdId, matSimHousehold) =>
    personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => personId -> householdId)
  }


  // Init households before RHA.... RHA vehicles will initially be managed by households
  initHouseholds()


  override def receive = {
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
    // Have to wait for households to create people so they can send their first trigger to the scheduler
    val houseHoldsInitialized = Future.sequence(scenario.getHouseholds.getHouseholds.values().asScala.map { household =>
      //TODO a good example where projection should accompany the data
      if (scenario.getHouseholds.getHouseholdAttributes.getAttribute(household.getId.toString, "homecoordx") == null) {
        log.error(s"Cannot find homeCoordX for household ${household.getId} which will be interpreted at 0.0")
      }
      if (scenario.getHouseholds.getHouseholdAttributes.getAttribute(household.getId.toString.toLowerCase(), "homecoordy") == null) {
        log.error(s"Cannot find homeCoordY for household ${household.getId} which will be interpreted at 0.0")
      }
      val homeCoord = new Coord(scenario.getHouseholds.getHouseholdAttributes.getAttribute(household.getId.toString, "homecoordx").asInstanceOf[Double],
        scenario.getHouseholds.getHouseholdAttributes.getAttribute(household.getId.toString, "homecoordy").asInstanceOf[Double]
      )

      val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] = Population.getVehiclesFromHousehold(household, scenario.getVehicles)

      houseHoldVehicles.foreach(x => beamServices.vehicles.update(x._1, x._2))

      val householdActor = context.actorOf(
        HouseholdActor.props(beamServices, beamServices.modeChoiceCalculatorFactory, scheduler, transportNetwork,
          router, rideHailingManager, eventsManager, scenario.getPopulation, household.getId, household, houseHoldVehicles, homeCoord),
        household.getId.toString)

      houseHoldVehicles.values.foreach { veh => veh.manager = Some(householdActor) }

      context.watch(householdActor)
      householdActor ? Identify(0)
    })
    Await.result(houseHoldsInitialized, timeout.duration)
    log.info(s"Initialized ${scenario.getHouseholds.getHouseholds.size} households")
  }


}


object Population {
  def props(scenario: Scenario, services: BeamServices, scheduler: ActorRef, transportNetwork: TransportNetwork, router: ActorRef, rideHailingManager: ActorRef, eventsManager: EventsManager): Props = {
    Props(new Population(scenario, services, scheduler, transportNetwork, router, rideHailingManager, eventsManager))
  }


  def getVehiclesFromHousehold(household: Household, matsimVehicles: Vehicles):
  Map[Id[BeamVehicle], BeamVehicle] = {
    val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] = JavaConverters
      .collectionAsScalaIterable(household.getVehicleIds)
      .map({ id =>
        val matsimVehicle = JavaConverters.mapAsScalaMap(
          matsimVehicles.getVehicles)(
          id)
        val information = Option(matsimVehicle.getType.getEngineInformation)
        val vehicleAttribute = Option(
          matsimVehicles.getVehicleAttributes)
        val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
          information
            .map(_.getGasConsumption)
            .getOrElse(Powertrain.AverageMilesPerGallon))
        agentsim.vehicleId2BeamVehicleId(id) -> new BeamVehicle(
          powerTrain,
          matsimVehicle,
          vehicleAttribute,
          Car)
      }).toMap
    houseHoldVehicles
  }

}
