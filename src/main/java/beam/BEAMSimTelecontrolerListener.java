package beam;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
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
import beam.replanning.EVDailyReplanable;
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
		}	
		
		EVGlobalData.data.chargingInfrastructureManager.resetAll();
		
		PlugInVehicleAgent.resetDecisionEventIdCounter();
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		log.info(EVGlobalData.data.router.toString());
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		if (EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH != null)
			EVGlobalData.data.router.serializeRouterCache(EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH);
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		
		
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (EVGlobalData.data.DUMP_PLAN_CSV && EVGlobalData.data.DUMP_PLAN_CSV_INTERVAL > 0
				&& event.getIteration() % EVGlobalData.data.DUMP_PLAN_CSV_INTERVAL == 0) {
			EVDailyPlanWriter evDailyPlanWriter = new EVDailyPlanWriter(
					event.getServices().getControlerIO().getIterationFilename(event.getIteration(), EVGlobalData.data.SELECTED_EV_DAILY_PLANS_FILE_NAME));
			for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
				evDailyPlanWriter.writeEVDailyPlan(ChargingStrategyManager.data.getReplanable(person.getId()));
				ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategiesForTheDay().resetScore();
			}
			evDailyPlanWriter.closeFile();
		}
	}

}
