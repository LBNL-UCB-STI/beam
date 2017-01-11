package beam.transEnergySim.vehicles.energyConsumption.sangjae;

import beam.transEnergySim.vehicles.energyConsumption.AbstractInterpolatedEnergyConsumptionModel;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;

/**
 * This class is to calculate Energy consumption of EV with the input of average speed,
 *
 * Created by Sangjae Bae on 1/10/17.
 */
public class EnergyConsumptionModelSangjae extends AbstractInterpolatedEnergyConsumptionModel{

    /**
     * Constructor
     */
    public EnergyConsumptionModelSangjae(){
        initModel();
    }

    /**
     * Initialize consumption model
     */
    private void initModel() {

    }

    /**
     *
     * @return
     */
    @Override
    public double getEnergyConsumptionRateInJoulesPerMeter() {
        return 0;
    }
}
