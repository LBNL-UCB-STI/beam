package beam.playground.scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import org.matsim.api.core.v01.Identifiable;

import com.google.inject.Singleton;

import beam.EVGlobalData;
import beam.playground.agents.BeamAgent;
import beam.playground.exceptions.IllegalTransitionException;
import beam.playground.transitions.Transition;

@Singleton
public class Scheduler {

	private Queue<ActionCallBack> queue = new PriorityQueue<ActionCallBack>(100,new Comparator<ActionCallBack>() {
		@Override
		public int compare(ActionCallBack a, ActionCallBack b) {
			if(a.getTime() < b.getTime()){
				return -1;
			}else if(a.getTime() > b.getTime()){
				return 1;
			}else if(a.getPriority() < b.getPriority()){
				return -1;
			}else if(a.getPriority() > b.getPriority()){
				return 1;
			}else if(a.getTargetAgent() instanceof Identifiable) {
				//TODO this is problematic when id's are not of the same class
				return ((Identifiable<?>)a.getTargetAgent()).getId().toString().compareTo(((Identifiable<?>)a.getTargetAgent()).getId().toString());
			}else{	
				//TODO make sure target objects are naturally ordered for reproducibility
				return 0;
			}
		}
	});
	public ActionCallBack addCallBackMethod(double time, BeamAgent targetAgent, String actionName, Transition callingTransition){
		return addCallBackMethod(time, targetAgent, actionName, callingTransition, 0.0);
	}
	public ActionCallBack addCallBackMethod(double time, BeamAgent targetAgent, String actionName, Transition callingTransition, double priority){
		ActionCallBack callback=new ActionCallBack(time,priority,targetAgent,actionName,EVGlobalData.data.now,callingTransition);
		this.queue.add(callback);
		return callback;
	}
	
	public void doSimStep(double now) {
		while (queue.peek() != null && queue.peek().getTime() <= now) {
			ActionCallBack entry = queue.poll();
			//TODO handle these exceptions more elegantly
			try {
				entry.perform();
			} catch (IllegalTransitionException e) {
				e.printStackTrace();
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
		}
	}
	public int getSize() {
		return 0;
	}
	public void removeCallback(ActionCallBack callback) {
		queue.remove(callback);
	}
}
