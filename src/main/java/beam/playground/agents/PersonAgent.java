package beam.playground.agents;

import org.matsim.api.core.v01.Coord;

import beam.playground.actions.Action;
import beam.playground.states.State;
import beam.playground.transition.selectors.RandomTransitionSelector;
import beam.playground.transition.selectors.TransitionSelector;
import beam.playground.transitions.Transition;

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

	public void setState(State state) {
		this.state = state;
	}

}
