package beam.playground.agents;

import java.util.HashSet;

import org.matsim.api.core.v01.Coord;

public interface MobileAgent extends BeamAgent {

	public Coord getLocation();
	public Boolean hasVehicleAvailable(Class<?> vehicleType);
}
