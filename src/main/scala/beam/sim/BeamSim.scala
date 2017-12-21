package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.physsim.jdeqsim.{AgentSimToPhysSimPlanConverter, CreateGraphsFromEvents}
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import com.google.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.VehicleCapacity

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await

class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val beamServices: BeamServices,
                        private val eventsManager: EventsManager,
                        private val scenario: Scenario,
                       ) extends StartupListener with IterationEndsListener with ShutdownListener {

  private var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = _
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var createGraphsFromEvents: CreateGraphsFromEvents = _;

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass, beamServices)

    // Before we initialize router we need to scale the transit vehicle capacities
    val alreadyScaled: mutable.HashSet[VehicleCapacity] = mutable.HashSet()
    scenario.getTransitVehicles.getVehicleTypes.asScala.foreach { case (_, vehType) =>
      val theCap: VehicleCapacity = vehType.getCapacity
      if (!alreadyScaled.contains(theCap)) {
        theCap.setSeats(math.round(theCap.getSeats * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        theCap.setStandingRoom(math.round(theCap.getStandingRoom * beamServices.beamConfig.beam.agentsim.tuning.transitCapacity).toInt)
        alreadyScaled.add(theCap)
      }
    }

    val fareCalculator = new FareCalculator(beamServices.beamConfig.beam.routing.r5.directory)
    beamServices.beamRouter = actorSystem.actorOf(BeamRouter.props(beamServices, scenario.getNetwork, eventsManager, scenario.getTransitVehicles, fareCalculator), "router")
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(eventsManager, event.getServices.getControlerIO, scenario, beamServices.geo, beamServices.registry, beamServices.beamRouter, beamServices.beamConfig.beam.outputs.writeEventsInterval)

    createGraphsFromEvents = new CreateGraphsFromEvents(eventsManager, event.getServices.getControlerIO, scenario, beamServices.geo, beamServices.registry, beamServices.beamRouter, beamServices.beamConfig.beam.outputs.writeEventsInterval)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    agentSimToPhysSimPlanConverter.startPhysSim(event)
    createGraphsFromEvents.createGraph(event);
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.terminate()
  }

}



