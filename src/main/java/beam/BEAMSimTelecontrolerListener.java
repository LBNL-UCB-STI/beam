package beam;

import beam.logit.NestedLogit;
import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.replanning.StrategySequence;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
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
import org.jdom.Element;

import java.util.Iterator;

public class BEAMSimTelecontrolerListener implements BeforeMobsimListener, AfterMobsimListener, ShutdownListener, IterationStartsListener, IterationEndsListener {
	private static final Logger log = Logger.getLogger(BEAMSimTelecontrolerListener.class);
	private static Element logitParams;

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
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		log.info(EVGlobalData.data.router.toString());
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		if (EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH != null)
			EVGlobalData.data.newTripInformationCache.serializeHotCacheKryo(EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH);
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		/*
		 * Here is where you would either initialize the algorithm (if this is iteration 0) or do the update.
		 *
		 * I.e. for initialization, read in the observed loads and the starting place for the parameters. For updates,
		 * read in the simulated loads, calculate the objective function, generate a new set of parameters to simulate
		 * (either from the random draw or from the update step).
		 */

		// CODE HERE

		/*
		 * Here is the code to actually change the values in the list of parameters in a way that can then easily
		 * overwrite the decision models that each agent holds. You are changing a jdom.Element object which is a tree
		 * representation of the XML object (from charging-strategies-nested-logit.xml) and which stores all data as strings.
		 * In the example below, I change the value of the elasticity of the arrival nest to 9999. You will need to add
		 * a bunch of logit here to change the parameters appropriately for the the arrival and departure models (for
		 * starters, feel free to only change the arrival model but we will need to do both eventually)
		 */
		Iterator itr = (BEAMSimTelecontrolerListener.logitParams.getChildren()).iterator();
		while (itr.hasNext()) {
			Element element = (Element) itr.next();
			if (element.getAttribute("name").getValue().toLowerCase().equals("arrival")) {
				Iterator itrArrival = element.getChildren().iterator();
				while (itrArrival.hasNext()) {
					// CODE HERE
					Element subElement = (Element) itrArrival.next();
					subElement.setText("9999");
					int j = 0;
				}
			} else if (element.getAttribute("name").getValue().toLowerCase().equals("departure")) {
			} else {
				DebugLib.stopSystemAndReportInconsistency("Charging Strategy config file has a nestedLogit element with an unrecongized name."
						+ " Expecting one of 'arrival' or 'depature' but found: " + element.getAttribute("name").getValue());
			}
		}

		/*
		 * This code you shouldn't need to touch, it just loops through all agents and replaces their choice models with
		 * fresh models based on the new params defined above.
		 */
		for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
			StrategySequence sequence = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategiesForTheDay();
			for(int i = 0; i < sequence.getSequenceLength(); i++){
				ChargingStrategy strategy = sequence.getStrategy(i);
				if(strategy instanceof ChargingStrategyNestedLogit){
					ChargingStrategyNestedLogit logitStrategy = (ChargingStrategyNestedLogit)strategy;
					logitStrategy.resetDecisions();
					logitStrategy.setParameters(BEAMSimTelecontrolerListener.logitParams);
				}
			}
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

	public static void setInitialLogitParams(Element params){
		BEAMSimTelecontrolerListener.logitParams = params;
	}

}
