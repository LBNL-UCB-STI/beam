package beam.transEnergySim.vehicles.energyConsumption;

import beam.transEnergySim.vehicles.api.VehicleWithBattery;

import org.matsim.api.core.v01.network.Link;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;

/**
 * This class used the vehicle average fuel economy to calculate Energy consumption 
 *
 * Created by Colin Sheppard 1/30/17
 */
public class EnergyConsumptionModelConstant extends AbstractInterpolatedEnergyConsumptionModel{

    public EnergyConsumptionModelConstant(){
    }
    @Override
    public double getEnergyConsumptionRateInJoulesPerMeter(VehicleWithBattery vehicle) {
        return vehicle.getAverageElectricConsumptionRateInJoulesPerMeter();
    }

    @Override
    public double getEnergyConsumptionForLinkInJoule(Link link, VehicleWithBattery vehicle, double averageSpeed) {
        return getEnergyConsumptionForLinkInJoule(link.getLength(), vehicle, averageSpeed);
    }

    @Override
    public double getEnergyConsumptionForLinkInJoule(double distance, VehicleWithBattery vehicle, double averageSpeed) {
        return distance * vehicle.getAverageElectricConsumptionRateInJoulesPerMeter();
    }
}
