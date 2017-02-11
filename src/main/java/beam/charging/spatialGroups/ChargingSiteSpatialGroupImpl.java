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

    public ChargingSiteSpatialGroupImpl(String county){
        this.county = county;
    }
    @Override
    public String getCounty() {
        return this.county;
    }
}
