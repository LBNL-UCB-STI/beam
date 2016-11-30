package beam.transEnergySim.chargingInfrastructure.management;

import org.matsim.api.core.v01.Id;

import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public interface ChargingNetworkOperator {

	Id getChargingNetworktOperatorId();
	
	String getName();

	double estimateChargingSessionDuration(ChargingSitePolicy chargingSitePolicy, ChargingPlugType chargingPlugType, VehicleWithBattery vehicle);
	double determineEnergyDelivered(ChargingPlug plug, VehicleWithBattery vehicle, double duration);

	double getTimeToDequeueNextVehicle(ChargingPlug plug, VehicleAgent agent);
	
}
