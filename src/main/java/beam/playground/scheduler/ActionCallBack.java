package beam.playground.scheduler;

import java.lang.reflect.Method;

import beam.playground.PlaygroundFun;
import beam.playground.actions.Action;
import beam.playground.agents.BeamAgent;
import beam.playground.exceptions.IllegalTransitionException;
import beam.playground.transitions.Transition;

public class ActionCallBack {
	private double time,timeScheduled;
	private double priority;
	private Transition callingTransition;
	private String actionName;
	private Action targetAction;
	private BeamAgent targetAgent;
	
	public ActionCallBack(double time, double priority, BeamAgent targetAgent, String actionName, double timeScheduled, Transition callingTransition) {
		super();
		this.time = time;
		this.priority = priority;
		this.targetAgent = targetAgent;
		this.actionName = actionName;
		this.targetAction = (Action)PlaygroundFun.actions.get(actionName);
		this.timeScheduled = timeScheduled;
		this.callingTransition = callingTransition;
	}
	public void perform() throws IllegalTransitionException{
		targetAction.initiateAction(targetAgent);
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
		return this.actionName + "::" + this.targetAgent.toString() + " @"+this.time + " (" + this.priority + ")";
	}
}
