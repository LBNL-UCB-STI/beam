package beam.playground;

public abstract class BaseTransition implements Transition {
	State fromState, toState;
	Boolean isContingent;
	
	//@Inject
	public BaseTransition(State fromState, State toState, Boolean isContingent) {
		super();
		this.fromState = fromState;
		this.toState = toState;
		this.isContingent = isContingent;
	}
	

	@Override
	public State getFromState() {
		return fromState;
	}

	@Override
	public State getToState() {
		return toState;
	}

	@Override
	public Boolean isContingent() {
		return isContingent;
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return null;
	}

}
