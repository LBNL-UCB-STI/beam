package beam.physsim.model;

import java.io.Serializable;

/**
 * Created by salma_000 on 8/25/2017.
 */
public class CopyNetworkAndUpdateRoadTravelTimes implements Serializable{
    private UpdateRoadTravelTimes updateRoadTravelTimes;
    public CopyNetworkAndUpdateRoadTravelTimes(UpdateRoadTravelTimes updateRoadTravelTimes) {
        this.updateRoadTravelTimes = updateRoadTravelTimes;
    }

    public UpdateRoadTravelTimes getUpdateRoadTravelTimes() {
        return updateRoadTravelTimes;
    }
}
