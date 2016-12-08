package beam.playground.agents;

import beam.playground.actions.Action;
import beam.playground.states.State;
import beam.playground.transition.selectors.TransitionSelector;
import beam.playground.transitions.Transition;

public interface BeamAgent {
	public State getState();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
}
