package beam.playground.metasim.agents.transition;

import java.util.LinkedList;
import java.util.List;

import org.matsim.api.core.v01.events.ActivityEndEvent;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.scheduler.ActionCallBack;

public class TransitionFromInActivityToStopped extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		PersonAgent person = (PersonAgent)agent;
		return person.getPlanTracker().getActivityQueue().size() <= 1;
	}

	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		return new LinkedList<ActionCallBack>();
	}

}
