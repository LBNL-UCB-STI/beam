package beam.playground.metasim.agents.plans;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;

import com.google.inject.Inject;

import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.services.BeamServices;

public class PlanTrackerEventHandler implements ActivityStartEventHandler, ActivityEndEventHandler {
	BeamServices beamServices;
	
	@Inject
	public PlanTrackerEventHandler(BeamServices beamServices) {
		this.beamServices = beamServices;
	}

	@Override
	public void reset(int iteration) {
		
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		PersonAgent person = beamServices.getBeamAgentPopulation().getPersonAgentById(event.getPersonId());
		person.getPlanTracker().getLegQueue().poll();
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		PersonAgent person = beamServices.getBeamAgentPopulation().getPersonAgentById(event.getPersonId());
		person.getPlanTracker().getActivityQueue().poll();
	}

}
