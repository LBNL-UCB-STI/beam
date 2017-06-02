package beam.sim.traveltime;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.Dijkstra;
import org.matsim.core.router.util.*;

import javax.inject.Inject;

/**
 * BEAM
 */
public class BeamRouterR5Factory implements LeastCostPathCalculatorFactory {

    @Inject
    public BeamRouterR5Factory() {
    }

    @Override
    public LeastCostPathCalculator createPathCalculator(final Network network, final TravelDisutility travelCosts, final TravelTime travelTimes) {
        return new BeamLeastCostPathCalculator();
    }

}
