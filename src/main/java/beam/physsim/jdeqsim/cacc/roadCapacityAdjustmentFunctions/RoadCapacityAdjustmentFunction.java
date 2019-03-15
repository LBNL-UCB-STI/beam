package beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions;

import org.matsim.api.core.v01.network.Link;

public interface RoadCapacityAdjustmentFunction {
    double getCapacityWithCACC(Link link, double fractionCACCOnRoad);
    public boolean isCACCCategoryRoad(Link link);
    public void printStats();
}
