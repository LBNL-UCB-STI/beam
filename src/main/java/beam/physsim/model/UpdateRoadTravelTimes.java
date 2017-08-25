package beam.physsim.model;

import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.io.Serializable;

/**
 * Created by salma_000 on 8/25/2017.
 */
public class UpdateRoadTravelTimes implements Serializable {
    private TravelTimeCalculator travelTimeCalculator;
    public UpdateRoadTravelTimes(TravelTimeCalculator travelTimeCalculator){
        this.travelTimeCalculator = travelTimeCalculator;
    }

    public TravelTimeCalculator getTravelTimeCalculator() {
        return travelTimeCalculator;
    }
}
