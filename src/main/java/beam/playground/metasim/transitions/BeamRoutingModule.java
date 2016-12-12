package beam.playground.metasim.transitions;

import com.google.inject.AbstractModule;

import beam.sim.traveltime.BeamRouter;
import beam.sim.traveltime.BeamRouterImpl;

public class BeamRoutingModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(BeamRouter.class).to(BeamRouterImpl.class);
	}

}
