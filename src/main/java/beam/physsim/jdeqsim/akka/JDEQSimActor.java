package beam.physsim.jdeqsim.akka;

import akka.actor.UntypedActor;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;


public class JDEQSimActor extends UntypedActor {

	private JDEQSimulation jdeqSimulation;
	private EventsManager events;
	private Network network;

	public JDEQSimActor(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Network network) {
		this.events = events;
		this.network = network;

        /*Config _config = ConfigUtils.createConfig("C:/ns/beam-integration-project/model-inputs/beamville/beam.conf");
        Scenario _s = ScenarioUtils.createScenario(_config);



        System.out.println("scenario.getNetwork() -> " + _s.getNetwork().getLinks().values());

		for(Node node : scenario.getNetwork().getNodes().values()){
		    System.out.println("Node -> " + node);
        }*/

		for(Person p : scenario.getPopulation().getPersons().values()){
			System.out.println("Person -> " + p.toString());
			Plan plan = p.getSelectedPlan();
			System.out.println("Plan -> " + plan.toString());
			System.out.println("Plan Elements -> " + plan.getPlanElements().toString());
		}

		jdeqSimulation=new JDEQSimulation(config, scenario, events, network);
	}
	
	@Override
    public void onReceive(Object msg) throws Exception {
		 if(msg instanceof String) {
			 String s=(String) msg;
			 if (s.equalsIgnoreCase("start")){
				 jdeqSimulation.run();
				 events.finishProcessing();
			 }
		 } 
    }
	
}
