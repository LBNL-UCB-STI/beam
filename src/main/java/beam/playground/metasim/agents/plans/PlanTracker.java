package beam.playground.metasim.agents.plans;

import java.util.LinkedList;
import java.util.Queue;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;

public class PlanTracker {
	BeamPlan beamPlan;
	Queue<Activity> activityQueue = new LinkedList<Activity>();
	Queue<Leg> legQueue = new LinkedList<Leg>();

	public PlanTracker(BeamPlan beamPlan) {
		this.beamPlan = beamPlan;
		for(PlanElement element : beamPlan.getMatsimPlan().getPlanElements()){
			if(element instanceof Activity){
				activityQueue.add((Activity)element);
			}else if(element instanceof Leg){
				legQueue.add((Leg)element);
			}
		}
	}

	public Activity getCurrentOrNextActivity() {
		return activityQueue.peek();
	}
	public Leg getCurrentOrNextLeg() {
		return legQueue.peek();
	}
	public Queue<Activity> getActivityQueue() {
		return activityQueue;
	}
	public Queue<Leg> getLegQueue() {
		return legQueue;
	}

}
