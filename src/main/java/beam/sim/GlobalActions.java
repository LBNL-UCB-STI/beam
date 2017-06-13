package beam.sim;

import java.util.*;
import java.util.concurrent.ExecutionException;

import beam.sim.traveltime.BeamRouteAllParallel;
import beam.sim.traveltime.TripInformation;
import beam.utils.MathUtil;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;

public class GlobalActions {
	private static final Logger log = Logger.getLogger(GlobalActions.class);
	private static Boolean hasWarmedCache = false;

	public GlobalActions() {
	}
	public void printRand(){
		log.info(EVGlobalData.data.now + ": Random Num " + EVGlobalData.data.rand.nextInt());
		if(EVGlobalData.data.now > 12900){
			DebugLib.stopSystemAndReportInconsistency();
		}else if(EVGlobalData.data.now < 40*3600){
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + 1.0, this, "printRand",100.0);
		}
	}
	public void pauseForHour(){
		try {
			Thread.sleep(1000L * 3600L);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void handleDayTracking() throws InterruptedException, ExecutionException {
		if (!hasWarmedCache) {
//			log.info("Warming up cache");
//			fixLinkLengthIssues();
//			warmUpCache();
			hasWarmedCache = true;
		}
		log.info("Handling Day Tracking: Set estimated travel distance in day for all agents.");
		EVGlobalData.data.currentDay++;
		log.info(EVGlobalData.data.router);
		int i = 1, multiplier = 1;

		for (final Person person : EVGlobalData.data.controler.getScenario().getPopulation().getPersons().values()) {
			PlugInVehicleAgent.getAgent(person.getId()).setEstimatedTravelDistanceInDay();
			if (i == multiplier) {
				log.info("person # " + i);
				multiplier *= 2;
			}
			i++;
		}
//		if (EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH != null)
		//EVGlobalData.data.newTripInformationCache.persistStore();
		if (EVGlobalData.data.scheduler.getSize() > 0) {
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + 86400.0, this, "handleDayTracking", -1.0);
		}
	}

	private void fixLinkLengthIssues() {
		for(Link link: EVGlobalData.data.controler.getScenario().getNetwork().getLinks().values()) {
			double euclid = Math.sqrt(Math.pow(link.getToNode().getCoord().getX() - link.getFromNode().getCoord().getX(), 2.0) + Math.pow(link.getToNode().getCoord().getY() - link.getFromNode().getCoord().getY(), 2.0));
			if (link.getLength() < euclid) {
				link.setLength(euclid + 1e-6);
			}
		}
	}

	public void warmUpCache(){
			LinkedHashMap<String,Link> fromGroups = new LinkedHashMap<>(), toGroups = new LinkedHashMap<>();
			for(Link link: EVGlobalData.data.controler.getScenario().getNetwork().getLinks().values()) {
				if(EVGlobalData.data.linkAttributes.get(link.getId().toString()) != null){
					String groupId = EVGlobalData.data.linkAttributes.get(link.getId().toString()).get("group");
                    fromGroups.put(groupId,link);
					toGroups.put(groupId,link);
				}
			}
			LinkedList<Thread> waitingThreads = new LinkedList<Thread>();
			LinkedList<Thread> runningThreads = new LinkedList<Thread>();
			for (String fromGroup : fromGroups.keySet()) {
				Runnable task = new BeamRouteAllParallel(fromGroup,fromGroups,toGroups);
				Thread worker = new Thread(task);
				worker.setName(fromGroup);
				waitingThreads.push(worker);
			}
			int maxThreads = EVGlobalData.data.NUM_THREADS, persistCounter = 0;
			do {
				List<Thread> threadsToRemove = new LinkedList<Thread>();
				for (Thread thread : runningThreads) {
					if (thread.getState() == Thread.State.TERMINATED) {
						threadsToRemove.add(thread);
					}
				}
				for (Thread thread : threadsToRemove) {
					runningThreads.remove(thread);
				}
				int startBegin = runningThreads.size();
				int startEnd = Math.min(waitingThreads.size(),maxThreads);
				for(int i = startBegin; i < startEnd; i++){
					Thread threadToStart = waitingThreads.pop();
					threadToStart.start();
					runningThreads.push(threadToStart);
				}
				log.info("We have " + runningThreads.size() + " running threads and " + waitingThreads.size() + " waiting to run and cache "+EVGlobalData.data.newTripInformationCache.cacheSizeAsString());
//				if(persistCounter++ % 20 == 0){
//					log.info("Persisting store, "+EVGlobalData.data.newTripInformationCache.cacheSizeAsString());
//					EVGlobalData.data.newTripInformationCache.persistStore();
//				}
				try {
					Thread.sleep(30000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} while (waitingThreads.size() > 0);

//            log.info("Persisting store, "+EVGlobalData.data.newTripInformationCache.cacheSizeAsString());
//            EVGlobalData.data.newTripInformationCache.persistStore();

	}
	public Boolean areLinksConnected(String from, String to){
		Id<Node> fromId = Id.createNodeId(from), toId = Id.createNodeId(to);
		Link fromLink = EVGlobalData.data.controler.getScenario().getNetwork().getNodes().get(fromId).getOutLinks().values().iterator().next();
		Link toLink = EVGlobalData.data.controler.getScenario().getNetwork().getNodes().get(toId).getOutLinks().values().iterator().next();
		Link currentLink = fromLink;

		HashSet<Link> visited = new HashSet<>();
		LinkedList<Link> toVisit = new LinkedList<>();

		while(true){
			for(double i = 0.0; i <= 24.0; i+=0.5) {
				double tt = EVGlobalData.data.travelTimeFunction.getLinkTravelTime(currentLink, i, null, null);
				if(tt >= 86400.0){
					return true;
				}else if (tt< 0.0){
					return true;
				}
			}
			if(currentLink==toLink){
				return true;
			}
			visited.add(currentLink);
			toVisit.addAll(currentLink.getToNode().getOutLinks().values());
			toVisit.addAll(currentLink.getFromNode().getInLinks().values());

			while(visited.contains(currentLink)){
			    if(toVisit.size()==0)return false;
				currentLink = toVisit.poll();
			}
		}
	}
}

