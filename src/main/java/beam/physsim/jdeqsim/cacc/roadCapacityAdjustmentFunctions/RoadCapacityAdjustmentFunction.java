package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions;

import org.matsim.api.core.v01.network.Link;

public interface RoadCapacityAdjustmentFunction {
    double getCapacityWithCACCPerSecond(Link link, double fractionCACCOnRoad, double simTime);
    public boolean isCACCCategoryRoad(Link link);
    public void printStats();
}
