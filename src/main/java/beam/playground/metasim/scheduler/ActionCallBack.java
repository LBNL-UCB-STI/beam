package beam.playground.metasim.scheduler;

import org.matsim.api.core.v01.Identifiable;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.exceptions.IllegalTransitionException;
import beam.playground.metasim.services.BeamServices;

public interface ActionCallBack {
	void perform() throws IllegalTransitionException;
	double getTime();
	double getPriority();
	BeamAgent getTargetAgent();
	
	public class Default implements ActionCallBack {
		private Double time,timeScheduled, priority;
		private Transition callingTransition;
		private Action action;
		private BeamAgent targetAgent;
		
		@Inject
		public Default(@Assisted("time") Double time,@Assisted("priority") Double priority,@Assisted BeamAgent targetAgent,@Assisted String actionName,@Assisted("timeScheduled") Double timeScheduled,@Assisted Transition callingTransition) {
			super();
			this.time = time;
			this.priority = priority;
			this.targetAgent = targetAgent;
			this.action = targetAgent.getGraph().getActionMap().get(actionName);
			this.timeScheduled = timeScheduled;
			this.callingTransition = callingTransition;
		}
		public void perform() throws IllegalTransitionException{
			action.initiateAction(targetAgent);
		}
		public double getTime() {
			return time;
		}
		public double getPriority() {
			return priority;
		}
		public BeamAgent getTargetAgent() {
			return targetAgent;
		}
		public String toString(){
			return this.action.getName() + "::" + this.targetAgent.toString() + " @"+this.time + " (" + this.priority + ")";
		}
	}

}
