package beam.sim.controler;

import beam.agentsim.events.handling.EventsToActivities;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.scoring.EventsToLegs;


public class ExperiencedPlanElementsModule extends AbstractModule {
	@Override
	public void install() {
		bind(EventsToActivities.class).asEagerSingleton();
		bind(EventsToLegs.class).asEagerSingleton();
	}
}
