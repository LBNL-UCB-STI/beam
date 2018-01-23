package beam.agentsim.agents

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Terminated}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle._
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

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

  def dieIfNoChildren() = {
    if (context.children.isEmpty) {
      context.stop(self)
    }
  }

}

object Population {
  def props(scenario: Scenario, services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager) = {
    Props(new Population(scenario, services, transportNetwork, eventsManager))
  }
}