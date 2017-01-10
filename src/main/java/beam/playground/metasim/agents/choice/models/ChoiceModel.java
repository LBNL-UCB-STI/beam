package beam.playground.metasim.agents.behavior;

import java.util.LinkedList;

import beam.playground.metasim.agents.transition.Transition;

public interface ChoiceModel {

	public Transition selectTransition(LinkedList<Transition> transitions);
	
}
