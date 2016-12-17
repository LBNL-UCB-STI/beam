package beam.playground.metasim.agents.states;

import java.util.Collection;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.Transition;

public interface State {
	public String getName();
	public void addTransition(Transition transition);
	public Collection<Transition> getAllTranstions();
	public Collection<Transition> getContingentTranstions();
	public Collection<Transition> getNonContingentTranstions();
	public void addAction(Action action);
	public Collection<Action> getAllActions();
}
