package beam.playground.metasim.agents.transition;

import java.util.LinkedList;
import java.util.List;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.plans.AgentWithPlans;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.scheduler.ActionCallBack;

public class TransitionFromWalkingToDriving extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}
	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		return new LinkedList<ActionCallBack>();
	}

}
