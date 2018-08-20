package beam.physsim.jdeqsim.cacc.travelTimeFunctions;

import org.matsim.api.core.v01.network.Link;

public class CACCTravelTimeFunctionA implements TravelTimeFunction {


    @Override
    public double calcTravelTime(Link link, Double shareOfCACC){

        if(shareOfCACC.equals(0d))
            return (link.getLength()) / link.getFreespeed();
        else
            return ((shareOfCACC*5)*(link.getLength()) / link.getFreespeed());

    }


}
