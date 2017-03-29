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
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.replanning.selectors.PlanSelector;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategyManager;
import beam.replanning.EVDailyPlan;
import beam.replanning.EVDailyReplanable;

/**
 * Selects one of the existing plans and charging strategy of the person based on the
 * weight = exp(beta*score).
 *
 * This class is adapted based on: org.matsim.core.replanning.selectors.ExpBetaPlanSelector
 *
 * @author rashid_waraich
 */
public class ExpBetaPlanChargingStrategySelector<T extends BasicPlan, I> implements PlanSelector<T, I>{

	protected static final double MIN_WEIGHT = Double.MIN_VALUE;
	protected final double beta;
	
	public ExpBetaPlanChargingStrategySelector( final double logitScaleFactor ) {
		this.beta = logitScaleFactor ;
	}

	public ExpBetaPlanChargingStrategySelector(PlanCalcScoreConfigGroup charyparNagelScoringConfigGroup) {
		this( charyparNagelScoringConfigGroup.getBrainExpBeta() ) ;
	}

	/**
	 * @return a random plan from the person, random but according to its weight.
	 */
	@Override
	public T selectPlan(final HasPlansAndId<T, I> person) {
		//TODO: check if any race conditions caused here...
		ChargingStrategyManager.data.getReplanable(person.getId()).removeObsoleteDailyPlans();

		// get the weights of all plans
		Map<EVDailyPlan, Double> weights = this.calcWeights(person);

		double sumWeights = 0.0;
		for (Double weight : weights.values()) {
			sumWeights += weight;
		}

		EVDailyReplanable replanable = ChargingStrategyManager.data.getReplanable(person.getId());
		LinkedList<EVDailyPlan> evDailyPlans = replanable.getEVDailyPlans();
		
		if(evDailyPlans.size() > 2){
			//DebugLib.emptyFunctionForSettingBreakPoint();
		}
		
		//System.out.println(replanable.getSelectedEvDailyPlan().getScore());
		
		// choose a random number over interval [0, sumWeights[
		double selnum = sumWeights * EVGlobalData.data.rand.nextDouble();
		for (EVDailyPlan plan : evDailyPlans) {
			selnum -= weights.get(plan);
			if (selnum <= 0.0) {
				replanable.setSelectedPlan(plan);
				return (T) plan.plan;
			}
		}

		// hmm, no plan returned... either the person has no plans, or the plan(s) have no score.
		if (person.getPlans().size() > 0) {
			return person.getPlans().get(0);
		}

		// this case should never happen, except a person has no plans at all.
		return null;
	}

	/**
	 * Calculates the weight of a single plan.
	 *
	 * @return the weight of the plan
	 */
	protected double calcPlanWeight(EVDailyPlan plan, final double maxScore) {
		// NOTE: The deduction of "maxScore" from all scores is a numerical trick.  It ensures that the values of exp(...)
		// are in some normal range, instead of close to numerical infinity.  The latter leads to numerically instable
		// results (this is not fiction; we had that some time ago). kai, aug'12

		if (plan.getScore() == null) {
			return Double.NaN;
		}
		double weight = Math.exp(this.beta * (plan.getScore() - maxScore));
		if (weight < MIN_WEIGHT) weight = MIN_WEIGHT;
		return weight;
	}

	/**
	 * Builds the weights of all plans.
	 *
	 * @return a map containing the weights of all plans
	 */
	Map<EVDailyPlan, Double> calcWeights(final HasPlansAndId<T, ?> person) {
		EVDailyReplanable replanable = ChargingStrategyManager.data.getReplanable(person.getId());
		LinkedList<EVDailyPlan> evDailyPlans = replanable.getEVDailyPlans();
		
		// - first find the max. score of all plans of this person
		double maxScore = Double.NEGATIVE_INFINITY;
		for (EVDailyPlan plan1 : evDailyPlans) {
			if ( (plan1.getScore() != null) && plan1.getScore().isNaN() ) {
				Logger.getLogger(this.getClass()).error("encountering getScore().isNaN().  This class is not well behaved in this situation.  Continuing anyway ...") ;
			}
			if ((plan1.getScore() != null) && (plan1.getScore() > maxScore)) {
				maxScore = plan1.getScore();
			}
		}

		Map<EVDailyPlan, Double> weights = new LinkedHashMap<EVDailyPlan, Double>(person.getPlans().size());

		for (EVDailyPlan plan : evDailyPlans) {
			weights.put(plan, this.calcPlanWeight(plan, maxScore));
			// see note in calcPlanWeight!
		}

		return weights;
	}

    /**
     * @return the probability that this expBetaPlanSelector will select this plan for this person.
     */
	public static <T extends BasicPlan, I> double getSelectionProbability(ExpBetaPlanChargingStrategySelector<T, I> expBetaPlanSelector, HasPlansAndId<T, ?> person, final T plan) {
		Map<EVDailyPlan, Double> weights = expBetaPlanSelector.calcWeights(person);
		double thisWeight = weights.get(plan);

		double sumWeights = 0.0;
		for (Double weight : weights.values()) {
			sumWeights += weight;
		}

		return (thisWeight / sumWeights);
	}

}
