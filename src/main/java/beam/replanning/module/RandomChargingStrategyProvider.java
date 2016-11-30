package beam.replanning.module;


import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyImpl;

import javax.inject.Inject;
import javax.inject.Provider;

class RandomChargingStrategyProvider implements Provider<PlanStrategy> {

	@Inject
	Network network;

	@Inject
	Population population;

	@Inject
	EventsManager eventsManager;

	@Override
	public PlanStrategy get() {
		PlanStrategyImpl.Builder builder = new PlanStrategyImpl.Builder(new RandomChargingStrategySelector());
		return builder.build();
	}

}
