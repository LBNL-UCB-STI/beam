package beam.transEnergySim.chargingInfrastructure.stationary;

import java.util.Collection;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;

import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.vehicles.api.Vehicle;

public interface ChargingPoint extends Identifiable<ChargingPoint> {
	
	ChargingSite getChargingSite();
	
	Collection<ChargingPlug> getAllChargingPlugs();
	Collection<ChargingPlug> getAvailableChargingPlugsOfPlugType(ChargingPlugType plugType);
	
	// We assume that one charging plug can potentially serve multiple parking spots located adjacent to it. But only one parked car at a time can use it.
	// For example in the scenarios it could be assumed that the charger is released when charging is finished (e.g. electronic unlock - chargingPlugStatus=AVAILABLE).
	int getNumberOfAvailableParkingSpots();
	
	void registerVehicleDeparture(VehicleAgent agent);
	
	void addChargingPlug(ChargingPlug plug);
	
	void handleBeginChargeEvent(ChargingPlug plug, VehicleAgent agent);

	void createSlowChargingQueue(int numberOfAvailableParkingSpots);

	boolean isSlowChargingQueueAccessible();

	void handleEndChargingSession(ChargingPlug plug, VehicleAgent agent);

	void removeVehicleFromQueue(ChargingPlug plug, VehicleAgent vehicle);

	void registerPlugAvailable(ChargingPlug chargingPlugImpl);

	void registerPlugUnavailable(ChargingPlug chargingPlugImpl);

	void resetAll();

	int getNumInChargingQueue(ChargingPlug plug);
}
