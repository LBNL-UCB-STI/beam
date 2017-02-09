package beam.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ReassessDecisionEvent extends Event implements IdentifiableDecisionEvent {

	private PlugInVehicleAgent agent;
	private String choice,plugType,site;
	private Double soc;
	private int decisionEventId;

	
	public static final String ATTRIBUTE_CHOICE=DepartureChargingDecisionEvent.ATTRIBUTE_CHOICE;
	public static final String ATTRIBUTE_SOC=DepartureChargingDecisionEvent.ATTRIBUTE_SOC;
	public static final String ATTRIBUTE_DECISION_EVENT_ID=DepartureChargingDecisionEvent.ATTRIBUTE_DECISION_EVENT_ID;
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	public static final String ATTRIBUTE_PLUG_TYPE = DepartureChargingDecisionEvent.ATTRIBUTE_PLUG_TYPE;
	public static final String ATTRIBUTE_SITE = DepartureChargingDecisionEvent.ATTRIBUTE_SITE;
	
	public ReassessDecisionEvent(double time, PlugInVehicleAgent agent, String choice) {
		super(time);
		this.agent = agent;
		this.choice = choice;
		this.soc = agent.getSoC()/agent.getBatteryCapacity();
		if(!this.choice.equals("abort")){
			this.plugType = agent.getSelectedChargingPlug().getChargingPlugType().getPlugTypeName();
			this.site = agent.getSelectedChargingPlug().getChargingSite().getId().toString();
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
		attributes.put(ATTRIBUTE_PERSON, agent.getPersonId().toString());
		attributes.put(ATTRIBUTE_CHOICE, this.choice);
		attributes.put(ATTRIBUTE_SOC, soc.toString());
		if(!this.choice.equals("abort")){
			attributes.put(ATTRIBUTE_PLUG_TYPE,plugType);
			attributes.put(ATTRIBUTE_SITE, site);
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

	public double getSoC() {
		return this.soc;
	}
	
}
