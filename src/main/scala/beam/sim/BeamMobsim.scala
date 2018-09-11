package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.ridehail.RideHailSurgePricingManager
import beam.sim.metrics.MetricsSupport
import beam.utils._
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim

import scala.concurrent.Await

/**
  * AgentSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamMobsim @Inject()(
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val scenario: Scenario,
  val eventsManager: EventsManager,
  val actorSystem: ActorSystem,
  val rideHailSurgePricingManager: RideHailSurgePricingManager
) extends Mobsim
    with LazyLogging
    with MetricsSupport {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override def run(): Unit = {
    logger.info("Starting Iteration")
    startMeasuringIteration(beamServices.iterationNumber)
    logger.info("Preparing new Iteration (Start)")
    startSegment("iteration-preparation", "mobsim")

    if (beamServices.beamConfig.beam.debug.debugEnabled)
      logger.info(DebugLib.gcAndGetMemoryLogMessage("run.start (after GC): "))
    beamServices.startNewIteration
    eventsManager.initProcessing()
    val iteration = actorSystem.actorOf(IterationActor.props(beamServices, transportNetwork, scenario, eventsManager,
      rideHailSurgePricingManager), "BeamMobsim.iteration")
    logger.info("Iteration result: {}", Await.result(iteration ? "Run!", timeout.duration))
    iteration ! PoisonPill
    logger.info("Agentsim finished.")

    eventsManager.finishProcessing()
    logger.info("Events drained.")
    endSegment("agentsim-events", "agentsim")

    logger.info("Processing Agentsim Events (End)")
  }
}
