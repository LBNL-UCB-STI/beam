package beam.playground.metasim.agents.actions;

import java.util.LinkedList;

import beam.playground.metasim.agents.transition.Transition;

public interface ActionFactory {
	Action create(String name, LinkedList<Transition> restrictedTransitions);
}
