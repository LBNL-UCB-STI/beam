package beam.playground.transitions;

import beam.playground.agents.BeamAgent;
import beam.playground.states.State;

public class TransitionFromInActivityToChoosingMode extends BaseTransition {

	public TransitionFromInActivityToChoosingMode(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

}
