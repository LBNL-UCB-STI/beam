package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Identify, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents._
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.physsim.jdeqsim.AgentSimToPhysSimPlanConverter
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.{VehicleCapacity, VehicleType}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await

class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val beamServices: BeamServices
                       ) extends StartupListener with IterationEndsListener with ShutdownListener {

  private val agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(beamServices)
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass, beamServices)

    val schedulerFuture = beamServices.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler], beamServices.beamConfig, 3600 * 30.0, 300.0))
    beamServices.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

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

    val fareCalculator = new FareCalculator(beamServices.beamConfig.beam.routing.r5.directory)

    val router = actorSystem.actorOf(BeamRouter.props(beamServices, beamServices.matsimServices.getScenario.getTransitVehicles, fareCalculator), "router")
    beamServices.beamRouter = router
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    val rideHailingManagerFuture = beamServices.registry ? Registry.Register("RideHailingManager", RideHailingManager.props("RideHailingManager",
      Map[Id[VehicleType], BigDecimal](), beamServices.vehicles.toMap, beamServices, Map.empty))
    beamServices.rideHailingManager = Await.result(rideHailingManagerFuture, timeout.duration).asInstanceOf[Created].ref
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    agentSimToPhysSimPlanConverter.startPhysSim()
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    actorSystem.stop(beamServices.schedulerRef)
    actorSystem.terminate()
  }

}



