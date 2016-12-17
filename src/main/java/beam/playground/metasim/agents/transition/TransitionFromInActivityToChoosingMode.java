package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import com.google.inject.Inject;

import beam.EVGlobalData;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.BaseState;
import beam.playground.metasim.agents.states.State;

public class TransitionFromInActivityToChoosingMode extends BaseTransition {

	public TransitionFromInActivityToChoosingMode(State fromState, State toState, Boolean isContingent) {
		super(fromState, toState, isContingent);
	}

	public TransitionFromInActivityToChoosingMode(BaseState fromState, BaseState toState, boolean isContingent, GraphVizGraph graph, GraphVizScope scope) {
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
