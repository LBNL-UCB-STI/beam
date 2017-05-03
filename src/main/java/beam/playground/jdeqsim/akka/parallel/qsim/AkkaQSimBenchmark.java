package beam.playground.jdeqsim.akka.parallel.qsim;

import java.util.LinkedList;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import beam.playground.jdeqsim.akka.AkkaEventHandlerAdapter;
import beam.playground.jdeqsim.akka.EventManagerActor;
import beam.playground.jdeqsim.akka.JDEQSimActor;

public class AkkaQSimBenchmark {

	public static void main(String[] args) {
		
		ActorSystem system = ActorSystem.create("PhysicalSimulation-Benchmark");
		LinkedList<ActorRef> qfakeModels=new LinkedList();
		
		for (int i=0;i<JavaSingleThreadQsimBenchmark.numberOfFakeLinks;i++){
			qfakeModels.add(system.actorOf(Props.create(QFakeModelActor.class)));
		}
		ActorRef centralClockActor = system.actorOf(Props.create(CentralClockActor.class,qfakeModels));
        
		centralClockActor.tell("start", ActorRef.noSender());
		
	}
	
}
