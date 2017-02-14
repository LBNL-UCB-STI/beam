package beam.charging.spatialGroups;

import beam.transEnergySim.chargingInfrastructure.management.ChargingSiteSpatialGroup;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

/**
 * @Author mygreencar.
 */
public class ChargingSiteSpatialGroupImpl implements ChargingSiteSpatialGroup {
    String county;
    String taz;
    String zip;
    private int l1NumPluggedIn=0;
    private int l2NumPluggedIn=0;
    private int fastNumPluggedIn=0;
    private double l1ChargingLoadInKw=0.0;
    private double l2ChargingLoadInKw=0.0;
    private double fastChargingLoadInKw=0.0;

    public ChargingSiteSpatialGroupImpl(String county){
        this.county = county;
    }

    /**
     * Get the county of the spatial group
     * @return current county of the spatial group
     */
    @Override
    public String getName() {
        return this.county;
    }

    /**
     * Get the instantaneous charging power in KW
     * @param chargerType: L1 charger (residential) = 1, L2 charger = 2, fast-dc-charger = 3
     * @return current instantaneous charging load
     */
    @Override
    public double getChargingLoadInKw(int chargerType) {
        switch (chargerType){
            case 1:
                return l1ChargingLoadInKw;
            case 2:
                return l2ChargingLoadInKw;
            case 3:
                return fastChargingLoadInKw;
            default:
                return 0f;
        }
    }

    /**
     * Get the number of plugged-in vehicles of a chargerType
     * @param chargerType
     * @return
     */
    @Override
    public double getNumPluggedIn(int chargerType) {
        switch (chargerType){
            case 1:
                return l1NumPluggedIn;
            case 2:
                return l2NumPluggedIn;
            case 3:
                return fastNumPluggedIn;
            default:
                return 0f;
        }
    }

    /**
     * Add charging power in KW
     * @param chargerType: L1 charger (residential) = 1, L2 charger = 2, fast-dc-charger = 3
     * @param chargingPowerInKw: positive or negative charging power that is added to the instantaneous charging load
     */
    @Override
    public void addChargingLoadInKw(int chargerType, double chargingPowerInKw) {
        switch (chargerType){
            case 1:
                l1ChargingLoadInKw+=chargingPowerInKw;
                break;
            case 2:
                l2ChargingLoadInKw+=chargingPowerInKw;
                break;
            case 3:
                fastChargingLoadInKw+=chargingPowerInKw;
                break;
        }
    }

    /**
     * Add number of vehicles for each charger type
     * @param chargerType
     * @param num
     */
    @Override
    public void addNumPluggedIn(int chargerType, int num){
        switch (chargerType){
            case 1:
                l1NumPluggedIn+=num;
                break;
            case 2:
                l2NumPluggedIn+=num;
                break;
            case 3:
                fastNumPluggedIn+=num;
                break;
        }
    }
}
