package beam.playground;

public class TransitionFromModeChoiceToWalk extends BaseTransition {

	public TransitionFromModeChoiceToWalk(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		if(agent instanceof MobileAgent){
			((MobileAgent)agent).hasVehicleAvailable(HumanBody.class);
		}
		return true;
	}

}
