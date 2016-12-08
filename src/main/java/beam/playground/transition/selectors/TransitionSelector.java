package beam.playground.transition.selectors;

import java.util.Collection;
import java.util.LinkedList;

import beam.playground.transitions.Transition;

public interface TransitionSelector {

	public Transition selectTransition(LinkedList<Transition> transitions);
}
