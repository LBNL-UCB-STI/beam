package beam.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.replanning.ChargingStrategy;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class DepartureChargingDecisionEvent extends Event implements IdentifiableDecisionEvent {

	private PlugInVehicleAgent agent;
	private String searchRadius, choice, chargingSiteId, plugType,strategyId;
	private int decisionEventId;
	double soc;

	public static final String ATTRIBUTE_CHOICE="choice";
	public static final String ATTRIBUTE_SEARCH_RADIUS="searchRadius";
	public static final String ATTRIBUTE_SOC="soc";
	public static final String ATTRIBUTE_DECISION_EVENT_ID="decisionEventId";
	public static final String ATTRIBUTE_PERSON = "person";
	public static final String ATTRIBUTE_PLUG_TYPE = "plugType";
	public static final String ATTRIBUTE_SITE = "site";
	public static final String ATTRIBUTE_STRATEGY_ID = "strategyId";
	
	public DepartureChargingDecisionEvent(double time, PlugInVehicleAgent agent, ChargingStrategy strategy) {
		super(time);
		this.agent = agent;
		this.strategyId = strategy.getId().toString();
		this.searchRadius = strategy.getSearchRadiusOnDeparture().toString();
		choice = strategy.getChosenAdaptationAlternativeOnDeparture(agent) == SearchAdaptationAlternative.STRANDED ? "stranded" : agent.getSelectedChargingPlug() == null ? "depart" : "enRoute";
		searchRadius = new Double(agent.getCurrentSearchRadius()).toString();
		setSoc(agent.getSoC()/agent.getBatteryCapacity());
		if(choice.equals("enRoute")){
			setPlugType(agent.getSelectedChargingPlug().getChargingPlugType().getPlugTypeName());
			setChargingSiteId(agent.getSelectedChargingPlug().getChargingSite().getId().toString());
		}
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
		attributes.put(ATTRIBUTE_CHOICE, choice);
		attributes.put(ATTRIBUTE_SEARCH_RADIUS, searchRadius);
		attributes.put(ATTRIBUTE_SOC, Double.toString(getSoc()));
		if(choice.equals("enRoute")){
			attributes.put(ATTRIBUTE_PLUG_TYPE,getPlugType());
			attributes.put(ATTRIBUTE_SITE, getChargingSiteId());
		}
		return attributes;
	}
	
	public Id<Person> getPersonId() {
		return this.agent.getPersonId();
	}

	public int getDecisionEventId() {
		return decisionEventId;
	}

	private void setDecisionEventId(int decisionEventId) {
		this.decisionEventId = decisionEventId;
	}

	public String getChargingSiteId() {
		return chargingSiteId;
	}

	private void setChargingSiteId(String chargingSiteId) {
		this.chargingSiteId = chargingSiteId;
	}

	public String getPlugType() {
		return plugType;
	}

	private void setPlugType(String plugType) {
		this.plugType = plugType;
	}

	public double getSoc() {
		return soc;
	}

	private void setSoc(double soc) {
		this.soc = soc;
	}
	
}
