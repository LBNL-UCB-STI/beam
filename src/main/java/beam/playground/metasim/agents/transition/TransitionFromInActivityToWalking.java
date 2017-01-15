package beam.playground.metasim.agents.transition;

import java.util.List;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.EVGlobalData;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.services.BeamServices;

public class TransitionFromInActivityToWalking extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		return beamServices.getScheduler().createCallBackMethod(beamServices.getScheduler().getNow() + 60.0, agent, "ChooseMode", this.getClass());
	}

}
