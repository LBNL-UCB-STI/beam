package beam.events;

import java.util.Map;

import beam.transEnergySim.chargingInfrastructure.management.ChargingSiteSpatialGroup;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.charging.vehicle.PlugInVehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class UnplugEvent extends Event implements IdentifiableDecisionEvent {

	private PlugInVehicleAgent agent;
	private int decisionEventId;
	private ChargingPlug plug;
	private Double soc;

	public static final String ATTRIBUTE_SOC=DepartureChargingDecisionEvent.ATTRIBUTE_SOC;
	public static final String ATTRIBUTE_DECISION_EVENT_ID=DepartureChargingDecisionEvent.ATTRIBUTE_DECISION_EVENT_ID;
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	public static final String ATTRIBUTE_PLUG = PreChargeEvent.ATTRIBUTE_PLUG;
	
	public UnplugEvent(double time, PlugInVehicleAgent agent, ChargingPlug plug) {
		super(time);
		this.agent = agent;
		this.plug = plug;
		this.soc = agent.getSoCAsFraction();
		this.setDecisionEventId(agent.getCurrentDecisionEventId());
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_PERSON, agent.getPersonId().toString());
		attributes.put(ATTRIBUTE_PLUG, plug.getId().toString());
		attributes.put(ATTRIBUTE_SOC, soc.toString());
		attributes.put(ATTRIBUTE_DECISION_EVENT_ID, Integer.toString(getDecisionEventId()));
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

	public ChargingSiteSpatialGroup getChargingSiteSpatialGroup(){
		return this.plug.getChargingSite().getChargingSiteSpatialGroup();
	}

	public int getNominalChargingLevel(){
		return this.plug.getChargingPlugType().getNominalLevel();
	}

	public String getSpatialGroup(){
		return this.plug.getChargingSite().getChargingSiteSpatialGroup().getName();
	}

	public String getSiteType(){
		return this.plug.getChargingSite().getSiteType();
	}

	public String getPlugType(){
		return this.plug.getChargingPlugType().getPlugTypeName();
	}
}
