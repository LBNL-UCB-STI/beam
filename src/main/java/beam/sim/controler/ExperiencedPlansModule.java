package beam.sim.controler;


import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.scoring.ExperiencedPlansService;

import java.util.HashMap;
import java.util.Map;

public class ExperiencedPlansModule extends AbstractModule {
	@Override
	public void install() {
		install(new ExperiencedPlanElementsModule());
		bind(ExperiencedPlansService.class).toInstance(new ExperiencedPlansService() {
			@Override
			public void writeExperiencedPlans(String filename) {

			}

			@Override
			public Map<Id<Person>, Plan> getExperiencedPlans() {
				return new HashMap<>();
			}
		});
	}
}
