package beam.playground.metasim.agents;

import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;

import beam.playground.metasim.agents.states.State;

public class FiniteStateMachineGraph implements StartupListener {
	State initialState;

	@Override
	public void notifyStartup(StartupEvent event) {

	}

	public State getInitialState() {
		return initialState;
	}

}
