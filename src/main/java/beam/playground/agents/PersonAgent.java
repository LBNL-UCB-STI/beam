package beam.playground.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.playground.states.State;

public class PersonAgent extends BeamAgentImpl implements MobileAgent {
	Coord location;
	Id<Person> personId;
	
	public PersonAgent(Id<Person> personId, State state, Coord location){
		super();
		this.id = Id.create(personId.toString(), BeamAgent.class);
		this.personId = personId;
		this.state = state;
		this.location = location;
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
