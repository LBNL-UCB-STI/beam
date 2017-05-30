package beam.sim.traveltime;

import beam.EVGlobalData;
import beam.utils.GeoUtils;
import beam.utils.MathUtil;
import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.*;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.*;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.AStarEuclidean;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.PreProcessEuclidean;
import org.matsim.facilities.Facility;

import java.io.File;
import java.util.*;

public class BeamRouterR5 extends BeamRouter {
	private static final Logger log = Logger.getLogger(BeamRouterR5.class);

	TransportNetwork transportNetwork;
	Network network;
	int cachMiss = 0, getCount = 0;

	public BeamRouterR5(){
	}
	//TODO this class should use dependency injection instead of hard-coded configuration
	private void configure(){
		File networkFile = new File(EVGlobalData.data.R5_FILEPATH + File.separator + "r5-transport-network.dat");
	    if(networkFile.exists()){
			try {
				this.transportNetwork = TransportNetwork.read(networkFile);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}else {
			networkFile = new File(EVGlobalData.data.R5_FILEPATH);
			try {
				this.transportNetwork = TransportNetwork.fromDirectory(networkFile);
				networkFile = new File(EVGlobalData.data.R5_FILEPATH + File.separator + "r5-transport-network.dat");
				this.transportNetwork.write(networkFile);
				this.transportNetwork = TransportNetwork.read(networkFile);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if(EVGlobalData.data.travelTimeFunction == null){
			EVGlobalData.data.travelTimeFunction = ExogenousTravelTime.LoadTravelTimeFromSerializedData(EVGlobalData.data.TRAVEL_TIME_FILEPATH);
		}

//		val mapdbFile = new File(getClass.getResource("osm.mapdb").getFile)
//		transportNetwork.readOSM(mapdbFile)

		network = EVGlobalData.data.controler.getScenario().getNetwork();
	}

	public LinkedList<RouteInformationElement> calcRoute(Link fromLink, Link toLink, double departureTime, Person person) {
		if(network == null)configure();
		Path path = null;
        PointToPointQuery query = new PointToPointQuery(transportNetwork);
        ProfileResponse response = query.getPlan(buildRequest(fromLink.getCoord(),toLink.getCoord()));

		double now = departureTime;
		LinkedList<RouteInformationElement> routeInformation = new LinkedList<>();
        ProfileOption option = response.getOptions().get(0);
        StreetSegment segment = option.access.get(0);
        Double totalDuration = Double.valueOf(segment.duration);
		Double totalDistance = Double.valueOf(segment.distance) / 1000.0; // R5 is in millimeters
        for(StreetEdgeInfo edge : segment.streetEdges){
        	double edgeDist = Double.valueOf(edge.distance)/1000.0;
//            com.vividsolutions.jts.geom.Coordinate coord = edge.geometry.getCoordinate();
//            Link nearestLink = EVGlobalData.data.linkQuadTree.getNearest(coord.x,coord.y);
            routeInformation.add(new RouteInformationElement(edge.edgeId.toString(),edgeDist * totalDuration / totalDistance, edgeDist));
		}

//		if(path==null)return routeInformation;
//		if(path.links.size()==0){
//			double linkTravelTime = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(fromLink, now, person, null);
//			routeInformation.add(new RouteInformationElement(fromLink, linkTravelTime));
//		}else{
//			for(Link link : path.links){
//			}
//		}
		return routeInformation;
	}

	public TripInformation getTripInformation(double time, Link startLink, Link endLink) {
		if(network==null)configure();

//		double roundedTime = MathUtil.roundDownToNearestInterval(time,60.0*60.0);
		double roundedTime = 0.0;
		String key = EVGlobalData.data.linkAttributes.get(startLink.getId().toString()).get("group") + "---" +
			EVGlobalData.data.linkAttributes.get(endLink.getId().toString()).get("group") + "---" +
			EVGlobalData.data.travelTimeFunction.convertTimeToBin(roundedTime);
		getCount++;
//		TripInformation resultTrip = EVGlobalData.data.newTripInformationCache.getTripInformation(key);
//		if(resultTrip==null){
			cachMiss++;
			TripInformation resultTrip = new TripInformation(roundedTime, calcRoute(startLink, endLink, roundedTime, null));
//			EVGlobalData.data.newTripInformationCache.putTripInformation(key, resultTrip);
//			if(EVGlobalData.data.newTripInformationCache.getCacheSize() % 10000 == 0){
//				EVGlobalData.data.newTripInformationCache.persistStore();
//			}
//		}
		resultTrip.departureTime = time;
		return resultTrip;
	}
	private TripInformation getTripInformation(double departureTime, Id<Link> fromLinkId, Id<Link> toLinkId) {
		if(network==null)configure();
		return getTripInformation(departureTime, network.getLinks().get(fromLinkId), network.getLinks().get(toLinkId));
	}

	public List<? extends PlanElement> calcRoute(Facility<?> fromFacility, Facility<?> toFacility, double departureTime, Person person) {
		List list=new ArrayList();
		BeamLeg leg = new BeamLeg(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES);
		list.add(leg);
		leg.setDepartureTime(departureTime);
		leg.setTravelTime(getTripInformation(departureTime, fromFacility.getLinkId(), toFacility.getLinkId()).getTripTravelTime());
		LinkNetworkRouteImpl route=new LinkNetworkRouteImpl(fromFacility.getLinkId(), toFacility.getLinkId());
		leg.setRoute(route);
		route.setDistance(1);
		return list;
	}
	public StageActivityTypes getStageActivityTypes() {
		return EmptyStageActivityTypes.INSTANCE;
	}
	public String toString(){
		return "BeamRouter: hot cache contains "+EVGlobalData.data.newTripInformationCache.getCacheSize()+" trips, current cache miss rate: "+this.cachMiss+"/"+this.getCount;
	}

	public ProfileRequest buildRequest(Coord fromCoord, Coord toCoord){
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = transportNetwork.getTimeZone();

        Coord fromPosTransformed = GeoUtils.transform(fromCoord);
        Coord toPosTransformed = GeoUtils.transform(toCoord);

        profileRequest.fromLat = fromPosTransformed.getY();
        profileRequest.fromLon = fromPosTransformed.getX();
        profileRequest.toLat = toPosTransformed.getY();
        profileRequest.toLon = toPosTransformed.getX();
        profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00");

        profileRequest.directModes = EnumSet.of(LegMode.CAR);
        return profileRequest;
    }
}

