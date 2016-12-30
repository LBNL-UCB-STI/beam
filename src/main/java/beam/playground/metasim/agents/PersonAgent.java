package beam.playground.metasim.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.services.BeamServices;

public class PersonAgent extends BeamAgent.Default implements MobileAgent {
	Coord location;
	Id<Person> personId;
	
	public PersonAgent(Id<Person> personId, Coord location, TransitionSelector transitionSelector, BeamServices beamServices){
		super(Id.create(personId.toString(), BeamAgent.class),transitionSelector, beamServices);
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
