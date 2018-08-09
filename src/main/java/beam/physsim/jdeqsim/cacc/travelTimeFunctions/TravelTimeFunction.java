package beam.physsim.jdeqsim.cacc.travelTimeFunctions;

import org.matsim.api.core.v01.network.Link;

public interface TravelTimeFunction {

    public double calcTravelTime(Link link, double shareOfCACC);


}
