package beam.playground.metasim.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.AgentWithPlans;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.states.BaseState;
import beam.playground.metasim.states.State;

public class TransitionFromWalkingToDriving extends BaseTransition {

	public TransitionFromWalkingToDriving(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}
	public TransitionFromWalkingToDriving(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		super(fromState, toState, isContingent,graph,scope);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}
	@Override
	public void performTransition(BeamAgent agent) {
		// TODO Auto-generated method stub
		
	}

}
