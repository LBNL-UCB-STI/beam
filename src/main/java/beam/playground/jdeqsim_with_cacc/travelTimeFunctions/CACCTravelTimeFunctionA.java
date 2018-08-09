package beam.playground.jdeqsim_with_cacc.travelTimeFunctions;

import org.matsim.api.core.v01.network.Link;

public class CACCTravelTimeFunctionA implements TravelTimeFunction {


    @Override
    public double calcTravelTime(Link link, double shareOfCACC){

        return ((shareOfCACC*5)*(link.getLength()) / link.getFreespeed());

    }


}
