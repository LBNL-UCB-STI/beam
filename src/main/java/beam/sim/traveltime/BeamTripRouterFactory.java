package beam.sim.traveltime;

import beam.EVGlobalData;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.MainModeIdentifierImpl;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.TripRouter;

import javax.inject.Provider;
import java.util.HashMap;
import java.util.Map;

/**
 * BEAM
 */
public class BeamTripRouterFactory implements Provider<TripRouter> {
    @Override
    public TripRouter get() {
        TripRouter tripRouter = new TripRouter();
        tripRouter.setRoutingModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES,new BeamRouterR5());
        return tripRouter;
    }
}
