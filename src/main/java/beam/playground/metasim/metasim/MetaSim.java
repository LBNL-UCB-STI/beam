package beam.playground.metasim.metasim;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.Mobsim;

import com.google.inject.Inject;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.BeamServices;

public class MetaSim implements Mobsim {

	private BeamServices beamServices;
//	private Scenario scenario;
	private Scheduler scheduler;
	private EventsManager events;

	@Inject
	public MetaSim(BeamServices services, EventsManager events, Scheduler scheduler) {
		this.beamServices = services;
//		this.scenario = scenario;
		this.events = events;
		this.scheduler = scheduler;
	}

	@Override
	public void run() {
		for(Double time = 0.0; time < Double.MAX_VALUE; time++){
			if(scheduler.getSize()==0)break;
			scheduler.doSimStep(time);
			beamServices.getMatsimServices().getEvents().afterSimStep(time);
		}
	}

}
