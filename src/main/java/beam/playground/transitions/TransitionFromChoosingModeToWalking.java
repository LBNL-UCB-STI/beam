package beam.playground.transitions;

import beam.playground.agents.BeamAgent;
import beam.playground.agents.MobileAgent;
import beam.playground.states.State;
import beam.playground.vehicle.HumanBody;

public class TransitionFromChoosingModeToWalking extends BaseTransition {

	public TransitionFromChoosingModeToWalking(State fromState, State toState, Boolean isContingent) {
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
