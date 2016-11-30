package beam.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.charging.vehicle.PlugInVehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ParkWithoutChargingEvent extends Event {

	private PlugInVehicleAgent agent;
	private String location;
	
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	public static final String ATTRIBUTE_LOCATION = "location";

	public ParkWithoutChargingEvent(double time, PlugInVehicleAgent agent) {
		super(time);
		this.agent = agent;
		this.location = this.agent.getCurrentCoord().toString();
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_PERSON, this.agent.getPersonId().toString());
		attributes.put(ATTRIBUTE_LOCATION, this.location);
		return attributes;
	}
	
	public Id<Person> getPersonId() {
		return this.agent.getPersonId();
	}

}
