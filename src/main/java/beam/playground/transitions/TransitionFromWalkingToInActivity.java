package beam.playground.transitions;

import beam.playground.agents.AgentWithPlans;
import beam.playground.agents.BeamAgent;
import beam.playground.states.State;

public class TransitionFromWalkingToInActivity extends BaseTransition {

	public TransitionFromWalkingToInActivity(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}

}
