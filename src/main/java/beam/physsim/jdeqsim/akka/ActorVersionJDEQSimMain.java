package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

//import akka.stream.scaladsl.BroadcastHub.Consumer;


public class ActorVersionJDEQSimMain {

	public static void main(String[] args) {
//		 See beam.agentsim.sim.AgentsimServices
//		@Depracated
//
//		Config config = ConfigUtils.loadConfig(
//				"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml");
//		
//		Scenario scenario = ScenarioUtils.loadScenario(config);
//		
//		EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
//		CountEnterLinkEvents countEnterLinkEvents = new CountEnterLinkEvents();
//		eventsManager.addHandler(countEnterLinkEvents);
//		eventsManager.initProcessing();
//		
//		JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
//		JDEQSimulation jdeqSimulation=new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);
//		
//		jdeqSimulation.run();
//		
//		eventsManager.finishProcessing();
//		System.out.println(countEnterLinkEvents.getLinkEnterCount());
		
		
		Config config = ConfigUtils.loadConfig(
		"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml");

		Scenario scenario = ScenarioUtils.loadScenario(config);
		
		
		JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
		
		ActorSystem system = ActorSystem.create("PhysicalSimulation");
		ActorRef eventHandlerActorREF = system.actorOf(Props.create(EventManagerActor.class));
		EventsManager eventsManager = new AkkaEventHandlerAdapter(eventHandlerActorREF);
		ActorRef jdeqsimActorREF = system.actorOf(Props.create(JDEQSimActor.class,jdeqSimConfigGroup,scenario,eventsManager));
        
        jdeqsimActorREF.tell("start", ActorRef.noSender());
        eventHandlerActorREF.tell("registerJDEQSimREF", eventHandlerActorREF);
        
//        for (int i = 1; i <= 10; i++) {
//            System.out.println(">>> Producing & sending a number " +  i);
//            printNumbersConsumer.tell(i, ActorRef.noSender());
//        }
        system.awaitTermination();
        //System.out.println("===== Finished producing & sending numbers 1 to 10");
       // Await.ready(system.whenTerminated, new Timeout(FiniteDuration.create(1, java.util.concurrent.TimeUnit.SECONDS)));
        
        //Timeout timeout = new Timeout(FiniteDuration.create(1, java.util.concurrent.TimeUnit.SECONDS));
	}
	
}
