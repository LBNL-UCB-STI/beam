package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;

public class PersonAgent extends BeamAgent.Default implements MobileAgent {
	Coord location;
	Id<Person> personId;
	
	public PersonAgent(Id<Person> personId, FiniteStateMachineGraph graph, Coord location, TransitionSelector transitionSelector){
		super(Id.create(personId.toString(), BeamAgent.class),graph,transitionSelector);
		this.personId = personId;
	}

	@Override
	public Coord getLocation() {
		return location;
	}

	@Override
	public Boolean hasVehicleAvailable(Class<?> vehicleType) {
		return true;
	}

}
