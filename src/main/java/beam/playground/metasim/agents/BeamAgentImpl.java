package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.metasim.actions.Action;
import beam.playground.metasim.states.State;
import beam.playground.metasim.transition.selectors.RandomTransitionSelector;
import beam.playground.metasim.transition.selectors.TransitionSelector;
import beam.playground.metasim.transitions.Transition;

public class BeamAgentImpl implements BeamAgent {
	protected Id<BeamAgent> id;
	protected State state;

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
		//TODO This should be injected
		return RandomTransitionSelector.getInstance();
	}

	@Override
	public void performTransition(Transition selectedTransition) {
		state = selectedTransition.getToState();
	}

	public void setState(State state) {
		this.state = state;
	}

}
