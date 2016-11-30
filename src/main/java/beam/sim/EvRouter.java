package beam.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.facilities.Facility;

import beam.EVGlobalData;

public class EvRouter implements RoutingModule {

	@Override
	public List<? extends PlanElement> calcRoute(Facility<?> fromFacility, Facility<?> toFacility, double departureTime,
			Person person) {
		
		//these are just dummy values, which will be not used by the simulation
		
		List list=new ArrayList();
//		LegImpl leg = new LegImpl(EVGlobalData.PLUGIN_ELECTRIC_VEHICLES);
//		leg.setTravelTime(1);
//		list.add(leg);
//		leg.setArrivalTime(departureTime);
//		leg.setArrivalTime(leg.getDepartureTime()+leg.getTravelTime());
		LinkNetworkRouteImpl route=new LinkNetworkRouteImpl(fromFacility.getLinkId(), toFacility.getLinkId());
//		leg.setRoute(route);
		route.setDistance(1);
		return list;
	}

	@Override
	public StageActivityTypes getStageActivityTypes() {
		return EmptyStageActivityTypes.INSTANCE;
	}

}
