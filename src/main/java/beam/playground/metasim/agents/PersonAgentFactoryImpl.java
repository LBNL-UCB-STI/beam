package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.actions.BaseAction;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.services.BeamServices;

public class PersonAgentFactoryImpl implements PersonAgentFactory {
	private final Provider<BeamServices> beamServicesProvider;
	private final Provider<TransitionSelector> transitionSelectorProvider;

	@Inject
	public PersonAgentFactoryImpl(Provider<BeamServices> beamServicesProvider, Provider<TransitionSelector> transitionSelectorProvider){
		this.beamServicesProvider = beamServicesProvider;
		this.transitionSelectorProvider = transitionSelectorProvider;
	}

	@Override
	public PersonAgent create(Id<Person> personId, State state, Coord location) {
		return new PersonAgent(personId,state,location, transitionSelectorProvider.get());
	}


}
