package beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions;

import org.matsim.api.core.v01.network.Link;

public class EmptyRoadCapacityAdjustmentFunction implements RoadCapacityAdjustmentFunction {
    @Override
    public double getCapacityWithCACCPerSecond(Link link, double fractionCACCOnRoad, double simTime) {
        double initialCapacity = link.getCapacity();
        return initialCapacity / 3600;
    }

    @Override
    public boolean isCACCCategoryRoad(Link link) {
        return false;
    }

    @Override
    public void printStats() {
    }

    @Override
    public void reset() {
    }
}
