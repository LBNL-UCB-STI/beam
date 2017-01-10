package beam.playground.metasim.agents.choice.models;

import java.util.LinkedList;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;

public interface ChoiceModel {

	public Transition selectTransition(BeamAgent agent, LinkedList<Transition> transitions);
	
}
