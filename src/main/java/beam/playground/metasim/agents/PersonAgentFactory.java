package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.playground.metasim.agents.states.State;

public interface PersonAgentFactory {

	PersonAgent create(Id<Person> personId, State state, Coord location);

}
