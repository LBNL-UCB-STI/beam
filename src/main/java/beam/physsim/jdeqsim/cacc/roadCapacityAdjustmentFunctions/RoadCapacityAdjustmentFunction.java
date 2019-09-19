package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions;

import org.matsim.api.core.v01.network.Link;

public interface RoadCapacityAdjustmentFunction {
    double getCapacityWithCACCPerSecond(Link link, double fractionCACCOnRoad, double simTime);
    boolean isCACCCategoryRoad(Link link);
    void printStats();
    void reset();
}
