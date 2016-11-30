package beam.sim;

import java.util.concurrent.ExecutionException;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;

public class GlobalActions {
	private static final Logger log = Logger.getLogger(GlobalActions.class);

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

	public void handleDayTracking() throws InterruptedException, ExecutionException {
		log.info("Handling Day Tracking: Set estimated travel distance in day for all agents.");
		EVGlobalData.data.currentDay++;
		log.info(EVGlobalData.data.router);

//	    int threads = Integer.parseInt(EVGlobalData.data.config.getParam("global", "numberOfThreads"));
//	    ExecutorService service = Executors.newFixedThreadPool(threads);

		for (final Person person : EVGlobalData.data.controler.getScenario().getPopulation().getPersons().values()) {
//	        Callable callable = new Callable() {
//	            public Object call() throws Exception {
	                PlugInVehicleAgent.getAgent(person.getId()).setEstimatedTravelDistanceInDay();
//					return null;
//	            }
//	        };
//	        service.submit(callable);
		}
//	    service.shutdown();
//		EVGlobalData.data.router.serializeRouterCache(EVGlobalData.data.ROUTER_CACHE_FILEPATH);
		log.info(EVGlobalData.data.router);
		if(EVGlobalData.data.scheduler.getSize() > 0){
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + 86400.0, this, "handleDayTracking",-1.0);
		}
	}
}
