package beam.playground.metasim.agents.transition;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.plans.AgentWithPlans;
import beam.playground.metasim.agents.states.State;

public class TransitionFromStartToInActivity extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return (agent instanceof AgentWithPlans);
	}
	@Override
	public void performTransition(BeamAgent agent) {
		beamServices.getScheduler().addCallBackMethod(beamServices.getScheduler().getNow() + 60.0, agent, "ChooseMode", this);
		
	}

}
