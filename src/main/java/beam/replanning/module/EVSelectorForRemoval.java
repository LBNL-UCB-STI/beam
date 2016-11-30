package beam.replanning.module;

import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.replanning.selectors.PlanSelector;

import beam.replanning.ChargingStrategyManager;
import beam.replanning.EVDailyPlan;
import beam.replanning.EVDailyReplanable;

public class EVSelectorForRemoval<T,I> implements PlanSelector {

	@Override
	public Plan selectPlan(HasPlansAndId person) {
		EVDailyReplanable replanable = ChargingStrategyManager.data.getReplanable(person.getId());
		
		double worstScore=Double.MAX_VALUE;
		Plan worstPlan=null;
		for(EVDailyPlan evDailyPlan: replanable.getEVDailyPlans()){
			if (evDailyPlan.getScore()<worstScore){
				worstPlan=evDailyPlan.plan;
				worstScore=evDailyPlan.getScore();
			}
		}
		
		return worstPlan;
	}
}
