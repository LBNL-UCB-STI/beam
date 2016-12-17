package beam.playground.metasim.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.MobileAgent;
import beam.playground.metasim.states.BaseState;
import beam.playground.metasim.states.State;
import beam.playground.metasim.vehicle.HumanBody;

public class TransitionFromChoosingModeToWalking extends BaseTransition {

	public TransitionFromChoosingModeToWalking(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}
	public TransitionFromChoosingModeToWalking(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		super(fromState, toState, isContingent,graph,scope);
	}

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
