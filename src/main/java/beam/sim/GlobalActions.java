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
	public void pauseForHour(){
		try {
			Thread.sleep(1000L * 3600L);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void handleDayTracking() throws InterruptedException, ExecutionException {
		log.info("Handling Day Tracking: Set estimated travel distance in day for all agents.");
		EVGlobalData.data.currentDay++;
		log.info(EVGlobalData.data.router);

		for (final Person person : EVGlobalData.data.controler.getScenario().getPopulation().getPersons().values()) {
			PlugInVehicleAgent.getAgent(person.getId()).setEstimatedTravelDistanceInDay();
		}
		EVGlobalData.data.newTripInformationCache.serializeHotCacheKryo(EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH);
		if(EVGlobalData.data.scheduler.getSize() > 0){
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + 86400.0, this, "handleDayTracking",-1.0);
		}
	}
}
