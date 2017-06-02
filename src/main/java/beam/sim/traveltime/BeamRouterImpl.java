package beam.sim.traveltime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import beam.utils.MathUtil;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.AStarEuclidean;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.PreProcessEuclidean;
import org.matsim.facilities.Facility;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import beam.EVGlobalData;
import org.matsim.vehicles.Vehicle;

public class BeamRouterImpl extends BeamRouter {
	private static final Logger log = Logger.getLogger(BeamRouterImpl.class);

	AStarEuclidean routingAlg;
	Network network;
	int cachMiss = 0, getCount = 0;

	BeamRouterImpl(){
		this(EVGlobalData.data.TRAVEL_TIME_FILEPATH,EVGlobalData.data.ROUTER_CACHE_READ_FILEPATH);
	}
	/*
	 * Construct a BeamRouter where the travel time data are deserialized from the file system
	 */
	public BeamRouterImpl(String travelTimeFunctionSerialPath, String routerCacheSerialPath){
		if(EVGlobalData.data.travelTimeFunction == null){
			EVGlobalData.data.travelTimeFunction = ExogenousTravelTime.LoadTravelTimeFromSerializedData(travelTimeFunctionSerialPath);
		}
		if(EVGlobalData.data.newTripInformationCache == null) {
			EVGlobalData.data.newTripInformationCache = new TripInfoCacheMapDB(routerCacheSerialPath);
//			if((new File(routerCacheSerialPath)).exists()){
//				EVGlobalData.data.newTripInformationCache.deserializeHotCacheKryo(routerCacheSerialPath);
//            }
		}
	}

	/*
	 * Construct a BeamRouter where the travel time data are extracted from an object of type TravelTimeCalculator
	 */
	public BeamRouterImpl(String validationTravelTimeDataFilePath){
		EVGlobalData.data.travelTimeFunction = ExogenousTravelTime.LoadTravelTimeFromValidationData(validationTravelTimeDataFilePath,true);
	}

	//TODO this class should use dependency injection instead of hard-coded configuration
	private void configure(){
		network = EVGlobalData.data.controler.getScenario().getNetwork();
		PreProcessEuclidean preProcessData = new PreProcessEuclidean(EVGlobalData.data.travelTimeFunction);
		preProcessData.run(network);
		routingAlg = new AStarEuclidean(network, preProcessData, EVGlobalData.data.travelTimeFunction);
	}

	public LinkedList<RouteInformationElement> calcRoute(Link fromLink, Link toLink, double departureTime, Person person) {
		if(network == null)configure();
		Path path = null;
		try{
			path = routingAlg.calcLeastCostPath(fromLink.getFromNode(), toLink.getToNode(), departureTime, person, null);
		}catch(NullPointerException e){
		}
		double now = departureTime;
		LinkedList<RouteInformationElement> routeInformation = new LinkedList<>();
		if(path==null)return routeInformation;
		if(path.links.size()==0){
			double linkTravelTime = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(fromLink, now, person, null);
			routeInformation.add(new RouteInformationElement(fromLink, linkTravelTime));
		}else{
			for(Link link : path.links){
				double linkTravelTime = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(link, now, person, null);
				if(linkTravelTime == Double.MAX_VALUE){
					linkTravelTime = link.getLength() / link.getFreespeed();
				}
				routeInformation.add(new RouteInformationElement(link,linkTravelTime));
				now += linkTravelTime;
			}
		}
		return routeInformation;
	}

	@Override
	public Path calcRoute(Node fromNode, Node toNode, double starttime, Person person, Vehicle vehicle) {
		return null;
	}

	public TripInformation getTripInformation(double time, Link startLink, Link endLink) {

		double roundedTime = MathUtil.roundDownToNearestInterval(time,60.0*60.0);
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
		return "BeamRouter: hot cache contains "+EVGlobalData.data.newTripInformationCache.getCacheSize()+" trips, current cache miss rate: "+this.cachMiss+"/"+this.getCount;
	}

}

