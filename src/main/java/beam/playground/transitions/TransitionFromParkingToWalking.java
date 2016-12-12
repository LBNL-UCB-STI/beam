package beam.playground.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.agents.AgentWithPlans;
import beam.playground.agents.BeamAgent;
import beam.playground.states.BaseState;
import beam.playground.states.State;

public class TransitionFromParkingToWalking extends BaseTransition {

	public TransitionFromParkingToWalking(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}
	public TransitionFromParkingToWalking(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
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
