package beam.playground.metasim.agents;

import java.util.HashSet;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Link;

public interface MobileAgent extends BeamAgent {

	public Coord getLocation();
	public Link getNearestLink();
	public Boolean hasVehicleAvailable(Class<?> vehicleType);
}
