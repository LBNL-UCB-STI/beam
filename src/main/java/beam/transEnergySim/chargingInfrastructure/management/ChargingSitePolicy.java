package beam.transEnergySim.chargingInfrastructure.management;

import beam.transEnergySim.chargingInfrastructure.stationary.ChargingLevel;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public interface ChargingSitePolicy {
	double getParkingCost(double time, double duration);
	
	double getChargingCost(double time, double duration, ChargingPlugType plugType, VehicleWithBattery vehicle);
}

