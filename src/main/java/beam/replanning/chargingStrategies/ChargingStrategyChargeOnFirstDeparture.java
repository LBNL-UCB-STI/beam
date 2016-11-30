package beam.replanning.chargingStrategies;

import java.util.HashSet;
import java.util.LinkedHashSet;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.replanning.ChargingStrategy;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ChargingStrategyChargeOnFirstDeparture extends ChargingStrategyAlwaysChargeOnArrival {
	private static HashSet<PlugInVehicleAgent> agentsWhoHaveMadeDecision = new HashSet<>();
	
	// TODO: update constructor (this and other strategy constructions to take id, name, etc. as input for constructor).
	// e.g. introduce abstract layer? otherwise, there is lots of overlap between the subclasses.
	public ChargingStrategyChargeOnFirstDeparture() {
	}
	
	public ChargingStrategy copy() {
		ChargingStrategyChargeOnFirstDeparture newStrategy = new ChargingStrategyChargeOnFirstDeparture();
		newStrategy.setId(super.id);
		return newStrategy;
	}
	@Override
	public void makeArrivalDecisions(PlugInVehicleAgent agent) {
		ChargingChoice theChoice = new ChargingChoice(SearchAdaptationAlternative.ABORT, null, agent.getCurrentSearchRadius());
		this.arrivalChargingChoices.add(theChoice);
	}
	@Override
	public void makeDepatureDecisions(PlugInVehicleAgent agent) {
		ChargingChoice theChoice = new ChargingChoice(null, null, agent.getCurrentSearchRadius());
		super.departureChargingChoices.add(theChoice);
		if(ChargingStrategyChargeOnFirstDeparture.agentsWhoHaveMadeDecision.add(agent)){
			LinkedHashSet<ChargingSite> foundSites = new LinkedHashSet<>();
			foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfPreviouActivity(),theChoice.searchRadius,agent));
			foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfNextActivity(),theChoice.searchRadius,agent)); 
			foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesAlongRoute(agent.getTripInfoAsOfDeparture().getRouteInfoElements(),theChoice.searchRadius,agent));
			if(foundSites.size()>0){
				theChoice.chosenChargingPlug = ((ChargingSite) foundSites.toArray()[foundSites.size()-1]).getAllChargingPlugs().iterator().next();
				return;
			}
		}
		theChoice.chosenAdaptation = SearchAdaptationAlternative.ABORT;
	}
}
