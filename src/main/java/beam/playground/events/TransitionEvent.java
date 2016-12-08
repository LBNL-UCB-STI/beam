package beam.playground.events;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.playground.actions.Action;
import beam.playground.agents.BeamAgent;
import beam.playground.transitions.Transition;
import beam.replanning.ChargingStrategy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class TransitionEvent extends Event {

	private BeamAgent agent;
	private Transition transition;
	
	public static final String ATTRIBUTE_AGENT="BeamAgent";
	public static final String ATTRIBUTE_TRANSITION_NAME="TransitionName";

	public TransitionEvent(double time, BeamAgent agent, Transition transition) {
		super(time);
		this.agent = agent;
		this.transition = transition;
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_AGENT, agent.getId().toString());
		attributes.put(ATTRIBUTE_TRANSITION_NAME, transition.getClass().getSimpleName());
		return attributes;
	}

	public Id<BeamAgent> getAgentId() {
		return agent.getId();
	}

	
}
