package beam.physsim;

import org.matsim.api.core.v01.network.Link;

public interface AdditionalLinkTravelTimeCalculationFunction {
    double getAdditionalLinkTravelTime(Link link, double simulationTime);
}
