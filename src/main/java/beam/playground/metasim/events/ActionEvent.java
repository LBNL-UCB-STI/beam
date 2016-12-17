package beam.playground.metasim.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.actions.Action;
import beam.replanning.ChargingStrategy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ActionEvent extends Event {

	private BeamAgent agent;
	private Action action;
	
	public static final String ATTRIBUTE_AGENT="BeamAgent";
	public static final String ATTRIBUTE_ACTION_NAME="ActionName";

	public ActionEvent(double time, BeamAgent agent, Action action) {
		super(time);
		this.agent = agent;
		this.action = action;
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_AGENT, agent.getId().toString());
		attributes.put(ATTRIBUTE_ACTION_NAME, action.getName());
		return attributes;
	}

	public Id<BeamAgent> getAgentId() {
		return agent.getId();
	}

	
}
