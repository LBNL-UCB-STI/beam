package beam.sim.traveltime;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;
import beam.utils.CSVUtil;
import beam.utils.GeoUtils;
import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.*;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.facilities.Facility;
import org.matsim.vehicles.Vehicle;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class BeamRouterR5 extends BeamRouter {
	private static final Logger log = Logger.getLogger(BeamRouterR5.class);

	private static Network network;
	int cachMiss = 0, getCount = 0;
	private static QuadTree<String> edgeQuadTree;
	LinkedHashMap<Id<Link>,Id<Link>> matsimLinksToR5Links = EVGlobalData.data.matsimLinksToR5Links;
	private EdgeStore.Edge cursor;

	public BeamRouterR5(){
	    if(network==null){
			configure();
		}
	}

	//TODO this class should use dependency injection instead of hard-coded configuration
	private void configure(){
	    if(EVGlobalData.data.networkR5 == null) {
			File networkFile = new File(EVGlobalData.data.NETWORK_FILEPATH + File.separator + "r5-transport-network.dat");
			TransportNetwork transportNetwork = null;
			if (networkFile.exists()) {
				log.info("Found R5 Trasnport Network file, loading....");
				try {
					transportNetwork = TransportNetwork.read(networkFile);
				} catch (Exception e) {
					e.printStackTrace();
				}
			} else {
				log.info("Loading R5 network data from directory: "+EVGlobalData.data.NETWORK_FILEPATH);
				networkFile = new File(EVGlobalData.data.NETWORK_FILEPATH);
				try {
					transportNetwork = TransportNetwork.fromDirectory(networkFile);
					networkFile = new File(EVGlobalData.data.NETWORK_FILEPATH + File.separator + "r5-transport-network.dat");
					transportNetwork.write(networkFile);
					transportNetwork = TransportNetwork.read(networkFile);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			EVGlobalData.data.networkR5 = transportNetwork;
			indexEdges();
//			updateMatsimNetwork();
		}

		if(EVGlobalData.data.travelTimeFunction == null){
			EVGlobalData.data.travelTimeFunction = ExogenousTravelTime.LoadTravelTimeFromSerializedData(EVGlobalData.data.TRAVEL_TIME_FILEPATH);
		}
		if(EVGlobalData.data.newTripInformationCache == null) {
			EVGlobalData.data.newTripInformationCache = new TripInfoCacheMapDB(EVGlobalData.data.ROUTER_CACHE_READ_FILEPATH);
		}


//		val mapdbFile = new File(getClass.getResource("osm.mapdb").getFile)
//		transportNetwork.readOSM(mapdbFile)

	}

	private void updateMatsimNetwork() {
		Double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY, minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
		network = EVGlobalData.data.controler.getScenario().getNetwork();
		LinkedList<Id<Link>> linksToRemove = new LinkedList<>();
		linksToRemove.addAll(network.getLinks().keySet());
		HashSet<Node> nodesToRemove = new HashSet<>();
		for(Link link : network.getLinks().values()){
			Id<Link> r5NearestEdgeId = Id.createLinkId(edgeQuadTree.getClosest(link.getCoord().getX(),link.getCoord().getY()));
			nodesToRemove.add(link.getFromNode());
			nodesToRemove.add(link.getToNode());
			matsimLinksToR5Links.put(link.getId(), r5NearestEdgeId);
			Coord coord = link.getFromNode().getCoord();
			if(coord.getX() < minLon)minLon = coord.getX();
			if(coord.getY() < minLat)minLat = coord.getY();
			if(coord.getX() > maxLon)maxLon = coord.getX();
			if(coord.getY() > maxLat)maxLat = coord.getY();
		}
		minLon -= 1000;
		maxLon += 1000;
		minLat -= 1000;
		maxLat += 1000;

		LinkedList<Link> linksToAdd = new LinkedList<>();
		try {
			String linkAttribsFilename = EVGlobalData.data.OUTPUT_DIRECTORY.getAbsolutePath() + File.separator + "r5-link-attribs.csv";
			FileWriter fileWriter = new FileWriter(linkAttribsFilename);
			LinkedList<String> theLine = new LinkedList<>();
			theLine.add("LinkId");
			theLine.add("x");
			theLine.add("y");
			CSVUtil.writeLine(fileWriter,theLine);
			cursor = EVGlobalData.data.networkR5.streetLayer.edgeStore.getCursor();
			while (cursor.advance()) {
				Integer idx = cursor.getEdgeIndex();
				double length = cursor.getLengthM();
				double speed = cursor.getSpeedMs();
				Coord coord = GeoUtils.transformToUtm(cursor.getGeometry().getCoordinate());
				if (coord.getX() > minLon && coord.getY() > minLat && coord.getX() < maxLon && coord.getY() < maxLat) {
					Node dummyFromNode = NetworkUtils.createAndAddNode(network, Id.createNodeId(idx.toString() + "from"), coord);
					Node dummyToNode = NetworkUtils.createAndAddNode(network, Id.createNodeId(idx.toString() + "to"), coord);
					Link r5Link = NetworkUtils.createLink(Id.createLinkId(idx.toString()), dummyFromNode, dummyToNode, network, length, speed, 1.0, 1.0);
					linksToAdd.add(r5Link);
					theLine = new LinkedList<>();
					theLine.add(idx.toString());
					theLine.add(Double.toString(coord.getX()));
					theLine.add(Double.toString(coord.getY()));
					CSVUtil.writeLine(fileWriter,theLine);
				}
			}
			fileWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		for(Id<Link> linkId : linksToRemove){
			network.removeLink(linkId);
		}
		for(Link link : linksToAdd){
			network.addLink(link);
		}
		for(Node node : nodesToRemove){
			network.removeNode(node.getId());
		}
		(new NetworkWriter(network)).write(EVGlobalData.data.OUTPUT_DIRECTORY.getAbsolutePath()+ File.separator + "r5-network.xml");

		log.info("MATSim Network updated to hold R5 edges.");
	}

	private void indexEdges() {
	    Double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY, minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
		cursor = EVGlobalData.data.networkR5.streetLayer.edgeStore.getCursor();
		while(cursor.advance()){
			Coord coord = GeoUtils.transformToUtm(cursor.getGeometry().getCoordinate());
            if(coord.getX() < minLon)minLon = coord.getX();
			if(coord.getY() < minLat)minLat = coord.getY();
			if(coord.getX() > maxLon)maxLon = coord.getX();
			if(coord.getY() > maxLat)maxLat = coord.getY();
		}
		Coord minCoord = new Coord(minLon, minLat);
		Coord maxCoord = new Coord(maxLon, maxLat);
		BeamRouterR5.edgeQuadTree = new QuadTree<>(minCoord.getX(),minCoord.getY(),maxCoord.getX(),maxCoord.getY());
		cursor = EVGlobalData.data.networkR5.streetLayer.edgeStore.getCursor();
		while(cursor.advance()){
			Coord coord = GeoUtils.transformToUtm(cursor.getGeometry().getCoordinate());
			try {
				edgeQuadTree.put(coord.getX(), coord.getY(), Integer.toString(cursor.getEdgeIndex()));
			}catch(IllegalArgumentException e){
				DebugLib.emptyFunctionForSettingBreakPoint();
			}
		}
		cursor = EVGlobalData.data.networkR5.streetLayer.edgeStore.getCursor();
	}

	public LinkedList<RouteInformationElement> calcRoute(Link fromLink, Link toLink, double departureTime, Person person) {
		if(network == null)configure();
		Path path = null;
        PointToPointQuery query = new PointToPointQuery(EVGlobalData.data.networkR5);
        ProfileRequest request = buildRequest(fromLink.getCoord(),toLink.getCoord());
        ProfileResponse response = query.getPlan(request);

		double now = departureTime;
		LinkedList<RouteInformationElement> routeInformation = new LinkedList<>();
		if(response.getOptions().size()==0){
		    log.warn("empty route");
			try {
				cursor.seek(Integer.parseInt(fromLink.getId().toString()));
				double linkTravelTime = cursor.getLengthM() / cursor.getSpeedMs();
				routeInformation.add(new RouteInformationElement(fromLink, linkTravelTime));
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}
			return routeInformation;
		}else{
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
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

		return routeInformation;
	}

	@Override
	public Path calcRoute(Node fromNode, Node toNode, double starttime, Person person, Vehicle vehicle) {
		return null;
	}

	public TripInformation getTripInformation(double time, Link startLink, Link endLink) {
		if(network==null)configure();

//		double roundedTime = MathUtil.roundDownToNearestInterval(time,60.0*60.0);
		double roundedTime = 0.0;
		String key = EVGlobalData.data.linkAttributes.get(startLink.getId().toString()).get("group") + "---" +
			EVGlobalData.data.linkAttributes.get(endLink.getId().toString()).get("group") + "---" +
			EVGlobalData.data.travelTimeFunction.convertTimeToBin(roundedTime);
		getCount++;
		TripInformation resultTrip = EVGlobalData.data.newTripInformationCache.getTripInformation(key);
		if(resultTrip==null){
			cachMiss++;
			resultTrip = new TripInformation(roundedTime, calcRoute(startLink, endLink, roundedTime, null));
			EVGlobalData.data.newTripInformationCache.putTripInformation(key, resultTrip);
//			if(EVGlobalData.data.newTripInformationCache.getCacheSize() % 10000 == 0){
//				EVGlobalData.data.newTripInformationCache.persistStore();
//			}
		}
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
//		return "BeamRouter: hot cache contains "+EVGlobalData.data.newTripInformationCache.getCacheSize()+" trips, current cache miss rate: "+this.cachMiss+"/"+this.getCount;
		return "BeamRouter";
	}

	public ProfileRequest buildRequest(Coord fromCoord, Coord toCoord){
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = EVGlobalData.data.networkR5.getTimeZone();

        Coord fromPosTransformed = GeoUtils.transformToWgs(fromCoord);
        Coord toPosTransformed = GeoUtils.transformToWgs(toCoord);

        profileRequest.fromLat = fromPosTransformed.getY();
        profileRequest.fromLon = fromPosTransformed.getX();
        profileRequest.toLat = toPosTransformed.getY();
        profileRequest.toLon = toPosTransformed.getX();
        profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00");

        profileRequest.directModes = EnumSet.of(LegMode.CAR);
        return profileRequest;
    }
}

