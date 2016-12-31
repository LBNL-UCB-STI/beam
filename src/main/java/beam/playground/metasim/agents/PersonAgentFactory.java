package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.plans.BeamPlanFactory;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.services.BeamServices;

public interface PersonAgentFactory {

	PersonAgent create(Person person, Coord location);

	public class Default implements PersonAgentFactory {
		private final Provider<BeamServices> beamServicesProvider;
		private final Provider<BeamPlanFactory> beamPlanFactoryProvider;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider, Provider<BeamPlanFactory> beamPlanFactoryProvider){
			this.beamServicesProvider = beamServicesProvider;
			this.beamPlanFactoryProvider = beamPlanFactoryProvider;
		}

		@Override
		public PersonAgent create(Person person, Coord location) {
			return new PersonAgent(person,location, beamServicesProvider.get(),beamPlanFactoryProvider.get());
		}
	}
}
