package beam.playground.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.actions.Action;
import beam.playground.states.State;
import beam.playground.transition.selectors.RandomTransitionSelector;
import beam.playground.transition.selectors.TransitionSelector;
import beam.playground.transitions.Transition;

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
