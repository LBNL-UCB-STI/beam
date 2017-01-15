package beam.playground.metasim.agents.transition;

import java.util.LinkedList;
import java.util.List;

import org.matsim.api.core.v01.events.ActivityEndEvent;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.scheduler.ActionCallBack;

public class TransitionFromInActivityToChoosingMode extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		PersonAgent person = (PersonAgent)agent;
		beamServices.getMatsimServices().getEvents().processEvent(new ActivityEndEvent(beamServices.getScheduler().getNow(), person.getPerson().getId(), person.getNearestLink().getId(), null, person.getCurrentOrNextActivity().getType()));
		return new LinkedList<ActionCallBack>();
	}

}
