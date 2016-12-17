package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.AgentWithPlans;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;

public class TransitionFromWalkingToInActivity extends BaseTransition {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}
	@Override
	public void performTransition(BeamAgent agent) {
		// TODO Auto-generated method stub
		
	}

}
