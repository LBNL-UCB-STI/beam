package beam.playground.beamSimAkkaProtoType;

import java.util.concurrent.TimeUnit;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;
import org.matsim.core.scenario.ScenarioUtils;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.stream.scaladsl.BroadcastHub.Consumer;
import akka.util.Timeout;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ChargingInfrastructureManager;
import beam.playground.beamSimAkkaProtoType.scheduler.Scheduler;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;

public class BeamSimAkkaMain {

	public static void main(String[] args) {
		// TODO: read Plans
		// TODO: introduce infrastructure (3 times more chargers than cars).
		
		
		Config config = ConfigUtils.loadConfig(
		"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config2000.xml");

//		Config config = ConfigUtils.loadConfig(
//				"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config_plans1.xml");
		
		
		Scenario scenario = ScenarioUtils.loadScenario(config);
		
		
		JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
		
		ActorSystem system = ActorSystem.create("AgentSim");
		
		ActorRef chargingInfrastructureManager = system.actorOf(Props.create(ChargingInfrastructureManager.class,10),"chargingInfrastructureManager");
		
		ActorRef scheduler = system.actorOf(Props.create(Scheduler.class,scenario.getPopulation(),chargingInfrastructureManager),"scheduler");
        
		scheduler.tell(new StartSimulationMessage(), ActorRef.noSender());
        
        system.awaitTermination();
	}
	
}
