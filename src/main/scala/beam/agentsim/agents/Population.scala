package beam.agentsim.agents

import akka.actor.SupervisorStrategy.{Resume, Stop}
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.BeamVehicleType.HumanBodyVehicle
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Plan}
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._

class Population(val beamServices: BeamServices) extends Actor with ActorLogging {

  // Our PersonAgents have their own explicit error state into which they recover
  // by themselves. So we do not restart them.
  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0) {
      case _: Exception      => Stop
    }

  private var personToHouseholdId: Map[Id[Person], Id[Household]] = Map()
  beamServices.households.foreach {
    case (householdId, matSimHousehold) =>
      personToHouseholdId = personToHouseholdId ++ matSimHousehold.getMemberIds.asScala.map(personId => personId -> householdId)
  }

  // Every Person gets a HumanBodyVehicle
  private val matsimHumanBodyVehicleType = VehicleUtils.getFactory.createVehicleType(Id.create("HumanBodyVehicle", classOf[VehicleType]))
  matsimHumanBodyVehicleType.setDescription("Human")

  for ((personId, matsimPerson) <- beamServices.persons.take(beamServices.beamConfig.beam.agentsim.numAgents)) {
    val bodyVehicleIdFromPerson = HumanBodyVehicle.createId(personId)
    val ref: ActorRef = context.system.actorOf(PersonAgent.props(beamServices, personId, personToHouseholdId(personId)
      , matsimPerson.getSelectedPlan, bodyVehicleIdFromPerson), PersonAgent.buildActorName(personId))

    // Human body vehicle initialization
    val matsimBodyVehicle = VehicleUtils.getFactory.createVehicle(bodyVehicleIdFromPerson,
      HumanBodyVehicle.MatsimHumanBodyVehicleType)
    val bodyVehicle = new BeamVehicle(Option(ref), HumanBodyVehicle.powerTrainForHumanBody(),
      matsimBodyVehicle, None, HumanBodyVehicle)

    // real vehicle( car, bus, etc.)  should be populated from config in notifyStartup
    //let's put here human body vehicle too, it should be clean up on each iteration
    beamServices.beamVehicles += ((bodyVehicleIdFromPerson, bodyVehicle))

    beamServices.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
    beamServices.personRefs += ((personId, ref))
  }


  override def receive = PartialFunction.empty

}

object Population {
  def props(services: BeamServices) = {
    Props(new Population(services))
  }
}
