package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.EVGlobalData;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public class TransitionFromInActivityToWalking extends BaseTransition {

	public TransitionFromInActivityToWalking(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	public TransitionFromInActivityToWalking(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
		super(fromState, toState, isContingent,graph, scope);
	}

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public void performTransition(BeamAgent agent) {
		beamServices.getScheduler().addCallBackMethod(EVGlobalData.data.now + 60.0, agent, "ChooseMode", this);
	}

}
