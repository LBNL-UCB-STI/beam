package beam.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class NestedLogitDecisionEvent extends Event implements IdentifiableDecisionEvent {

	private Integer decisionEventId;
	private String chargerAttributes,remainingRange,remainingTravelDistanceInDay,nextTripTravelDistance,plannedDwellTime,
		isHomeActivity,isBEV,searchRadius,yesProbability,choiceUtility,link;
	
	public static final String ATTRIBUTE_DECISION_EVENT_ID=DepartureChargingDecisionEvent.ATTRIBUTE_DECISION_EVENT_ID;
	public static final String ATTRIBUTE_CHARGER_ATTRIBUTES="chargerAttributes";
	public static final String ATTRIBUTE_REMAINING_RANGE="remainingRange";
	public static final String ATTRIBUTE_REMAINING_DISTANCE_IN_DAY="remainingTravelDistanceInDay";
	public static final String ATTRIBUTE_NEXT_TRIP_DISTANCE="nextTripTravelDistance";
	public static final String ATTRIBUTE_PLANNED_DWELL_TIME="plannedDwellTime";
	public static final String ATTRIBUTE_IS_HOME="isHomeActivity";
	public static final String ATTRIBUTE_IS_BEV="isBEV";
	public static final String ATTRIBUTE_SEARCH_RADIUS=DepartureChargingDecisionEvent.ATTRIBUTE_SEARCH_RADIUS;
	public static final String ATTRIBUTE_YES_PROB="yesProbability";
	public static final String ATTRIBUTE_CHOICE_UTILITY="choiceUtility";
	public static final String ATTRIBUTE_LINK="link";

	public NestedLogitDecisionEvent(double time, Integer decisionEventId, ChargingStrategyNestedLogit logitStrategy) {
		super(time);
		this.decisionEventId = decisionEventId;
		this.chargerAttributes = logitStrategy.chargerAttributesToColonSeparatedValues();
		this.remainingRange = logitStrategy.getRemainingRange();
		this.remainingTravelDistanceInDay = logitStrategy.getRemainingTravelDistanceInDay();
		this.nextTripTravelDistance = logitStrategy.getNextTripTravelDistance();
		this.plannedDwellTime = logitStrategy.getPlannedDwellTime();
		this.isHomeActivity = logitStrategy.getIsHomeActivity();
		this.isBEV = logitStrategy.getIsBEV();
		this.searchRadius = logitStrategy.getSearchRadiusOnArrival().toString();
		this.yesProbability = logitStrategy.getYesProbability();
		this.choiceUtility = logitStrategy.getChoiceUtility();
		this.link = logitStrategy.getLinkOfChoiceLocation();
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_DECISION_EVENT_ID, this.decisionEventId.toString());
		attributes.put(ATTRIBUTE_CHARGER_ATTRIBUTES, this.chargerAttributes);
		attributes.put(ATTRIBUTE_REMAINING_RANGE, this.remainingRange);
		attributes.put(ATTRIBUTE_REMAINING_DISTANCE_IN_DAY, this.remainingTravelDistanceInDay);
		attributes.put(ATTRIBUTE_SEARCH_RADIUS, this.searchRadius);
		attributes.put(ATTRIBUTE_NEXT_TRIP_DISTANCE, this.nextTripTravelDistance);
		attributes.put(ATTRIBUTE_PLANNED_DWELL_TIME, this.plannedDwellTime);
		attributes.put(ATTRIBUTE_IS_HOME, this.isHomeActivity);
		attributes.put(ATTRIBUTE_IS_BEV, this.isBEV);
		attributes.put(ATTRIBUTE_SEARCH_RADIUS, this.searchRadius);
		attributes.put(ATTRIBUTE_YES_PROB, this.yesProbability);
		attributes.put(ATTRIBUTE_CHOICE_UTILITY, this.choiceUtility);
		attributes.put(ATTRIBUTE_LINK, this.link);
		return attributes;
	}

	@Override
	public int getDecisionEventId() {
		return this.decisionEventId;
	}

	
}
