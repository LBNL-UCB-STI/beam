package beam.replanning;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Random;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

import beam.EVGlobalData;


// TODO: rename class to more meaningful name!!!
public class EVDailyReplanable {

	private Person person;
	LinkedList<EVDailyPlan> evDailyPlans = new LinkedList<>();
	
	
	public EVDailyReplanable(Person person){
		this.person=person;
	}
	
	public void removeObsoleteDailyPlans(){
		HashSet<Plan> plans=new HashSet<>();
		for (Plan plan:person.getPlans()){
			plans.add(plan);
		}
		
		
		LinkedList<EVDailyPlan> delete=new LinkedList<>();
		for (EVDailyPlan evDailyPlan:evDailyPlans){
			if (!plans.contains(evDailyPlan.plan)){
				delete.add(evDailyPlan);
			}
		}
		
		
		evDailyPlans.removeAll(delete);
		
//		if (evDailyPlans.size()==0){
//			// the case may occur theoretically, that a very good plan with several strategies attached to it
//			// TODO: but check this further, if this is really the case
//			
//			Plan randomPlan = person.getPlans().get(rand.nextInt(person.getPlans().size()));
//			
//			evDailyPlans.add(new EVDailyPlan(randomPlan, ChargingStrategyManager.createStrategySequence(randomPlan)));
//			person.setSelectedPlan(randomPlan);
//		}
		
	}
	
	public LinkedList<EVDailyPlan> getEVDailyPlans() {
		return evDailyPlans;
	}

	public void setSelectedPlan(EVDailyPlan plan) {
		if (evDailyPlans.contains(plan)){
			evDailyPlans.remove(plan);
		}
		
		evDailyPlans.addFirst(plan);
	}

	public EVDailyPlan getSelectedEvDailyPlan() {
		if (evDailyPlans.size()==0){
			System.out.println();
		}
		
		return evDailyPlans.getFirst();
	}
	
	public void trimEVDailyPlanMemoryIfNeeded(){
		if (EVGlobalData.data.MAX_NUMBER_OF_EV_DAILY_PLANS_IN_MEMORY<evDailyPlans.size()){
			double worstScore=Double.MAX_VALUE;
			EVDailyPlan worstPlan=null;
			for (EVDailyPlan eVDailyPlan:evDailyPlans){
				if (eVDailyPlan.getScore()<worstScore){
					worstScore=eVDailyPlan.getScore();
					worstPlan=eVDailyPlan;
				}
			}
			evDailyPlans.remove(worstPlan);
		}
	}

}
