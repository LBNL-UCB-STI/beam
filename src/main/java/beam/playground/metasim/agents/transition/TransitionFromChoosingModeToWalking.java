package beam.playground.metasim.agents.transition;

import java.util.LinkedList;
import java.util.List;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.MobileAgent;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.vehicle.HumanBody;

public class TransitionFromChoosingModeToWalking extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		if(agent instanceof MobileAgent){
			((MobileAgent)agent).hasVehicleAvailable(HumanBody.class);
		}
		return true;
	}
	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		return new LinkedList<ActionCallBack>();
	}

}
