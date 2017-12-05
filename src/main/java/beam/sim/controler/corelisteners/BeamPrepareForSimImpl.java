package beam.sim.controler.corelisteners;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.groups.GlobalConfigGroup;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.router.TripRouter;
import org.matsim.facilities.ActivityFacilities;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * BEAM
 */
public class BeamPrepareForSimImpl implements PrepareForSim {

    private static Logger log = Logger.getLogger(PrepareForSim.class);

    private final GlobalConfigGroup globalConfigGroup;
    private final Scenario scenario;
    private final Network network;
    private final Population population;
    private final ActivityFacilities activityFacilities;
    private final Provider<TripRouter> tripRouterProvider;

    @Inject
    BeamPrepareForSimImpl(GlobalConfigGroup globalConfigGroup, Scenario scenario, Network network, Population population, ActivityFacilities activityFacilities, Provider<TripRouter> tripRouterProvider) {
        this.globalConfigGroup = globalConfigGroup;
        this.scenario = scenario;
        this.network = network;
        this.population = population;
        this.activityFacilities = activityFacilities;
        this.tripRouterProvider = tripRouterProvider;
    }


    @Override
    public void run() {
        log.warn("For performance reasons, BEAM is ignoring MATSim's PerpareForSim process. Can be re-enabled in RunBeam.scala");
    }
}

