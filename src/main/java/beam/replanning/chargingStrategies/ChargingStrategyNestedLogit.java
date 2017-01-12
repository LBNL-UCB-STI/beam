package beam.replanning.chargingStrategies;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Random;

import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.matsim.api.core.v01.Id;

import beam.EVGlobalData;
import beam.charging.infrastructure.ChargingDecisionAlternative;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.logit.NestedLogit;
import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ChargingStrategyNestedLogit implements ChargingStrategy {
	private static final Logger log = Logger.getLogger(ChargingStrategyNestedLogit.class);
	private NestedLogit arrivalModel, departureModel, arrivalSitePlugTypeAlternativeTemplate, departureSitePlugTypeAlternativeTemplate,
			arrivalYesChargeNest, departureYesChargeNest;

	ArrayList<ChargingChoice> arrivalChargingChoices = new ArrayList<>(), departureChargingChoices = new ArrayList<>();
	int arrivalChargingChoiceIndex = -1, departureChargingChoiceIndex = -1;

	private String name, linkOfChoiceLocation;
	int id;
	private LinkedHashMap<String, Double> altData;
	private LinkedHashMap<String, LinkedHashMap<String, Double>> inputData;
	private Double yesProbability, choiceExpectedMaximumUtility;

	public ChargingStrategyNestedLogit() {
	}

	public ChargingStrategy copy() {
		ChargingStrategyNestedLogit newStrategy = new ChargingStrategyNestedLogit();
		newStrategy.setId(id);
		newStrategy.setLogitModels(this.arrivalModel,this.departureModel,this.arrivalSitePlugTypeAlternativeTemplate,this.departureSitePlugTypeAlternativeTemplate,this.arrivalYesChargeNest,this.departureYesChargeNest);
		return newStrategy;
	}
	void setLogitModels(NestedLogit arrivalModel, NestedLogit departureModel, NestedLogit arrivalSitePlugTypeAlternativeTemplate, NestedLogit departureSitePlugTypeAlternativeTemplate, NestedLogit arrivalYesChargeNest, NestedLogit departureYesChargeNest){
		this.arrivalModel = arrivalModel;
		this.departureModel = departureModel;
		this.arrivalSitePlugTypeAlternativeTemplate = arrivalSitePlugTypeAlternativeTemplate;
		this.departureSitePlugTypeAlternativeTemplate = departureSitePlugTypeAlternativeTemplate;
		this.arrivalYesChargeNest = arrivalYesChargeNest;
		this.departureYesChargeNest = departureYesChargeNest;
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
		if(agent.getId().toString().equals("5401309")){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		this.arrivalChargingChoiceIndex++;
		if(!hasPreviousFeasibleArrivalChargingDecision()){
			gatherDataAndMakeArrivalDecisions(agent);
		}
		return this.arrivalChargingChoices.get(this.arrivalChargingChoiceIndex).chosenChargingPlug != null;
	}
	@Override
	public boolean hasChosenToChargeOnDeparture(PlugInVehicleAgent agent) {
		this.departureChargingChoiceIndex++;
		if(!hasPreviousFeasibleDepartureChargingDecision()){
			if(this.departureChargingChoiceIndex <= 10){
				gatherDataAndMakeDepatureDecisions(agent);
			}else{
				this.departureChargingChoices.add(new ChargingChoice(SearchAdaptationAlternative.ABORT, null, agent.getCurrentSearchRadius()));
			}
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


	/*
	 * The meat of the algorithm.
	 */
	@SuppressWarnings("unchecked")
	private void gatherDataAndMakeArrivalDecisions(PlugInVehicleAgent agent) {
		this.linkOfChoiceLocation = agent.getCurrentOrNextActivity().getLinkId().toString();
		ArrayList<NestedLogit> alternativeNests = new ArrayList<NestedLogit>();
		LinkedHashMap<String, ChargingDecisionAlternative> sitePlugAlternatives = new LinkedHashMap<String, ChargingDecisionAlternative>();
		inputData = new LinkedHashMap<String, LinkedHashMap<String, Double>>();
		ArrayList<ChargingSite> foundSites = new ArrayList<ChargingSite>();
		foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfNextActivity(),agent.getCurrentSearchRadius(),agent));

		altData = new LinkedHashMap<>();
		altData.put("remainingRange", agent.getVehicleWithBattery().getRemainingRangeInMiles());
		altData.put("remainingTravelDistanceInDay", agent.getRemainingTravelDistanceInDayInMiles());
		altData.put("nextTripTravelDistance", agent.getNextLegTravelDistanceInMiles());
		altData.put("plannedDwellTime", agent.getCurrentOrNextActivityDuration()/3600.0);
		altData.put("isHomeActivity", agent.getCurrentOrNextActivity().getType().equals("Home") ? 1.0 : 0.0);
		altData.put("isWorkActivity", agent.getCurrentOrNextActivity().getType().equals("Work") ? 1.0 : 0.0);
		altData.put("isBEV", agent.isBEV() ? 1.0 : 0.0);
		altData.put("searchRadius", agent.getCurrentSearchRadiusInMiles());
		inputData.put("continueSearchInLargerArea", altData);
		inputData.put("tryChargingLater", altData);
		inputData.put("abort", altData);
		this.arrivalYesChargeNest.removeChildren();

		if (foundSites.size() > 0) {
			ArrayList<ChargingPlug> foundPlugs = new ArrayList<ChargingPlug>();
			for (ChargingSite site : foundSites) {
				for (ChargingPlugType plugType : site.getAllAccessibleChargingPlugTypes()) {
					
					// TODO: update the following lines in phase 2 (skipping all plugs allowing charging fast charging, if soc > 0.8 
					if (ChargingInfrastructureManagerImpl.avoidPlug(agent, plugType)){
						continue;
					}
					
					ChargingDecisionAlternative alt = new ChargingDecisionAlternative(site, plugType);
					NestedLogit alterativeNest = new NestedLogit(this.arrivalSitePlugTypeAlternativeTemplate);
					alterativeNest.setName(alt.toString());
					this.arrivalYesChargeNest.addChild(alterativeNest);
					alternativeNests.add(alterativeNest);
					sitePlugAlternatives.put(alt.toString(), alt);

					altData.put("cost", site.getChargingCost(EVGlobalData.data.now, agent.getCurrentOrNextActivityDuration(), plugType, agent.getVehicleWithBattery()));
					altData.put("chargerCapacity",
							Math.min(agent.getVehicleWithBattery().getMaxChargingPowerInKW(plugType), plugType.getChargingPowerInKW()));
					altData.put("distanceToActivity", agent.getDistanceToNextActivityInMiles(site.getCoord()));
					altData.put("isHomeActivityAndHomeCharger", agent.getCurrentOrNextActivity().getType().equals("Home") & site.isResidentialCharger() ? 1.0 : 0.0);
					altData.put("isAvailable",site.getNumAvailablePlugsOfType(plugType) > 0 ? 1.0 : 0.0);
					inputData.put(alt.toString(), ((LinkedHashMap<String, Double>) altData.clone()));
				}
			}
		}
		if(altData.containsKey("isHomeActivityAndHomeCharger") && altData.get("isHomeActivityAndHomeCharger") == 1.0){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}

		this.arrivalModel.evaluateProbabilities(inputData);
		this.yesProbability = this.arrivalModel.getMarginalProbability("yesCharge");
		this.choiceExpectedMaximumUtility = this.arrivalModel.getExpectedMaximumUtility("arrival");
		String choice = this.arrivalModel.makeRandomChoice(inputData);
		ChargingDecisionAlternative chosenAlternative = sitePlugAlternatives.get(choice);
		ChargingChoice theChoice = new ChargingChoice(null, null, agent.getCurrentSearchRadius());
		this.arrivalChargingChoices.add(theChoice);
		if (chosenAlternative != null){
			for(ChargingPlug plug : chosenAlternative.site.getAccessibleChargingPlugsOfChargingPlugType(chosenAlternative.plugType)){
				if(plug.isAvailable()){
					theChoice.chosenChargingPlug = plug;
					break;
				}
			}
			if(theChoice.chosenChargingPlug == null){
				theChoice.chosenChargingPlug = chosenAlternative.site.getAccessibleChargingPlugsOfChargingPlugType(chosenAlternative.plugType).iterator().next();
			}
		}
		if (foundSites.size() > 0) {
			// Clean up the model of the nests added for the alternatives
			for (NestedLogit nest : alternativeNests) {
				this.arrivalYesChargeNest.removeChild(nest);
			}
		}
		if (chosenAlternative == null) {
			if (choice.equals("abort")) {
				theChoice.chosenAdaptation = SearchAdaptationAlternative.ABORT;
			} else if (choice.equals("continueSearchInLargerArea")) {
				theChoice.chosenAdaptation = SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA;
			} else if (choice.equals("tryChargingLater")) {
				theChoice.chosenAdaptation = SearchAdaptationAlternative.TRY_CHARGING_LATER;
			} else {
				DebugLib.stopSystemAndReportInconsistency("Unrecognized choice in ChargingStrategyNestedLogit: " + choice);
			}
		}
	}

	private void gatherDataAndMakeDepatureDecisions(PlugInVehicleAgent agent) {
		ArrayList<NestedLogit> alternativeNests = new ArrayList<NestedLogit>();
		LinkedHashMap<String, ChargingDecisionAlternative> sitePlugAlternatives = new LinkedHashMap<String, ChargingDecisionAlternative>();
		LinkedHashMap<String, LinkedHashMap<String, Double>> inputData = new LinkedHashMap<String, LinkedHashMap<String, Double>>();
		ArrayList<ChargingSite> foundSites = new ArrayList<ChargingSite>();
		foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfPreviouActivity(),agent.getCurrentSearchRadius(),agent));
		foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesAlongRoute(agent.getReachableRouteInfoAlongTrip(),agent.getCurrentSearchRadius(),agent));
		if(agent.canReachDestinationPlusSearchDistance()){
			foundSites.addAll(EVGlobalData.data.chargingInfrastructureManager.getAllAccessibleAndCompatibleChargingSitesInArea(agent.getLinkCoordOfNextActivity(),agent.getCurrentSearchRadius(),agent));
		}

		altData = new LinkedHashMap<>();
		altData.put("remainingRange", agent.getVehicleWithBattery().getRemainingRangeInMiles());
		altData.put("remainingTravelDistanceInDay", agent.getRemainingTravelDistanceInDayInMiles());
		altData.put("nextTripTravelDistance", agent.getNextLegTravelDistanceInMiles());
		altData.put("isBEV", agent.isBEV() ? 1.0 : 0.0);
		altData.put("searchRadius", agent.getCurrentSearchRadiusInMiles());
		inputData.put("noCharge", altData);

		if (foundSites.size() > 0) {
			ArrayList<ChargingPlug> foundPlugs = new ArrayList<ChargingPlug>();
			for (ChargingSite site : foundSites) {
				if(agent.getSoC() >= agent.getTripInformation(EVGlobalData.data.now, agent.getCurrentLink(), site.getNearestLink().getId()).getTripEnergyConsumption(agent.getVehicleWithBattery().getElectricDriveEnergyConsumptionModel(),agent.getVehicleWithBattery())){
					for (ChargingPlugType plugType : site.getAllAccessibleChargingPlugTypes()) {
						ChargingDecisionAlternative alt = new ChargingDecisionAlternative(site, plugType);
						NestedLogit alterativeNest = new NestedLogit(this.departureSitePlugTypeAlternativeTemplate);
						alterativeNest.setName(alt.toString());
						this.departureYesChargeNest.addChild(alterativeNest);
						alternativeNests.add(alterativeNest);
						sitePlugAlternatives.put(alt.toString(), alt);

						altData.put("cost", site.getChargingCost(EVGlobalData.data.now, agent.getCurrentOrNextActivityDuration(), plugType, agent.getVehicleWithBattery()));
						altData.put("chargerCapacity",
								Math.min(agent.getVehicleWithBattery().getMaxChargingPowerInKW(plugType), plugType.getChargingPowerInKW()));
						altData.put("distanceToActivity", agent.getDistanceToNextActivityInMiles(site.getCoord()));
						double[] extraEnergyAndTimeRequiredToUseSite = agent.getExtraEnergyAndTimeToChargeAt(site, plugType);
						altData.put("extraEnergyRequired", extraEnergyAndTimeRequiredToUseSite[0]/2.77778e-7);
						altData.put("extraTimeRequired", extraEnergyAndTimeRequiredToUseSite[1]/3600);
						inputData.put(alt.toString(), ((LinkedHashMap<String, Double>) altData.clone()));
					}
				}
			}
		}

		this.departureModel.evaluateProbabilities(inputData);
		this.yesProbability = this.departureModel.getMarginalProbability("yesCharge");
		String choice = this.departureModel.makeRandomChoice(inputData);
		ChargingDecisionAlternative chosenAlternative = sitePlugAlternatives.get(choice);
		ChargingChoice theChoice = new ChargingChoice(null, chosenAlternative == null ? null : chosenAlternative.site.getAccessibleChargingPlugsOfChargingPlugType(chosenAlternative.plugType).iterator().next(), agent.getCurrentSearchRadius());
		this.departureChargingChoices.add(theChoice);

		if (foundSites.size() > 0) {
			// Clean up the model of the nests added for the alternatives
			for (NestedLogit nest : alternativeNests) {
				this.departureYesChargeNest.removeChild(nest);
			}
		}
	}

	@Override
	public void setParameters(Element paramElement) {
		Iterator itr = (paramElement.getChildren()).iterator();
		while (itr.hasNext()) {
			Element element = (Element) itr.next();
			if (element.getAttribute("name").getValue().toLowerCase().equals("arrival")) {
				this.arrivalModel = NestedLogit.NestedLogitFactory(element);
			} else if (element.getAttribute("name").getValue().toLowerCase().equals("departure")) {
				this.departureModel = NestedLogit.NestedLogitFactory(element);
			} else {
				DebugLib.stopSystemAndReportInconsistency("Charging Strategy config file has a nestedLogit element with an unrecongized name."
						+ " Expecting one of 'arrival' or 'depature' but found: " + element.getAttribute("name").getValue());
			}
		}
		setPlugTypeAlternativeTemplate();
		log.info(this.toString());
	}

	@Override
	public void setParameters(String parameterStringAsXML) {
		SAXBuilder saxBuilder = new SAXBuilder();
		InputStream stream = new ByteArrayInputStream(parameterStringAsXML.getBytes(StandardCharsets.UTF_8));
		Document document;
		try {
			document = saxBuilder.build(stream);
			setParameters(document.getRootElement());
		} catch (JDOMException | IOException e) {
			e.printStackTrace();
		}
	}

	private void setPlugTypeAlternativeTemplate() {
		for (NestedLogit nest : this.arrivalModel.children) {
			if (nest.data.getNestName().equals("yesCharge")) {
				for (NestedLogit subNest : nest.children) {
					if (subNest.data.getNestName().equals("genericSitePlugTypeAlternative")) {
						this.arrivalSitePlugTypeAlternativeTemplate = subNest;
					}
				}
				nest.children.remove(this.arrivalSitePlugTypeAlternativeTemplate);
				this.arrivalYesChargeNest = nest;
			}
		}
		for (NestedLogit nest : this.departureModel.children) {
			if (nest.data.getNestName().equals("yesCharge")) {
				for (NestedLogit subNest : nest.children) {
					if (subNest.data.getNestName().equals("genericSitePlugTypeAlternative")) {
						this.departureSitePlugTypeAlternativeTemplate = subNest;
					}
				}
				nest.children.remove(this.departureSitePlugTypeAlternativeTemplate);
				this.departureYesChargeNest = nest;
			}
		}
	}

	@Override
	public void setId(int id) {
		this.id = id;
	}

	@Override
	public void setName(String name) {
		this.name = name;
	}

	public String chargerAttributesToColonSeparatedValues() {
		String attribs = "";
		Integer altNumber = 1;
		for(String key : inputData.keySet()){
			if(!key.equals("continueSearchInLargerArea") && !key.equals("tryChargingLater") && !key.equals("abort")){
				attribs += "Alt"+(altNumber++)+"=";
				for(String fieldName : inputData.get(key).keySet()){
					if(fieldName.equals("cost") || fieldName.equals("chargerCapacity") || fieldName.equals("isAvailable") || 
							fieldName.equals("distanceToActivity") || fieldName.equals("isHomeActivityAndHomeCharger")){
						attribs += fieldName+":"+inputData.get(key).get(fieldName)+";";
					}
				}
			}
		}
		return attribs;
	}

	public String getRemainingRange() {
		return altData.get("remainingRange").toString();
	}

	public String getRemainingTravelDistanceInDay() {
		return altData.get("remainingTravelDistanceInDay").toString();
	}

	public String getNextTripTravelDistance() {
		return altData.get("nextTripTravelDistance").toString();
	}

	public String getPlannedDwellTime() {
		return altData.containsKey("plannedDwellTime") ? altData.get("plannedDwellTime").toString() : "";
	}

	public String getIsHomeActivity() {
		return altData.containsKey("isHomeActivity") ? altData.get("isHomeActivity").toString() : "";
	}

	public String getIsBEV() {
		return altData.get("isBEV").toString();
	}


	public String getYesProbability() {
		return this.yesProbability.toString();
	}
	public String toString(){
		if(this.arrivalModel==null)return "null";
		return toStringRecursive(arrivalModel,0);
	}
	public String toStringRecursive(NestedLogit nest,int depth){
		String result = "";
		String tabs = "", tabsPlusOne = "  ";
		for(int i=0; i<depth; i++){
			tabs += "  ";
			tabsPlusOne += "  ";
		}
		result += tabs + nest.data.getNestName() + "\n";
		if((nest.children==null || nest.children.isEmpty()) && nest.data.getUtility()!=null){
			result += tabsPlusOne + nest.data.getUtility().toString() + "\n";
		}else if (nest.data.getNestName().equals("yesCharge")) {
			result += tabsPlusOne + this.arrivalSitePlugTypeAlternativeTemplate.data.getUtility().toString() + "\n";
		}else{
			for (NestedLogit subnest : nest.children) {
				result += toStringRecursive(subnest,depth+1);
			}
		}
		return result;

	}
	public String getChoiceUtility() {
		return this.choiceExpectedMaximumUtility.toString();
	}

	public String getLinkOfChoiceLocation() {
		return this.linkOfChoiceLocation;
	}

	@Override
	public ChargingPlug getChosenChargingAlternativeOnArrival(PlugInVehicleAgent agent) {
		return this.arrivalChargingChoices.get(arrivalChargingChoiceIndex).chosenChargingPlug;
	}
	@Override
	public SearchAdaptationAlternative getChosenAdaptationAlternativeOnArrival(PlugInVehicleAgent agent) {
		return this.arrivalChargingChoices.get(arrivalChargingChoiceIndex).chosenAdaptation;
	}
	@Override
	public void setSearchAdaptationDecisionOnArrival(SearchAdaptationAlternative alternative) {
		this.arrivalChargingChoices.get(arrivalChargingChoiceIndex).chosenAdaptation = alternative;
	}
	@Override
	public Double getSearchRadiusOnArrival() {
		return this.arrivalChargingChoices.get(arrivalChargingChoiceIndex).searchRadius;
	}
	@Override
	public ChargingPlug getChosenChargingAlternativeOnDeparture(PlugInVehicleAgent agent) {
		return this.departureChargingChoices.get(this.departureChargingChoiceIndex).chosenChargingPlug;
	}
	@Override
	public void setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative alternative) {
		this.departureChargingChoices.get(departureChargingChoiceIndex).chosenAdaptation = alternative;
	}
	@Override
	public SearchAdaptationAlternative getChosenAdaptationAlternativeOnDeparture(PlugInVehicleAgent agent) {
		return this.departureChargingChoices.get(departureChargingChoiceIndex).chosenAdaptation;
	}
	@Override
	public Double getSearchRadiusOnDeparture() {
		return this.departureChargingChoices.get(departureChargingChoiceIndex).searchRadius;
	}

	@Override
	public void resetInternalTracking() {
		this.arrivalChargingChoiceIndex = -1;
		this.departureChargingChoiceIndex = -1;
	}

}
