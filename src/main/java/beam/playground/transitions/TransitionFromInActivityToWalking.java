package beam.playground.transitions;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.EVGlobalData;
import beam.playground.PlaygroundFun;
import beam.playground.agents.BeamAgent;
import beam.playground.states.BaseState;
import beam.playground.states.State;

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
		PlaygroundFun.scheduler.addCallBackMethod(EVGlobalData.data.now + 60.0, agent, "ChooseMode", this);
	}

}
