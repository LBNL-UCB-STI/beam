package beam.playground.agents;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import beam.playground.states.State;

public class PersonAgent extends BeamAgentImpl implements MobileAgent {
	Coord location;
	
	public PersonAgent(Id<BeamAgent> id, State state, Coord location) {
		super();
		this.id = id;
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
