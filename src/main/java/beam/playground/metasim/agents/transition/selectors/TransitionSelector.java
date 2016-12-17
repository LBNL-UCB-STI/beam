package beam.playground.metasim.agents.transition.selectors;

import java.util.Collection;
import java.util.LinkedList;

import beam.playground.metasim.agents.transition.Transition;

public interface TransitionSelector {

	public Transition selectTransition(LinkedList<Transition> transitions);
}
