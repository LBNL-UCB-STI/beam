package beam.charging.management;

import org.matsim.api.core.v01.Id;

import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.management.ChargingNetworkOperator;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public class ChargingNetworkOperatorSmartCharging implements ChargingNetworkOperator {
	Id<ChargingNetworkOperator> operatorId;
	
	public ChargingNetworkOperatorSmartCharging(String string) {
	}

	@Override
	public Id<ChargingNetworkOperator> getChargingNetworktOperatorId() {
		return operatorId;
	}

	@Override
	public String getName() {
		return null;
	}

	@Override
	public double estimateChargingSessionDuration(ChargingSitePolicy chargingSitePolicy,ChargingPlugType chargingPlugType, VehicleWithBattery vehicle) {
		return vehicle.getRequiredEnergyInJoules() / Math.min(chargingPlugType.getChargingPowerInKW(),vehicle.getMaxChargingPowerInKW(chargingPlugType)) / 1000.0;
	}
	
	@Override
	public double determineEnergyDelivered(ChargingPlug plug, VehicleWithBattery vehicle, double duration) {
		return Math.min(vehicle.getRequiredEnergyInJoules(), plug.getActualChargingPowerInWatt() * duration);
	}

	@Override
	public double getTimeToDequeueNextVehicle(ChargingPlug plug, VehicleAgent agent) {
		//TODO decide how to manage this timing
		return 10*60;
	}
}
