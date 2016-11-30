package beam.replanning;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;

// TODO: the result of both plans should be put on person plan, which is optimized
public class EVDailyPlan {

	public Plan plan; // => contains selected plan
	private StrategySequence chargingStrategiesForTheDay;
	boolean containsPEVLeg = false;
	double matsimPlanScore = Double.MAX_VALUE;

	public double getMatsimPlanScore() {
		return matsimPlanScore;
	}

	public void setMatsimPlanScore(double matsimPlanScore) {
		this.matsimPlanScore = matsimPlanScore;
	}

	public EVDailyPlan(Plan plan, StrategySequence chargingStrategiesForTheDay) {
		super();
		this.plan = plan;
		this.setChargingStrategiesForTheDay(chargingStrategiesForTheDay);

		for (PlanElement pe : plan.getPlanElements()) {
			if (pe instanceof Leg) {
				Leg leg = (Leg) pe;
				if (leg.getMode().equalsIgnoreCase(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES)) {
					containsPEVLeg = true;
					break;
				}
			}
		}
	}

	public boolean constainsPluginElectricVehicleLeg() {
		return containsPEVLeg;
	}

	public Double getScore() {
		if (constainsPluginElectricVehicleLeg()) {
			return matsimPlanScore + EVGlobalData.data.CHARGING_SCORE_SCALING_FACTOR * getChargingStrategiesForTheDay().getScore();
		} else {
			return matsimPlanScore;
		}
	}

	public ChargingStrategy getChargingStrategyForLeg(Integer currentLegIndex) {
		return this.getChargingStrategiesForTheDay().getStrategy(currentLegIndex);
	}

	public StrategySequence getChargingStrategiesForTheDay() {
		return chargingStrategiesForTheDay;
	}

	private void setChargingStrategiesForTheDay(StrategySequence chargingStrategiesForTheDay) {
		this.chargingStrategiesForTheDay = chargingStrategiesForTheDay;
	}

}
