package beam.playground.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.actions.Action;
import beam.playground.states.State;
import beam.playground.transition.selectors.TransitionSelector;
import beam.playground.transitions.Transition;

public interface BeamAgent {
	public Id<BeamAgent> getId();
	public State getState();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
	public void setState(State toState);
}
