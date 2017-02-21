package beam.sim.traveltime;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.facilities.Facility;

import beam.EVGlobalData;

public abstract class BeamRouter implements RoutingModule {
	public abstract TripInformation getTripInformation(double time, Link startLink, Link endLink);

	public abstract LinkedList<RouteInformationElement> calcRoute(Link fromLink, Link toLink, double departureTime, Person person);

}
