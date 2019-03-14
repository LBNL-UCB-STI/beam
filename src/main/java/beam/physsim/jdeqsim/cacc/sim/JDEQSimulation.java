package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.CACCSettings;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.jdeqsim.*;
import org.matsim.core.mobsim.jdeqsim.util.Timer;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class JDEQSimulation extends org.matsim.core.mobsim.jdeqsim.JDEQSimulation {

	private final static Logger log = Logger.getLogger(JDEQSimulation.class);


	private CACCSettings caccSettings;
    private double speedAdjustmentFactor;

	EventsManager _events;
	JDEQSimConfigGroup _config;

	Scheduler scheduler;

	@Inject
	public JDEQSimulation(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, CACCSettings caccSettings, double speedAdjustmentFactor) {

		super(config, scenario, events);

		this.caccSettings = caccSettings;
		Road.setRoadCapacityAdjustmentFunction (caccSettings.roadCapacityAdjustmentFunction());
        this.speedAdjustmentFactor = speedAdjustmentFactor;


        this._events = events;
		this._config = config;

		scheduler = new Scheduler(new MessageQueue(), _config.getSimulationEndTime());

		initializeRoads();
		initializeVehicles();
	}

	private void initializeVehicles() {
		List<String> vehicleNotFound = new ArrayList<>();
		Map<String, Boolean> isCACCVehicle = caccSettings.isCACCVehicle();

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

		String CACCenabledString=", CACC enabled: " + isCACCVehicle.entrySet().stream().filter(x -> x.getValue()).collect(Collectors.toMap(x -> x.getKey(), x -> x.getValue())).size();
		log.info("isCACCVehicle map -> total vehicles " + this.scenario.getPopulation().getPersons().values().size() + ", not found " + vehicleNotFound.size() + CACCenabledString);
	}

	private void initializeRoads() {
		HashMap<Id<Link>, org.matsim.core.mobsim.jdeqsim.Road> allRoads = new HashMap<>();
		// initialize network
		org.matsim.core.mobsim.jdeqsim.Road road;
		for (Link link : this.scenario.getNetwork().getLinks().values()) {
			road = new Road(scheduler, link, speedAdjustmentFactor);
			allRoads.put(link.getId(), road);
		}
		Road.setAllRoads(allRoads);
	}

	@Override
	public void run() {

		_events.initProcessing();
		Timer t = new Timer();
		t.startTimer();

		scheduler.startSimulation();

		t.endTimer();

		log.info("Time needed for one iteration (only JDEQSimulation part): " + t.getMeasuredTime() + "[ms]");
		_events.finishProcessing();
		caccSettings.roadCapacityAdjustmentFunction().printStats();
	}
}
