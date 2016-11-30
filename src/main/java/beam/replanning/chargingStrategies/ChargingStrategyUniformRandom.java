package beam.replanning.chargingStrategies;

import java.util.ArrayList;
import java.util.Random;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.replanning.ChargingStrategy;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ChargingStrategyUniformRandom extends ChargingStrategyAlwaysChargeOnArrival {
	Random rand;
	
	public ChargingStrategyUniformRandom() {
		super();
		this.rand = EVGlobalData.data.rand;
	}
	public ChargingStrategy copy() {
		ChargingStrategyUniformRandom newStrategy = new ChargingStrategyUniformRandom();
		newStrategy.setId(super.id);
		return newStrategy;
	}

	@Override
	public void makeArrivalDecisions(PlugInVehicleAgent agent) {
		ChargingChoice theChoice = new ChargingChoice(null, null, agent.getCurrentSearchRadius());
		this.arrivalChargingChoices.add(theChoice);
		ArrayList<ChargingSite> foundSites = new ArrayList<ChargingSite>();
		foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfCurrentOrNextActivity(), theChoice.searchRadius,agent)); 
		if(foundSites.size()>0){
			choosePlug(agent,foundSites,theChoice);
		}
		if(theChoice.chosenChargingPlug == null){
			chooseAdaptation(theChoice);
		}
	}
	private void choosePlug(PlugInVehicleAgent agent, ArrayList<ChargingSite> foundSites, ChargingChoice theChoice) {
		ArrayList<ChargingPlug> foundPlugs = new ArrayList<ChargingPlug>();
		for(ChargingPlugType plugType : agent.getVehicleWithBattery().getCompatiblePlugTypes()){
			foundPlugs.addAll(foundSites.get(this.rand.nextInt(foundSites.size())).getAccessibleChargingPlugsOfChargingPlugType(plugType));
		}
		if(foundPlugs.size()>0){
			theChoice.chosenChargingPlug = foundPlugs.get(this.rand.nextInt(foundPlugs.size()));
		}
	}
	public void chooseAdaptation(ChargingChoice theChoice) {
		if(theChoice.chosenAdaptation == null){
			switch (this.rand.nextInt(3)) {
			case 0:
				theChoice.chosenAdaptation = SearchAdaptationAlternative.ABORT;
			case 1:
				theChoice.chosenAdaptation = SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA;
			case 2:
				theChoice.chosenAdaptation = SearchAdaptationAlternative.TRY_CHARGING_LATER;
			}
		}
	}

}
