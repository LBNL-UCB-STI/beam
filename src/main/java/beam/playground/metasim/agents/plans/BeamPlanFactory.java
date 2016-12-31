package beam.playground.metasim.agents.plans;

import org.matsim.api.core.v01.population.Plan;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.services.BeamServices;

public interface BeamPlanFactory {

	BeamPlan create(PersonAgent personAgent, Plan plan);

	public class Default implements BeamPlanFactory {
		private final Provider<BeamServices> beamServicesProvider;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider){
			this.beamServicesProvider = beamServicesProvider;
		}

		@Override
		public BeamPlan create(PersonAgent personAgent, Plan plan) {
			return new BeamPlan.Default(personAgent, plan, beamServicesProvider.get());
		}
	}
}
