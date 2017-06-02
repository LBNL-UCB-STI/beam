package beam;

import beam.calibration.LogitParamCalibration;
import beam.calibration.SitingCalibration;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;

import beam.charging.vehicle.PlugInVehicleAgent;
import beam.replanning.ChargingStrategyManager;
import beam.replanning.io.EVDailyPlanWriter;

public class BEAMSimTelecontrolerListener implements BeforeMobsimListener, AfterMobsimListener, ShutdownListener, IterationStartsListener, IterationEndsListener {
	private static final Logger log = Logger.getLogger(BEAMSimTelecontrolerListener.class);


	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		log.info("In controler at iteration " + event.getIteration());
		for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
			PlugInVehicleAgent agent = PlugInVehicleAgent.getAgent(person.getId());
			agent.resetAll();
		}
		
		for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
			ChargingStrategyManager.data.getReplanable(person.getId()).trimEVDailyPlanMemoryIfNeeded();
			ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategiesForTheDay().resetScore();
		}	
		
		EVGlobalData.data.chargingInfrastructureManager.resetAll();
		
		PlugInVehicleAgent.resetDecisionEventIdCounter();

		EVGlobalData.data.currentDay = 0;
		EVSimTeleController.scheduleGlobalActions();
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
//		EVGlobalData.data.newTripInformationCache.persistStore();
		log.info(EVGlobalData.data.router.toString());
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
//		if (EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH != null)
//			EVGlobalData.data.newTripInformationCache.serializeHotCacheKryo(EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH);
//		EVGlobalData.data.newTripInformationCache.close();
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		if(EVGlobalData.data.SHOULD_CALIBRATE_PARAMS) { // Calibrate nested logit model params?
			LogitParamCalibration.getInstance().run(event);
		}else if(EVGlobalData.data.SHOULD_CALIBRATE_SITES){ // Calibrate new sites?
			SitingCalibration.getInstance().run(event);
		}else{ // Normal simulation without calibration?
			// Do nothing
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (EVGlobalData.data.DUMP_PLAN_CSV && EVGlobalData.data.DUMP_PLAN_CSV_INTERVAL > 0
				&& event.getIteration() % EVGlobalData.data.DUMP_PLAN_CSV_INTERVAL == 0) {
			EVDailyPlanWriter evDailyPlanWriter = new EVDailyPlanWriter(
					event.getServices().getControlerIO().getIterationFilename(event.getIteration(), EVGlobalData.data.SELECTED_EV_DAILY_PLANS_FILE_NAME));
			for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
				evDailyPlanWriter.writeEVDailyPlan(ChargingStrategyManager.data.getReplanable(person.getId()));
			}
			evDailyPlanWriter.closeFile();
		}
	}

}

