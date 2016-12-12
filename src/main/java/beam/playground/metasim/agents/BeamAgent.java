package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.metasim.actions.Action;
import beam.playground.metasim.states.State;
import beam.playground.metasim.transition.selectors.TransitionSelector;
import beam.playground.metasim.transitions.Transition;

public interface BeamAgent {
	public Id<BeamAgent> getId();
	public State getState();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
	public void setState(State toState);
}
