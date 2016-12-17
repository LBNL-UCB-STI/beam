package beam.playground.metasim.scheduler;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;

public interface ActionCallBackFactory {

	ActionCallBackImpl create(double time, double priority, BeamAgent targetAgent, String actionName, Double now,
			Transition callingTransition);

}
