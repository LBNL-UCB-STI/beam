package beam.playground.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.agents.AgentWithPlans;
import beam.playground.agents.BeamAgent;
import beam.playground.states.BaseState;
import beam.playground.states.State;

public class TransitionFromWalkingToInActivity extends BaseTransition {

	public TransitionFromWalkingToInActivity(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}
	public TransitionFromWalkingToInActivity(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		super(fromState, toState, isContingent,graph,scope);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}

}
