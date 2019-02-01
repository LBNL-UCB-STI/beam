package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.travelTimeFunctions.TravelTimeFunction;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.jdeqsim.*;
import org.matsim.core.mobsim.jdeqsim.util.Timer;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class JDEQSimulation implements Mobsim {

	private final static Logger log = Logger.getLogger(JDEQSimulation.class);

	private final JDEQSimConfigGroup config;
	protected Scenario scenario;
	private final EventsManager events;
	////////CHANGES/////////
	public static Map<String ,Boolean> isCACCVehicle;

	Double caccShare = null;


	protected final PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation;

	@Inject
	public JDEQSimulation(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final Map<String, Boolean> isCACCVehicle, TravelTimeFunction travelTimeFunction) {

		this.config = config;
		this.scenario = scenario;
		this.events = events;
		this.isCACCVehicle = isCACCVehicle;

		this.activityDurationInterpretation = this.scenario.getConfig().plans().getActivityDurationInterpretation();
		Message.setEventsManager(events);
		Road.setConfig(config);
		Road.setTravelTimeFunction(travelTimeFunction);
		this.caccShare = caccShare;
	}

	@Override
	public void run() {
		org.matsim.core.mobsim.jdeqsim.Road road;
		events.initProcessing();
		Timer t = new Timer();
		t.startTimer();

//		System.out.println("config.getSimulationEndTime() ->  " + config.getSimulationEndTime());
		Scheduler scheduler = new Scheduler(new MessageQueue(), 2*config.getSimulationEndTime());

		HashMap<Id<Link>, org.matsim.core.mobsim.jdeqsim.Road> allRoads = new HashMap<>();
		// initialize network

		for (Link link : this.scenario.getNetwork().getLinks().values()) {
			road = new Road(scheduler, link);
			allRoads.put(link.getId(), road);
		}

		Road.setAllRoads(allRoads);

		// TODO: remember which vehicles are cacc or provide from main program
		// use same for doing analysis on how many vehicles are on network which are caccs
		//

		List<String> vehicleNotFound = new ArrayList<>();

		for (Person person : this.scenario.getPopulation().getPersons().values()) {

			boolean isCaccEnabled = false;
			String personId = person.getId().toString();
			if(isCACCVehicle.get(personId) == null){
				vehicleNotFound.add(personId);
			}else{
				isCaccEnabled = isCACCVehicle.get(personId);
			}

			new Vehicle(scheduler, person, activityDurationInterpretation, isCaccEnabled); // the vehicle registers itself to the scheduler
		}


		scheduler.startSimulation();

		t.endTimer();

		log.info("Vehilces not found in the isCACCVehicle map -> total vehicles " + this.scenario.getPopulation().getPersons().values().size() + ", not found " + vehicleNotFound.size());
		log.info("Time needed for one iteration (only JDEQSimulation part): " + t.getMeasuredTime() + "[ms]");
		events.finishProcessing();
	}
}
