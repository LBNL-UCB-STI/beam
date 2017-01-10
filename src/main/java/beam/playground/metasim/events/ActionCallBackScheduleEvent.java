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
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.replanning.ChargingStrategy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ActionCallBackScheduleEvent extends Event {

	private BeamAgent targetAgent;
	private Action schedulingAction, targetAction;
	private Double targetTime, priority;
	
	public static final String ATTRIBUTE_SCHEDULING_ACTION="SchedulingAction";
	public static final String ATTRIBUTE_CALLBACK_TIME="TargetTime";
	public static final String ATTRIBUTE_CALLBACK_PRIORITY="CallbackPriority";
	public static final String ATTRIBUTE_CALLBACK_TARGET_AGENT="TargetAgent";
	public static final String ATTRIBUTE_CALLBACK_TARGET_ACTION="TargetAction";

	public ActionCallBackScheduleEvent(double time, Action callingAction, ActionCallBack callback) {
		super(time);
		this.schedulingAction = callingAction;
		this.targetTime = callback.getTime();
		this.priority = callback.getPriority();
		this.targetAgent = callback.getTargetAgent();
		this.targetAction = callback.getTargetAction();
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_SCHEDULING_ACTION, schedulingAction.getName());
		attributes.put(ATTRIBUTE_CALLBACK_PRIORITY, priority.toString());
		attributes.put(ATTRIBUTE_CALLBACK_TARGET_ACTION, targetAction.getName());
		attributes.put(ATTRIBUTE_CALLBACK_TARGET_AGENT, targetAgent.getId().toString());
		attributes.put(ATTRIBUTE_CALLBACK_TIME, targetTime.toString());
		return attributes;
	}
	
}
