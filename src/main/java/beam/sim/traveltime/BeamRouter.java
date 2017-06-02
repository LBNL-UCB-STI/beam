package beam.sim.traveltime;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.facilities.Facility;

import beam.EVGlobalData;
import org.matsim.vehicles.Vehicle;

public abstract class BeamRouter implements RoutingModule {
	public abstract TripInformation getTripInformation(double time, Link startLink, Link endLink);

	public abstract LinkedList<RouteInformationElement> calcRoute(Link fromLink, Link toLink, double departureTime, Person person);

	public abstract LeastCostPathCalculator.Path calcRoute(Node fromNode, Node toNode, double starttime, Person person, Vehicle vehicle);
}
