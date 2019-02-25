package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, Identify, OneForOneStrategy, Props, Terminated}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.{BeamVehicle, BicycleFactory}
import beam.agentsim.vehicleId2BeamVehicleId
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.BeamVehicleUtils.makeHouseholdVehicle
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.{Await, Future}

class Population(
  val scenario: Scenario,
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val router: ActorRef,
  val rideHailManager: ActorRef,
  val parkingManager: ActorRef,
  val sharedVehicleFleets: Seq[ActorRef],
  val eventsManager: EventsManager
) extends Actor
    with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
      case _: AssertionError => Stop
    }
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private val personToHouseholdId: mutable.Map[Id[Person], Id[Household]] =
    mutable.Map()
  scenario.getHouseholds.getHouseholds.forEach { (householdId, matSimHousehold) =>
    personToHouseholdId ++= matSimHousehold.getMemberIds.asScala
      .map(personId => personId -> householdId)
  }

  initHouseholds()

  override def receive: PartialFunction[Any, Unit] = {
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
    import scala.concurrent.ExecutionContext.Implicits.global
    try {
      // Have to wait for households to create people so they can send their first trigger to the scheduler
      val houseHoldsInitialized =
        Future.sequence(scenario.getHouseholds.getHouseholds.values().asScala.map { household =>
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
              bvid -> beamServices.privateVehicles(bvid)
            }
            .toMap
          val householdActor = context.actorOf(
            HouseholdActor.props(
              beamServices,
              beamServices.modeChoiceCalculatorFactory,
              scheduler,
              transportNetwork,
              tollCalculator,
              router,
              rideHailManager,
              parkingManager,
              eventsManager,
              scenario.getPopulation,
              household,
              householdVehicles,
              homeCoord,
              sharedVehicleFleets
            ),
            household.getId.toString
          )

          context.watch(householdActor)
          householdActor ? Identify(0)
        })
      Await.result(houseHoldsInitialized, timeout.duration)
      log.info(s"Initialized ${scenario.getHouseholds.getHouseholds.size} households")
    } catch {
      case e: Exception =>
        log.error(e, "Error initializing houseHolds")
        throw e
    }
  }

}

object Population {
  val defaultVehicleRange = 500e3
  val refuelRateLimitInWatts: Option[_] = None

  def getVehiclesFromHousehold(
    household: Household,
    beamServices: BeamServices,
  ): Map[Id[BeamVehicle], BeamVehicle] = {
    val houseHoldVehicles: Iterable[Id[Vehicle]] =
      JavaConverters.collectionAsScalaIterable(household.getVehicleIds)

    // Add bikes
    if (beamServices.beamConfig.beam.agentsim.agents.vehicles.bicycles.useBikes) {
      val bikeFactory = new BicycleFactory(beamServices.matsimServices.getScenario, beamServices)
      bikeFactory.bicyclePrepareForSim()
    }
    houseHoldVehicles
      .map({ id =>
        makeHouseholdVehicle(beamServices.privateVehicles, id) match {
          case Right(vehicle) => beam.agentsim.vehicleId2BeamVehicleId(id) -> vehicle
          case Left(e)        => throw e
        }
      })
      .toMap
  }

  def personInitialLocation(person: Person): Coord =
    person.getSelectedPlan.getPlanElements
      .iterator()
      .next()
      .asInstanceOf[Activity]
      .getCoord

  def props(
    scenario: Scenario,
    services: BeamServices,
    scheduler: ActorRef,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    sharedVehicleFleets: Seq[ActorRef],
    eventsManager: EventsManager
  ): Props = {
    Props(
      new Population(
        scenario,
        services,
        scheduler,
        transportNetwork,
        tollCalculator,
        router,
        rideHailManager,
        parkingManager,
        sharedVehicleFleets,
        eventsManager
      )
    )
  }
}
