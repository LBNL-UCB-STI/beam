package beam.playground.metasim.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.states.BaseState;
import beam.playground.metasim.states.State;

public class TransitionFromChoosingModeToInActivity extends BaseTransition {

	public TransitionFromChoosingModeToInActivity(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	public TransitionFromChoosingModeToInActivity(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		super(fromState, toState, isContingent,graph, scope);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public void performTransition(BeamAgent agent) {
	}

}
