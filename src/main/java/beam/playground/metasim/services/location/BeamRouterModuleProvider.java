package beam.playground.metasim.services.location;

import org.matsim.core.router.RoutingModule;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.services.BeamServices;

public class BeamRouterModuleProvider implements Provider<RoutingModule> {
	BeamServices beamServices;
	
	@Inject
	public BeamRouterModuleProvider(BeamServices beamServices) {
		super();
		this.beamServices = beamServices;
	}

	@Override
	public RoutingModule get() {
		return new BeamRouter();
	}

}
