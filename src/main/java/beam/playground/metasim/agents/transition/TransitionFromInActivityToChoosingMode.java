package beam.playground.metasim.agents.transition;

import java.util.List;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.scheduler.ActionCallBack;

public class TransitionFromInActivityToChoosingMode extends Transition.Default {

	@Override
	public Boolean isAvailableTo(BeamAgent agent) {
		return true;
	}

	@Override
	public List<ActionCallBack> performTransition(BeamAgent agent) {
		return beamServices.getScheduler().createCallBackMethod(beamServices.getScheduler().getNow() + 60.0, agent, "ChooseMode", this);
	}

}
