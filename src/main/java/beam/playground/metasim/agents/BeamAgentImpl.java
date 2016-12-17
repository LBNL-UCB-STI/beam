package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Id;

import com.google.inject.Inject;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;

public class BeamAgentImpl implements BeamAgent {
	protected Id<BeamAgent> id;
	protected State state;
	@Inject protected TransitionSelector transitionSelector;

	@Override
	public Id<BeamAgent> getId() {
		return id;
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public TransitionSelector getTransitionSelector(Action action) {
		return transitionSelector;
	}

	@Override
	public void performTransition(Transition selectedTransition) {
		state = selectedTransition.getToState();
	}

	public void setState(State state) {
		this.state = state;
	}

}
