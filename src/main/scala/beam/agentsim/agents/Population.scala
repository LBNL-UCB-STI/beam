package beam.agentsim.agents

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, PoisonPill, Props, Terminated}
import beam.agentsim
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.household.HouseholdActor
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.{CarVehicle, HumanBodyVehicle}
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters
import scala.collection.JavaConverters._

class Population(val scenario: Scenario, val beamServices: BeamServices, val transportNetwork: TransportNetwork, val eventsManager: EventsManager) extends Actor with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
  OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
    case _: AssertionError => Stop
  }

  private var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
  scenario.getHouseholds.getHouseholds.forEach { (householdId, matSimHousehold) =>
      personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => personId -> householdId)
  }

  // Every Person gets a HumanBodyVehicle

  scenario.getPopulation.getPersons.values().stream().limit(beamServices.beamConfig.beam.agentsim.numAgents).forEach { matsimPerson =>
    val bodyVehicleIdFromPerson = createId(matsimPerson.getId)
    val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson, MatsimHumanBodyVehicleType)
    // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
    //let's put here human body vehicle too, it should be clean up on each iteration


    val personRef: ActorRef = context.actorOf(PersonAgent.props(beamServices, transportNetwork, eventsManager, matsimPerson.getId, scenario.getHouseholds.getHouseholds.get(personToHouseholdId(matsimPerson.getId)), matsimPerson.getSelectedPlan, bodyVehicleIdFromPerson), PersonAgent.buildActorName(matsimPerson.getId))
    context.watch(personRef)
    val newBodyVehicle = new BeamVehicle(powerTrainForHumanBody(), matsimBodyVehicle, None, HumanBodyVehicle)
    newBodyVehicle.registerResource(personRef)
    beamServices.vehicles += ((bodyVehicleIdFromPerson, newBodyVehicle))
    beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), personRef)
    beamServices.personRefs += ((matsimPerson.getId, personRef))
  }

  // Init households before RHA.... RHA vehicles will initially be managed by households
  initHouseholds()


  override def receive = {
    case Terminated(_) =>
      // Do nothing
    case Finish =>
      cleanupHouseHolder()
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
      context.become {
        case Terminated(_) =>
          dieIfNoChildren()
      }
  }

  def dieIfNoChildren() = {
    if (context.children.isEmpty) {
      context.stop(self)
    } else {
      log.debug("Remaining: {}", context.children)
    }

  }

  private def initHouseholds(iterId: Option[String] = None)(implicit ev: Id[Vehicle] => Id[BeamVehicle]): Unit = {
    val householdAttrs = scenario.getHouseholds.getHouseholdAttributes

    scenario.getHouseholds.getHouseholds.forEach { (householdId, matSimHousehold) =>
      //TODO a good example where projection should accompany the data
      if (householdAttrs.getAttribute(householdId.toString, "homecoordx") == null) {
        log.error(s"Cannot find homeCoordX for household $householdId which will be interpreted at 0.0")
      }
      if (householdAttrs.getAttribute(householdId.toString.toLowerCase(), "homecoordy") == null) {
        log.error(s"Cannot find homeCoordY for household $householdId which will be interpreted at 0.0")
      }
      val homeCoord = new Coord(householdAttrs.getAttribute(householdId.toString, "homecoordx").asInstanceOf[Double],
        householdAttrs.getAttribute(householdId.toString, "homecoordy").asInstanceOf[Double]
      )

      val membersActors = matSimHousehold.getMemberIds.asScala.map { personId =>
        (personId, beamServices.personRefs.get(personId))
      }.collect {
        case (personId, Some(personAgent)) => (personId, personAgent)
      }.toMap

      val houseHoldVehicles: Map[Id[BeamVehicle], BeamVehicle] = JavaConverters
        .collectionAsScalaIterable(matSimHousehold.getVehicleIds)
        .map({ id =>
          val matsimVehicle = JavaConverters.mapAsScalaMap(
            scenario.getVehicles.getVehicles)(
            id)
          val information = Option(matsimVehicle.getType.getEngineInformation)
          val vehicleAttribute = Option(
            scenario.getVehicles.getVehicleAttributes)
          val powerTrain = Powertrain.PowertrainFromMilesPerGallon(
            information
              .map(_.getGasConsumption)
              .getOrElse(Powertrain.AverageMilesPerGallon))
          agentsim.vehicleId2BeamVehicleId(id) -> new BeamVehicle(
            powerTrain,
            matsimVehicle,
            vehicleAttribute,
            CarVehicle)
        }).toMap

      houseHoldVehicles.foreach(x => beamServices.vehicles.update(x._1, x._2))

      val householdActor = context.actorOf(
        HouseholdActor.props(beamServices, eventsManager, scenario.getPopulation, householdId, matSimHousehold, houseHoldVehicles, membersActors, homeCoord),
        HouseholdActor.buildActorName(householdId, iterId))

      houseHoldVehicles.values.foreach { veh => veh.manager = Some(householdActor) }


      beamServices.householdRefs.put(householdId, householdActor)
      context.watch(householdActor)
    }
  }

  private def cleanupHouseHolder(): Unit = {
    for ((_, householdAgent) <- beamServices.householdRefs) {
      log.debug(s"Stopping ${householdAgent.path.name} ")
      householdAgent ! PoisonPill
    }
  }

}

object Population {
  def props(scenario: Scenario, services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager) = {
    Props(new Population(scenario, services, transportNetwork, eventsManager))
  }
}