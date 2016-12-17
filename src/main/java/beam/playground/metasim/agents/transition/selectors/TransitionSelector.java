package beam.playground.metasim.transition.selectors;

import java.util.Collection;
import java.util.LinkedList;

import beam.playground.metasim.transitions.Transition;

public interface TransitionSelector {

	public Transition selectTransition(LinkedList<Transition> transitions);
}
