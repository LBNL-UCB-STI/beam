package beam.playground.metasim.scheduler;

import com.google.inject.assistedinject.Assisted;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;

public interface ActionCallBackFactory {

	ActionCallBack create(@Assisted("time") Double time, @Assisted("priority") Double priority, BeamAgent targetAgent, String actionName,@Assisted("timeScheduled") Double now, Transition callingTransition);
	
}
