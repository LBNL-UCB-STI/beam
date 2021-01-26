package beam.physsim;

import org.matsim.api.core.v01.network.Link;

public class AdditionalLinkTravelTimeCalculationFunctionMock implements AdditionalLinkTravelTimeCalculationFunction {
    @Override
    public double getAdditionalLinkTravelTime(Link link, double simulationTime) {
        return 0;
    }
}
