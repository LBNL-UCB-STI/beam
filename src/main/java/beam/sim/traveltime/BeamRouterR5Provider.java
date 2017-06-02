package beam.sim.traveltime;

import com.google.inject.Inject;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.router.RoutingModule;
import javax.inject.Provider;

/**
 * BEAM
 */

public class BeamRouterR5Provider implements Provider<RoutingModule> {

    @Inject
    PopulationFactory populationFactory;

    public BeamRouterR5Provider(){};

    public BeamRouterR5Provider(String mode) {
                                           this.mode = mode;
                                                            }

    private String mode;

    @Override
    public RoutingModule get() {
        return new BeamRouterR5();
    }
}
