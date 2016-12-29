package beam.playground.metasim.scheduler;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import org.matsim.api.core.v01.Identifiable;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import beam.EVGlobalData;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.exceptions.IllegalTransitionException;
import beam.playground.metasim.services.BeamServices;

@Singleton
public class Scheduler {
	ActionCallBackFactory callbackFactory;
	BeamServices beamServices;
	Double now = 0.0;

	@Inject
	public Scheduler(BeamServices beamServices, ActionCallBackFactory callbackFactory) {
		super();
		this.beamServices = beamServices;
		this.callbackFactory = callbackFactory;
	}

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
	public ActionCallBack.Default addCallBackMethod(double time, BeamAgent targetAgent, String actionName, Transition callingTransition){
		return addCallBackMethod(time, targetAgent, actionName, callingTransition, 0.0);
	}
	public ActionCallBack.Default addCallBackMethod(double time, BeamAgent targetAgent, String actionName, Transition callingTransition, double priority){
		ActionCallBack.Default callback = callbackFactory.create(time,priority,targetAgent,actionName,now,callingTransition);
		this.queue.add(callback);
		return callback;
	}
	
	public void doSimStep(double until) {
		while (queue.peek() != null && queue.peek().getTime() <= until) {
			ActionCallBack entry = queue.poll();
			this.now = entry.getTime();
			//TODO handle these exceptions more elegantly
			try {
				entry.perform();
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalTransitionException e) {
				e.printStackTrace();
			}
		}
	}
	public Double getNow(){
		return now;
	}
	public int getSize() {
		return queue.size();
	}
	public void removeCallback(ActionCallBack.Default callback) {
		queue.remove(callback);
	}
}
