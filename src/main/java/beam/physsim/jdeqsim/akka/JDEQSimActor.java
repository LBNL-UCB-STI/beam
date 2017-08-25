package beam.physsim.jdeqsim.akka;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;


public class JDEQSimActor extends UntypedActor {

	private JDEQSimulation jdeqSimulation;
	private EventsManager events;
	private Network network;
	private TravelTimeCalculator travelTimeCalculator;
	ActorRef beamRouterRef;

	public JDEQSimActor(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Network network, final ActorRef beamRouterRef) {
		this.events = events;
		this.network = network;
		this.beamRouterRef = beamRouterRef;

		TravelTimeCalculatorConfigGroup ttccg = new TravelTimeCalculatorConfigGroup();
		travelTimeCalculator = new TravelTimeCalculator(network, ttccg);
		events.addHandler(travelTimeCalculator);

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
			 System.out.println("Message received -> " + s);
			 if (s.equalsIgnoreCase("start")){
				 jdeqSimulation.run();
				 events.finishProcessing();
			 }else if(s.equalsIgnoreCase("eventsProcessingFinished")){
			 	/*
			 	At this point we know simulation is finished.
			 	Here we can send the info to router with the correct info.
			 	We need to get the router reference
				Need to know how we can register our actors in the registry. as registry is available in beamservices
				We need to get router reference from beamservices using the registry
				UpdateRoadNetworkTravelTimes is the message that needs to be communicated

				The routing actor will do the following on receipt of above message
				1. Make the travel times to one million for proof of concept
				2. We have to check if update is successful or not
				3. the particular actor should remain in sleep for 10 sec and then send query to router for specific info
					that tells us the travel time for a link
				4. Receives route from the router and for all the routes the time should be 1 million. for testing.

				/////
				After jdeqsim you need to get backk this object that is a handler and we can send it to the router
				Send TravelTimeCalculator to the router with same messsage
				The router goes through all the time slices for each link
				and this is the exact time that needs to be set by the router for the edges
			 	 */

			 	System.out.println(">>> Events finished processing");
				 //travelTimeCalculator.getLinkTravelTimes()
				 //for (TravelTime tt : travelTimeCalculator.)
				 /*for(Id<Link> linkId : network.getLinks().keySet()){
				 	double time = travelTimeCalculator.getLinkTravelTime(linkId, 0.0); // question about this
				 	System.out.println("TIME FOR LINK ->> " + time);
				 }*/

				 /**********/
				 // Send whole travelTimeCalculator to the router
				 // The router will basically use it and the second argument will be for what time for.

				 /*
				 Just for a specific link we can print the time for one specific link for before and after the update
				 Just make sure that some traffic was on that link for the day

				 Case 2: Just test that two different actors are using two travel time calculators
				 		We have to process large number of events from jdeqsim for which we will need multiple time calculators
				 		the data within timetravel calculator
				  */


				 //beamRouterRef.tell("UpdateRoadNetworkTravelTimes", getSelf());
				 beamRouterRef.tell(travelTimeCalculator, getSelf());
				 //beamRouterRef.tell("REPLACE_NETWORK", getSelf());
				 /*
				 Combine both messages
				  */

				 // We need to replace this with the parallel version

			 }

		 }
    }


	public static Props props(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Network network, final ActorRef beamRouterRef){
		return  Props.create(JDEQSimActor.class,config, scenario,events, network, beamRouterRef);
	}
	
}
