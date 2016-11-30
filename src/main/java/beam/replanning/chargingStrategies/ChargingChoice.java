package beam.replanning.chargingStrategies;

import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;

public class ChargingChoice {
	public SearchAdaptationAlternative chosenAdaptation;
	public ChargingPlug chosenChargingPlug;
	public Double searchRadius;

	public ChargingChoice(SearchAdaptationAlternative chosenAdaptation, ChargingPlug chosenChargingPlug, Double searchRadius) {
		super();
		this.chosenAdaptation = chosenAdaptation;
		this.chosenChargingPlug = chosenChargingPlug;
		this.searchRadius = searchRadius;
	}
}
