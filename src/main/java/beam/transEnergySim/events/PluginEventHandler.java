package beam.transEnergySim.events;

import org.matsim.api.core.v01.Id;

import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public abstract class PluginEventHandler extends EventManager<ChargingEventHandler> {

	public abstract void handlePluginEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId);
	public abstract void handleUnplugEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId);
	public abstract void handleTimeStep(double time);
	
	public void processStartChargingEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId) {
		for (ChargingEventHandler handler : handlers) {
			handler.handleStartChargingEvent(time, vehicleId, plugId);
		}
	}

	public void processEndChargingEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId){
		for (ChargingEventHandler handler : handlers) {
			handler.handleEndChargingEvent(time, vehicleId, plugId);
		}
	}

	public void processTimeStep(double time) {
		for (ChargingEventHandler handler : handlers) {
			handler.handleTimeStep(time);
		}
	}
	
}
