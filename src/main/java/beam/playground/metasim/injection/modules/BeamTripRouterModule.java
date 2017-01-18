package beam.playground.metasim.injection.modules;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.groups.PlansCalcRouteConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.LeastCostPathCalculatorModule;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.MainModeIdentifierImpl;
import org.matsim.core.router.NetworkRouting;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.SingleModeNetworksCache;
import org.matsim.core.router.Transit;
import org.matsim.core.router.TripRouter;
import org.matsim.pt.router.TransitRouterModule;

import com.google.inject.Key;
import com.google.inject.name.Names;

import beam.playground.metasim.services.location.BeamRouterModuleProvider;

public class BeamTripRouterModule extends AbstractModule {

	@Override
	public void install() {
		bind(TripRouter.class); // not thread-safe, not a singleton
		bind(MainModeIdentifier.class).to(MainModeIdentifierImpl.class);
		install(new LeastCostPathCalculatorModule());
		install(new TransitRouterModule());
		bind(SingleModeNetworksCache.class).asEagerSingleton();
		PlansCalcRouteConfigGroup routeConfigGroup = getConfig().plansCalcRoute();
		for (String mode : routeConfigGroup.getTeleportedModeFreespeedFactors().keySet()) {
			if (getConfig().transit().isUseTransit() && getConfig().transit().getTransitModes().contains(mode)) {
				// default config contains "pt" as teleported mode, but if we have simulated transit, this is supposed to override it
				// better solve this on the config level eventually.
				continue;
			}
//			addRoutingModuleBinding(mode).toProvider(new FreespeedFactorRouting(getConfig().plansCalcRoute().getModeRoutingParams().get(mode)));
		}
		for (String mode : routeConfigGroup.getTeleportedModeSpeeds().keySet()) {
//			addRoutingModuleBinding(mode).toProvider(new BeelineTeleportationRouting(getConfig().plansCalcRoute().getModeRoutingParams().get(mode)));
		}
		for (String mode : routeConfigGroup.getNetworkModes()) {
//			addRoutingModuleBinding(mode).toProvider(new NetworkRouting(mode));
			addRoutingModuleBinding(mode).toProvider(new BeamRouterModuleProvider(null));
		}
		if (getConfig().transit().isUseTransit()) {
			for (String mode : getConfig().transit().getTransitModes()) {
				addRoutingModuleBinding(mode).toProvider(Transit.class);
			}
			addRoutingModuleBinding(TransportMode.transit_walk).to(Key.get(RoutingModule.class, Names.named(TransportMode.walk)));
		}
	}

}
