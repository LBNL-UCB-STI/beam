package beam.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ArrivalChargingDecisionEvent extends Event implements IdentifiableDecisionEvent {

	private PlugInVehicleAgent agent;
	private String plugType, chargingSiteId;
	private double soc,searchRadius;
	private int decisionEventId;
	private String choice,adaptation,strategyId;
	
	public static final String ATTRIBUTE_CHOICE=DepartureChargingDecisionEvent.ATTRIBUTE_CHOICE;
	public static final String ATTRIBUTE_ADAPTATION="adaptation";
	public static final String ATTRIBUTE_SEARCH_RADIUS=DepartureChargingDecisionEvent.ATTRIBUTE_SEARCH_RADIUS;
	public static final String ATTRIBUTE_SOC=DepartureChargingDecisionEvent.ATTRIBUTE_SOC;
	public static final String ATTRIBUTE_DECISION_EVENT_ID=DepartureChargingDecisionEvent.ATTRIBUTE_DECISION_EVENT_ID;
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	public static final String ATTRIBUTE_PLUG_TYPE = DepartureChargingDecisionEvent.ATTRIBUTE_PLUG_TYPE;
	public static final String ATTRIBUTE_SITE = DepartureChargingDecisionEvent.ATTRIBUTE_SITE;
	public static final String ATTRIBUTE_STRATEGY_ID = DepartureChargingDecisionEvent.ATTRIBUTE_STRATEGY_ID;

	public ArrivalChargingDecisionEvent(double time, PlugInVehicleAgent agent, ChargingStrategy strategy) {
		super(time);
		this.agent = agent;
		this.strategyId = strategy.getId().toString();
		searchRadius = strategy.getSearchRadiusOnArrival();
		soc = agent.getSoC()/agent.getBatteryCapacity();
		if(agent.getSelectedChargingPlug() != null){
			setPlugType(agent.getSelectedChargingPlug().getChargingPlugType().getPlugTypeName());
			chargingSiteId = agent.getSelectedChargingPlug().getChargingSite().getId().toString();
			
			if (chargingSiteId.equalsIgnoreCase("0")){
				chargingSiteId="-" + agent.getPersonId().toString();
			}
		}
		
		setChoice(agent.getSelectedChargingPlug() == null ? "park" : "charge");
		this.adaptation = (strategy.getChosenAdaptationAlternativeOnArrival(agent) == null) ? "" : strategy.getChosenAdaptationAlternativeOnArrival(agent).toString();
		setDecisionEventId(agent.createNewDecisionEventId());
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_DECISION_EVENT_ID, Integer.toString(getDecisionEventId()));
		attributes.put(ATTRIBUTE_STRATEGY_ID, this.strategyId);
		attributes.put(ATTRIBUTE_PERSON, agent.getPersonId().toString());
		attributes.put(ATTRIBUTE_CHOICE, getChoice());
		attributes.put(ATTRIBUTE_ADAPTATION, this.adaptation);
		attributes.put(ATTRIBUTE_SEARCH_RADIUS, Double.toString(searchRadius));
		attributes.put(ATTRIBUTE_SOC, Double.toString(soc));
		if(agent.getSelectedChargingPlug() != null){
			attributes.put(ATTRIBUTE_PLUG_TYPE,getPlugType());
			attributes.put(ATTRIBUTE_SITE, chargingSiteId);
		}
		return attributes;
	}

	public Id<Person> getPersonId() {
		return this.agent.getPersonId();
	}
	
	public double getSoC() {
		return soc;
	}

	public int getDecisionEventId() {
		return decisionEventId;
	}

	private void setDecisionEventId(int decisionEventId) {
		this.decisionEventId = decisionEventId;
	}
	
	public String getChargingSiteId(){
		return chargingSiteId;
	}
	
	public double getSearchRadius(){
		return searchRadius;
	}

	public String getPlugType() {
		return plugType;
	}

	private void setPlugType(String plugType) {
		this.plugType = plugType;
	}

	public String getChoice() {
		return choice;
	}

	private void setChoice(String choice) {
		this.choice = choice;
	}
	
}
