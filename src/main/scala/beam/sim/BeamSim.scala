package beam.sim

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Identify}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.physsim.jdeqsim.{AgentSimToPhysSimPlanConverter, CreateGraphsFromAgentSimEvents, ExpectedMaxUtilityHeatMap}
import beam.router.BeamRouter
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener, StartupListener}
import org.matsim.vehicles.VehicleCapacity

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val transportNetwork: TransportNetwork,
                        private val beamServices: BeamServices,
                        private val eventsManager: EventsManager,
                        private val scenario: Scenario,
                       ) extends StartupListener with IterationEndsListener with ShutdownListener {

  private var agentSimToPhysSimPlanConverter: AgentSimToPhysSimPlanConverter = _
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  private var createGraphsFromEvents: CreateGraphsFromAgentSimEvents = _;
  private var expectedDisutilityHeatMapDataCollector: ExpectedMaxUtilityHeatMap = _;

  override def notifyStartup(event: StartupEvent): Unit = {
    beamServices.modeChoiceCalculator = ModeChoiceCalculator(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass, beamServices)

    import scala.collection.JavaConverters._
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
    val tollCalculator = new TollCalculator(beamServices.beamConfig.beam.routing.r5.directory)
    beamServices.beamRouter = actorSystem.actorOf(BeamRouter.props(beamServices, transportNetwork, scenario.getNetwork, eventsManager, scenario.getTransitVehicles, fareCalculator, tollCalculator), "router")
    Await.result(beamServices.beamRouter ? Identify(0), timeout.duration)

    beamServices.persons ++= scala.collection.JavaConverters.mapAsScalaMap(scenario.getPopulation.getPersons)
    beamServices.households ++= scenario.getHouseholds.getHouseholds.asScala.toMap
    agentSimToPhysSimPlanConverter = new AgentSimToPhysSimPlanConverter(
      eventsManager,
      transportNetwork,
      event.getServices.getControlerIO,
      scenario,
      beamServices.geo,
      beamServices.beamRouter,
      beamServices.beamConfig.beam.outputs.writeEventsInterval)

    createGraphsFromEvents = new CreateGraphsFromAgentSimEvents(eventsManager, event.getServices.getControlerIO, scenario, beamServices.geo, beamServices.registry, beamServices.beamRouter)

    expectedDisutilityHeatMapDataCollector = new ExpectedMaxUtilityHeatMap(eventsManager, scenario.getNetwork, event.getServices.getControlerIO, beamServices.beamConfig.beam.outputs.writeEventsInterval)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    agentSimToPhysSimPlanConverter.startPhysSim(event)
    createGraphsFromEvents.createGraphs(event);
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    Await.result(actorSystem.terminate(), Duration.Inf)

    def deleteOutputFile(fileName: String) = {
      Files.deleteIfExists(Paths.get(event.getServices.getControlerIO.getOutputFilename(fileName)))
    }

    // remove output files which are not ready for release yet (enable again after Jan 2018)
    deleteOutputFile("traveldistancestats.txt")

    deleteOutputFile("traveldistancestats.png")

    deleteOutputFile("modestats.txt")

    deleteOutputFile("modestats.png")

    deleteOutputFile("tmp")

    for (i <- 0 to 200) {
      Files.deleteIfExists(Paths.get(event.getServices.getControlerIO.getIterationFilename(i, "plans.xml.gz")))
    }
    //===========================
  }
}



