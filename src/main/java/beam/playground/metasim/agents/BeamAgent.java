package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Id;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;

public interface BeamAgent {
	public Id<BeamAgent> getId();
	public State getState();
	public FiniteStateMachineGraph getGraph();
	public TransitionSelector getTransitionSelector(Action action);
	public void performTransition(Transition selectedTransition);
	public void setState(State toState);
}
