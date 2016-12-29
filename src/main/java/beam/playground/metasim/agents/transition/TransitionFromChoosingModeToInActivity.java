package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;

public class TransitionFromChoosingModeToInActivity extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public void performTransition(BeamAgent agent) {
	}

}
