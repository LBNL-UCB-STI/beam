package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.util.Timeout
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import com.google.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.VehicleCapacity

import scala.collection.JavaConverters._
import scala.collection.mutable

class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val beamServices: BeamServices,
                        private val eventsManager: EventsManager,
                        private val scenario: Scenario,
                       ) extends StartupListener with IterationEndsListener with ShutdownListener {

  private val agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(eventsManager, scenario, beamServices.geo, beamServices.registry, beamServices.beamRouter)
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass, beamServices)


    // Before we initialize router we need to scale the transit vehicle capacities
    val alreadyScaled: mutable.HashSet[VehicleCapacity] = mutable.HashSet()
    beamServices.matsimServices.getScenario.getTransitVehicles.getVehicleTypes.asScala.foreach{ case(_, vehType) =>
      val theCap: VehicleCapacity = vehType.getCapacity
      if(!alreadyScaled.contains(theCap)){
        theCap.setSeats(math.round(theCap.getSeats * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        theCap.setStandingRoom(math.round(theCap.getStandingRoom * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        alreadyScaled.add(theCap)
      }
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    agentSimToPhysSimPlanConverter.startPhysSim()
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.terminate()
  }

}



