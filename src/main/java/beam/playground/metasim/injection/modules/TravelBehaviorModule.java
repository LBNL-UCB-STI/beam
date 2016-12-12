package beam.playground.metasim.injection.modules;

import com.google.inject.AbstractModule;

import beam.playground.metasim.agents.behavior.Behavior;
import beam.playground.metasim.agents.behavior.TravelBehaviorProvider;

public class TravelBehaviorModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(Behavior.class).toProvider(TravelBehaviorProvider.class);
	}

}
