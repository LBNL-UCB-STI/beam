package beam.transEnergySim.events;

import org.matsim.api.core.v01.Id;

import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.vehicles.api.Vehicle;

public interface ChargingEventHandler {

	void handleStartChargingEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId);
	void handleEndChargingEvent(double time, Id<Vehicle> vehicleId, Id<ChargingPlug> plugId);
	void handleTimeStep(double time);

}
