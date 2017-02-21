package beam.sim.traveltime;

import java.io.Serializable;

/**
 * BEAM
 */
public class TripInfoAndCount implements Serializable {
    public TripInformation tripInfo;
    public Integer count;

    public TripInfoAndCount(){
    }
    public TripInfoAndCount(TripInformation tripInfo, Integer count) {
        this.tripInfo = tripInfo;
        this.count = count;
    }
}
