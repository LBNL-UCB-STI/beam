package beam.playground;

public class TransitionFromInActivityToModeChoice extends BaseTransition {

	public TransitionFromInActivityToModeChoice(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

}
