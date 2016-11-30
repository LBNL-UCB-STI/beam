package beam.replanning.chargingStrategies;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Random;

import org.jdom.Element;
import org.matsim.api.core.v01.Id;

import beam.EVGlobalData;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ChargingStrategyAlwaysChargeOnArrival implements ChargingStrategy {
	private String name;
	public int id;
	public ArrayList<ChargingChoice> arrivalChargingChoices = new ArrayList<>(), departureChargingChoices = new ArrayList<>();
	public int arrivalChargingChoiceIndex = -1, departureChargingChoiceIndex = -1;
	
	public ChargingStrategyAlwaysChargeOnArrival() {
	}

	public ChargingStrategy copy() {
		ChargingStrategyAlwaysChargeOnArrival newStrategy = new ChargingStrategyAlwaysChargeOnArrival();
		newStrategy.setId(id);
		return newStrategy;
	}

	@Override
	public Id<ChargingStrategy> getId() {
		return Id.create(id, ChargingStrategy.class);
	}

	@Override
	public double getScore() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getStrategyName() {
		return this.name;
	}

	@Override
	public void resetDecisions() {
		this.arrivalChargingChoices.clear();
		this.departureChargingChoices.clear();
	}

	@Override
	public boolean hasChosenToChargeOnArrival(PlugInVehicleAgent agent) {
		this.arrivalChargingChoiceIndex++;
		if(!hasPreviousFeasibleArrivalChargingDecision()){
			makeArrivalDecisions(agent);
		}
		return this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).chosenChargingPlug != null;
	}
	@Override
	public boolean hasChosenToChargeOnDeparture(PlugInVehicleAgent agent) {
		this.departureChargingChoiceIndex++;
		if(!hasPreviousFeasibleDepartureChargingDecision()){
			makeDepatureDecisions(agent);
		}
		return this.departureChargingChoices.get(this.departureChargingChoiceIndex).chosenChargingPlug != null;
	}
	
	public boolean hasPreviousFeasibleArrivalChargingDecision(){
		if(this.arrivalChargingChoices.size() > this.arrivalChargingChoiceIndex){
			ChargingChoice choice = this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex);
			if(choice.chosenChargingPlug == null || choice.chosenChargingPlug.isAccessible()){
				return true;
			}
		}
		clearCurrentAndFutureChoices(this.arrivalChargingChoices, this.arrivalChargingChoiceIndex);
		return false;
	}
	public boolean hasPreviousFeasibleDepartureChargingDecision(){
		if(this.departureChargingChoices.size() > this.departureChargingChoiceIndex){
			ChargingChoice choice = this.departureChargingChoices.get(this.departureChargingChoiceIndex);
			if(choice.chosenChargingPlug == null || choice.chosenChargingPlug.isAccessible()){
				return true;
			}
		}
		clearCurrentAndFutureChoices(this.departureChargingChoices, this.departureChargingChoiceIndex);
		return false;
	}
	private void clearCurrentAndFutureChoices(ArrayList<ChargingChoice> choiceList, int i) {
		while(choiceList.size() > i){
			choiceList.remove(i);
		}
	}

	public void makeArrivalDecisions(PlugInVehicleAgent agent) {
		ChargingChoice theChoice = new ChargingChoice(null, null, agent.getCurrentSearchRadius());
		this.arrivalChargingChoices.add(theChoice);
		ArrayList<ChargingSite> foundSites = new ArrayList<ChargingSite>();
		foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfNextActivity(), theChoice.searchRadius,agent)); 
		if(foundSites.size()==0){
			theChoice.chosenAdaptation = SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA;
			return;
		}
		
		// Select the residential site if nearby, otherwise the first L2 and then whatever is left
		Iterator<ChargingPlug> iterator = foundSites.get(0).getAllAccessibleChargingPlugs().iterator();
		boolean foundRes = false;
		for(ChargingSite site : foundSites){
			if(site.isResidentialCharger()){
				iterator = site.getAllAccessibleChargingPlugs().iterator();
				foundRes = true;
			}
		}
		if(!foundRes){
			for(ChargingSite site : foundSites){
				for(ChargingPlugType plugType : site.getAllAccessibleChargingPlugTypes()){
					if(plugType.getNominalLevel()==2){
						iterator = site.getAccessibleChargingPlugsOfChargingPlugType(plugType).iterator();
					}
				}
			}
		}
		
		ChargingPlug chargingPlug=null;
		if (ChargingInfrastructureManagerImpl.isSocAbove80Pct(agent)){
			boolean plugFound=false;
			while (iterator.hasNext()){
				chargingPlug=iterator.next();
				
				if (!ChargingInfrastructureManagerImpl.isFastCharger(chargingPlug.getChargingPlugType())){
					plugFound=true;
					break;
				}
			}
			
			if (!plugFound){
				theChoice.chosenAdaptation = SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA;
				return;
			}
		}else {
			chargingPlug=iterator.next();
		}
		theChoice.chosenChargingPlug = chargingPlug;
	}

	public void makeDepatureDecisions(PlugInVehicleAgent agent) {
		ChargingChoice theChoice = new ChargingChoice(SearchAdaptationAlternative.ABORT, null, agent.getCurrentSearchRadius());
		this.departureChargingChoices.add(theChoice);
	}


	/*
	 * The 2-step choice here first uniformly chooses among all available charging sites and then uniformly chooses among all available plugs at that site.
	 * If not available site are found, null is returned.
	 */
	@Override
	public ChargingPlug getChosenChargingAlternativeOnArrival(PlugInVehicleAgent agent) {
		return this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).chosenChargingPlug;
	}

	@Override
	public SearchAdaptationAlternative getChosenAdaptationAlternativeOnArrival(PlugInVehicleAgent agent) {
		return this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).chosenAdaptation;
	}

	@Override
	public void setParameters(String parameterStringAsXML) {
		// No parameters needed
	}

	@Override
	public void setId(int id) {
		this.id = id;
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}

	@Override
	public void setParameters(Element child) {
	}

	@Override
	public ChargingPlug getChosenChargingAlternativeOnDeparture(PlugInVehicleAgent agent) {
		return this.departureChargingChoices.get(this.departureChargingChoiceIndex).chosenChargingPlug;
	}

	@Override
	public void setSearchAdaptationDecisionOnArrival(SearchAdaptationAlternative alternative) {
		this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).chosenAdaptation = alternative;
	}

	@Override
	public Double getSearchRadiusOnArrival() {
		return this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).searchRadius;
	}

	@Override
	public void setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative alternative) {
		this.departureChargingChoices.get(this.departureChargingChoiceIndex).chosenAdaptation = alternative;
	}

	@Override
	public SearchAdaptationAlternative getChosenAdaptationAlternativeOnDeparture(PlugInVehicleAgent agent) {
		return this.departureChargingChoices.get(this.departureChargingChoiceIndex).chosenAdaptation;
	}

	@Override
	public Double getSearchRadiusOnDeparture() {
		return this.departureChargingChoices.get(this.departureChargingChoiceIndex).searchRadius;
	}

	@Override
	public void resetInternalTracking() {
		this.arrivalChargingChoiceIndex = -1;
		this.departureChargingChoiceIndex = -1;
	}

}
