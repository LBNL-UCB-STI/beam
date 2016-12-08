package beam.playground;

import org.matsim.api.core.v01.Coord;

public class PersonAgent implements MobileAgent {
	State state;
	Coord location;
	
	@Override
	public State getState() {
		return state;
	}

	@Override
	public TransitionSelector getTransitionSelector(Action action) {
		return RandomTransitionSelector.getInstance();
	}

	@Override
	public void performTransition(Transition selectedTransition) {
		state = selectedTransition.getToState();
	}

	@Override
	public Coord getLocation() {
		return location;
	}

	@Override
	public Boolean hasVehicleAvailable(Class<?> vehicleType) {
		return true;
	}

}
