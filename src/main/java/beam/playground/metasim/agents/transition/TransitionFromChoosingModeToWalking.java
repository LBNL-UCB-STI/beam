package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.MobileAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.vehicle.HumanBody;

public class TransitionFromChoosingModeToWalking extends BaseTransition {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		if(agent instanceof MobileAgent){
			((MobileAgent)agent).hasVehicleAvailable(HumanBody.class);
		}
		return true;
	}
	@Override
	public void performTransition(BeamAgent agent) {
		// TODO Auto-generated method stub
		
	}

}
