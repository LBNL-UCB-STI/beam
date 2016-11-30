package beam.transEnergySim.chargingInfrastructure.stationary;

import org.matsim.api.core.v01.Identifiable;

import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.events.ChargingEvent;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public interface ChargingPlug extends Identifiable<ChargingPlug> {

	ChargingPoint getChargingPoint();
	
	ChargingPlugStatus getChargingPlugStatus();
	ChargingPlugType getChargingPlugType();
	
	void plugVehicle(VehicleWithBattery vehicle);
	void unplugVehicle(VehicleWithBattery vehicle);
	void registerPlugAvailable();

	VehicleWithBattery getVehicle();

	double getMaxChargingPowerInWatt();
	double getActualChargingPowerInWatt();

	double getEnergyDeliveredByTime(double time);

	ChargingSite getChargingSite();

	double estimateChargingSessionDuration();
	
	void handleBeginChargeEvent();

	void handleEndChargingSession();

	void registerPlugInaccessible();

	void registerPlugAccessible();

	void handleBeginChargingSession(VehicleAgent agent);

	void handleChargingSessionInterruption();

	boolean isAvailable();
	boolean isAccessible();

	void resetAll();
}
