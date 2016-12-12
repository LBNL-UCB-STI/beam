package beam.playground.transitions;

import java.util.HashSet;

import beam.playground.agents.BeamAgent;
import beam.playground.states.State;

public interface Transition {
	public State getFromState();
	public State getToState();
	public Boolean isContingent();
	public Boolean isAvailableTo(BeamAgent agent);
	public void performTransition(BeamAgent agent);
}
