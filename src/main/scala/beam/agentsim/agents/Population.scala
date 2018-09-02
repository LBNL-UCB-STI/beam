package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, Identify, OneForOneStrategy, Props, Terminated}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.Population.InitParkingVehicles
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.{BeamVehicle, BicycleFactory}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.vehicleId2BeamVehicleId
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.sim.BeamServices
import beam.utils.BeamVehicleUtils.makeHouseholdVehicle
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.contrib.bicycle.BicycleUtils
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.{mutable, JavaConverters}
import scala.concurrent.{Await, Future}
import scala.util.Try

class Population(
  val scenario: Scenario,
  val beamServices: BeamServices,
  val scheduler: ActorRef,
  val transportNetwork: TransportNetwork,
  val router: ActorRef,
  val rideHailManager: ActorRef,
  val parkingManager: ActorRef,
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

  var initParkingVeh: Seq[ActorRef] = Nil

  private val personToHouseholdId: mutable.Map[Id[Person], Id[Household]] =
    mutable.Map[Id[Person], Id[Household]]()
  scenario.getHouseholds.getHouseholds.forEach { (householdId, matSimHousehold) =>
    personToHouseholdId ++= matSimHousehold.getMemberIds.asScala
      .map(personId => personId -> householdId)
  }

  // Init households before RHA.... RHA vehicles will initially be managed by households
  initHouseholds()

  override def receive: PartialFunction[Any, Unit] = {
    case Terminated(_) =>
    // Do nothing
    case Finish =>
      context.children.foreach(_ ! Finish)
      initParkingVeh.foreach(context.stop(_))
      initParkingVeh = Nil
      dieIfNoChildren()
      context.become {
        case Terminated(_) =>
          dieIfNoChildren()
      }
    case InitParkingVehicles =>
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

        var houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] =
          Population.getVehiclesFromHousehold(household, beamServices)

        houseHoldVehicles.foreach(x => beamServices.vehicles.update(x._1, x._2))

        val householdActor = context.actorOf(
          HouseholdActor.props(
            beamServices,
            beamServices.modeChoiceCalculatorFactory,
            scheduler,
            transportNetwork,
            router,
            rideHailManager,
            parkingManager,
            eventsManager,
            scenario.getPopulation,
            household.getId,
            household,
            houseHoldVehicles,
            homeCoord
          ),
          household.getId.toString
        )

        houseHoldVehicles.values.foreach { veh =>
          veh.manager = Some(householdActor)
        }

        houseHoldVehicles.foreach {
          vehicle =>
            val initParkingVehicle = context.actorOf(Props(new Actor with ActorLogging {
              parkingManager ! ParkingInquiry(
                Id.createPersonId("atHome"),
                homeCoord,
                homeCoord,
                "home",
                0,
                NoNeed,
                0,
                0
              ) //TODO personSelectedPlan.getType is null

              def receive = {
                case ParkingInquiryResponse(stall) =>
                  vehicle._2.useParkingStall(stall)
                  context.stop(self)
                //TODO deal with timeouts and errors
              }
            }))
            initParkingVeh :+= initParkingVehicle
        }

        context.watch(householdActor)
        householdActor ? Identify(0)
      })
    Await.result(houseHoldsInitialized, timeout.duration)
    log.info(s"Initialized ${scenario.getHouseholds.getHouseholds.size} households")
  }

}

object Population {

  case object InitParkingVehicles

  def getVehiclesFromHousehold(
    household: Household,
    beamServices: BeamServices
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
          case Right(vehicle) => vehicleId2BeamVehicleId(id) -> vehicle
        }
      })
      .toMap
  }

  def props(
    scenario: Scenario,
    services: BeamServices,
    scheduler: ActorRef,
    transportNetwork: TransportNetwork,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    eventsManager: EventsManager
  ): Props = {
    Props(
      new Population(
        scenario,
        services,
        scheduler,
        transportNetwork,
        router,
        rideHailManager,
        parkingManager,
        eventsManager
      )
    )
  }

}
