/* *********************************************************************** *
 * project: org.matsim.*
 * ExpBetaPlanSelector.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.replanning.module;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.BasicPlan;
import org.matsim.api.core.v01.population.HasPlansAndId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.replanning.selectors.PlanSelector;

import beam.EVGlobalData;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.replanning.ChargingStrategy;
import beam.replanning.ChargingStrategyManager;
import beam.replanning.EVDailyPlan;
import beam.replanning.EVDailyReplanable;
import beam.replanning.StrategySequence;

/**
 * @author rashid_waraich
 * 
 *  TODO: this could be optimized in future to avoid the case, that one bad charging strategy suddenly causes a good plan to be removed. E.g. take
 *  average of plan and remove plan based on that ranking or other heuristic with more knowledge of application scenario.
 */
public class RandomChargingStrategySelector<T,I>  implements PlanSelector {

	@Override
	public Plan selectPlan(HasPlansAndId person) {
		// get the weights of all plans
		EVDailyReplanable replanable = ChargingStrategyManager.data.getReplanable(person.getId());
		LinkedList<EVDailyPlan> evDailyPlans = replanable.getEVDailyPlans();
		
		Plan randomPlan = (Plan)person.getPlans().get(EVGlobalData.data.rand.nextInt(person.getPlans().size()));
		StrategySequence strategySequence = ChargingStrategyManager.data.createStrategySequence(randomPlan);
		
		EVDailyPlan evDailyPlan =new EVDailyPlan(randomPlan, strategySequence);
		
		replanable.setSelectedPlan(evDailyPlan);
		
		return randomPlan;
	}

}
