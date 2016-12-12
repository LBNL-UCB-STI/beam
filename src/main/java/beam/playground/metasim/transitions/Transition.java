package beam.playground.metasim.transitions;

import java.util.HashSet;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.states.State;

public interface Transition {
	public State getFromState();
	public State getToState();
	public Boolean isContingent();
	public Boolean isAvailableTo(BeamAgent agent);
	public void performTransition(BeamAgent agent);
}
