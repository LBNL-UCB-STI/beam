package beam.playground;

public class TransitionFromWalkToInActivity extends BaseTransition {

	public TransitionFromWalkToInActivity(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}

}
