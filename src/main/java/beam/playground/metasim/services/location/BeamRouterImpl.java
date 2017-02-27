package beam.playground.metasim.services.location;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
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
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.facilities.Facility;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.CommandLineParameters;
import org.opentripplanner.standalone.OTPMain;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class BeamRouterImpl extends BeamRouter {

	AStarEuclidean routingAlg;
	Network network;
	int cachMiss = 0, getCount = 0;

	BeamRouterImpl(){ 
		CommandLineParameters params = new CommandLineParameters();
		// TODO remove hard coded file path
		params.build = new File("/Users/critter/Documents/beam/otp/");
		OTPMain otp = new OTPMain(params);
		Graph graph = otp.graphService.getRouter().graph;

//		this(EVGlobalData.data.RELAXED_TRAVEL_TIME_FILEPATH,EVGlobalData.data.ROUTER_CACHE_READ_FILEPATH);
	}
	/*
	 * Construct a BeamRouter where the travel time data are deserialized from the file system
	 */
	public BeamRouterImpl(String travelTimeFunctionSerialPath, String routerCacheSerialPath){
//		if(EVGlobalData.data.travelTimeFunction == null)deserializeTravelTimeFunction(travelTimeFunctionSerialPath);
//		if(EVGlobalData.data.tripInformationCache == null){
			if((new File(routerCacheSerialPath)).exists()){
				deserializeRouterCache(routerCacheSerialPath);
			}else{
//				EVGlobalData.data.tripInformationCache = new LinkedHashMap<String,TripInformation>();
			}
//		}
	}
	
	/*
	 * Construct a BeamRouter where the travel time data are extracted from an object of type TravelTimeCalculator
	 */
	public BeamRouterImpl(TravelTimeCalculator ttCalculator){
//		EVGlobalData.data.travelTimeFunction = new RelaxedTravelTime(true, ttCalculator);
	}
	
	//TODO this class should use dependency injection instead of hard-coded configuration
	private void configure(){
//		network = EVGlobalData.data.controler.getScenario().getNetwork();
//		PreProcessEuclidean preProcessData = new PreProcessEuclidean(EVGlobalData.data.travelTimeFunction);
//		preProcessData.run(network);
//		routingAlg = new AStarEuclidean(network, preProcessData, EVGlobalData.data.travelTimeFunction);
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
//			double linkTravelTime = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(fromLink, now, person, null);
//			routeInformation.add(new RouteInformationElement(fromLink, linkTravelTime));
		}else{
			for(Link link : path.links){
//				double linkTravelTime = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(link, now, person, null);
//				if(linkTravelTime == Double.MAX_VALUE){
//					linkTravelTime = link.getLength() / link.getFreespeed();
//				}
//				routeInformation.add(new RouteInformationElement(link,linkTravelTime));
//				now += linkTravelTime;
			}
		}
		return routeInformation;
	}
	
	private TripInformation getTripInformation(double departureTime, Id<Link> fromLinkId, Id<Link> toLinkId) {
		if(network==null)configure();
		return getTripInformation(departureTime, network.getLinks().get(fromLinkId), network.getLinks().get(toLinkId));
	}

	public List<? extends PlanElement> calcRoute(Facility<?> fromFacility, Facility<?> toFacility, double departureTime, Person person) {
		List list=new ArrayList();
//		BeamLeg leg = new BeamLeg(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES);
//		list.add(leg);
//		leg.setDepartureTime(departureTime);
//		leg.setTravelTime(getTripInformation(departureTime, fromFacility.getLinkId(), toFacility.getLinkId()).getTripTravelTime());
		LinkNetworkRouteImpl route=new LinkNetworkRouteImpl(fromFacility.getLinkId(), toFacility.getLinkId());
//		leg.setRoute(route);
		route.setDistance(1);

		return list;
	}
	public StageActivityTypes getStageActivityTypes() {
		return EmptyStageActivityTypes.INSTANCE;
	}
	public String toString(){
//		return "BeamRouter: cache contains "+EVGlobalData.data.tripInformationCache.size()+" trips, current cache miss rate: "+this.cachMiss+"/"+this.getCount;
		return "";
	}
}

