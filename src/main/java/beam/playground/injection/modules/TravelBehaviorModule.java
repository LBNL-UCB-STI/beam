package beam.playground.injection.modules;

import com.google.inject.AbstractModule;

import beam.playground.agents.behavior.Behavior;
import beam.playground.agents.behavior.TravelBehaviorProvider;

public class TravelBehaviorModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(Behavior.class).toProvider(TravelBehaviorProvider.class);
	}

}
