package beam.sim.traveltime;

import beam.EVGlobalData;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.vehicles.Vehicle;

/**
 * BEAM
 */
public class BeamLeastCostPathCalculator implements LeastCostPathCalculator {
    @Override
    public Path calcLeastCostPath(Node fromNode, Node toNode, double starttime, Person person, Vehicle vehicle) {
        return EVGlobalData.data.router.calcRoute(fromNode,toNode,starttime,person,vehicle);
    }
}
