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
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._

class Population(val beamServices: BeamServices, val transportNetwork: TransportNetwork, val eventsManager: EventsManager) extends Actor with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy: OneForOneStrategy =
  OneForOneStrategy(maxNrOfRetries = 0) {
    case _: Exception => Stop
    case _: AssertionError => Stop
  }

  private var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
  beamServices.households.foreach {
    case (householdId, matSimHousehold) =>
      personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => personId -> householdId)
  }

  // Every Person gets a HumanBodyVehicle

  for ((personId, matsimPerson) <- beamServices.persons.take(beamServices.beamConfig.beam.agentsim.numAgents)) { // if personId.toString.startsWith("9607-") ){
    val bodyVehicleIdFromPerson = createId(personId)
    val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson, MatsimHumanBodyVehicleType)
    // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
    //let's put here human body vehicle too, it should be clean up on each iteration


    val ref: ActorRef = context.actorOf(PersonAgent.props(beamServices, transportNetwork, eventsManager, personId, personToHouseholdId(personId), matsimPerson.getSelectedPlan, bodyVehicleIdFromPerson), PersonAgent.buildActorName(personId))
    beamServices.vehicles += ((bodyVehicleIdFromPerson, new BeamVehicle(Some(ref), powerTrainForHumanBody(), matsimBodyVehicle, None, HumanBodyVehicle)))
    beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
    beamServices.personRefs += ((personId, ref))
  }

  dieIfNoChildren()

  override def receive = {
    case Finish =>
      context.children.foreach(_ ! Finish)
      dieIfNoChildren()
    case Terminated(_) =>
      dieIfNoChildren()
  }

  def dieIfNoChildren() = {
    if (context.children.isEmpty) {
      context.stop(self)
    }
  }

}

object Population {
  def props(services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager) = {
    Props(new Population(services, transportNetwork, eventsManager))
  }
}