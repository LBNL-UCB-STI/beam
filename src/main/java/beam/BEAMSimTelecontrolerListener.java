package beam;

import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.replanning.StrategySequence;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
import beam.utils.StdRandom;
import org.apache.log4j.Logger;
import org.jdom.Document;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
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
import org.jdom.Element;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;

public class BEAMSimTelecontrolerListener implements BeforeMobsimListener, AfterMobsimListener, ShutdownListener, IterationStartsListener, IterationEndsListener {
	private static final Logger log = Logger.getLogger(BEAMSimTelecontrolerListener.class);
	private static Element logitParams, logitParamsPlus, logitParamsMinus;
	private double a0=0.5f, c0=0.5f, alpha=1f, gamma= 0.4f, a,c, diff, grad;
	private boolean shouldUpdateLogitParams = true; // true when updating objective function
	private boolean shouldUpdateBetaPlus, shouldUpdateBetaMinus, isFirstIteration;
	private ArrayList<Double> paramsList = new ArrayList<>(), paramsPlus = new ArrayList<>(), paramsMinus = new ArrayList<>(), paramsDelta = new ArrayList<>();
	private ArrayList<Double> loadProfile = new ArrayList<>(), loadProfileBetaPlus = new ArrayList<>(),
			loadProfileBetaMinus = new ArrayList<>(), loadProfileChargingPoint = new ArrayList<>();

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
		shouldUpdateLogitParams	= (event.getIteration()%3 == 0);
		shouldUpdateBetaPlus 	= (event.getIteration()%3 == 1);
		shouldUpdateBetaMinus 	= (event.getIteration()%3 == 2);
		isFirstIteration		= (event.getIteration() == 0);
		a 	= a0 / (Math.pow(event.getIteration(),alpha));
		c 	= c0 / (Math.pow(event.getIteration(),gamma));

		if(isFirstIteration){
			//TODO: << MARKED >> load parameters from logit model XML for arrival and departure and assign the parameters to params arrival and params departure
			try {
				Element element = loadChargingStrategies();
				paramsList = getUtilityParams(element);
			} catch (Exception e) {
				e.printStackTrace();
			}

			//TODO: read observed loads (only for 27-51) -- from charging point data

			//TODO: << MARKED >> observed data and simulated data have to be merged -- this is tricky part how can we do this easier?

		}else{
			//TODO: random values
			if(shouldUpdateBetaPlus){
				// TODO: REINITIALIZE logitParamsPlus and logitParamsMinus
			}

			//TODO: update logit params
			if(shouldUpdateLogitParams){
				//TODO: read simulated loads (only for 27-51) -- from the previous simulation result
				// CSV READ HERE
//				loadProfile;
	//			obj[i] = ((observed - modeled)**2) -- need to track this error somewhere
				// TODO: read load profile of simulation with beta plus
				// CSV READ HERE
//				loadProfileBetaPlus;
				// TODO: read load profile of simulation with beta minus
				// CSV READ HERE
//				loadProfileBetaMinus;
				// TODO: update gradient
				diff = 0;
				for(int i =0; i<loadProfileBetaPlus.size(); i++){
					diff += Math.pow(loadProfileChargingPoint.get(i)-loadProfileBetaPlus.get(i),2)
							- Math.pow(loadProfileChargingPoint.get(i)-loadProfileBetaMinus.get(i),2);
				}
			}

		}

		/*
		 * Here is the code to actually change the values in the list of parameters in a way that can then easily
		 * overwrite the decision models that each agent holds. You are changing a jdom.Element object which is a tree
		 * representation of the XML object (from charging-strategies-nested-logit.xml) and which stores all data as strings.
		 * In the example below, I change the value of the elasticity of the arrival nest to 9999. You will need to add
		 * a bunch of logit here to change the parameters appropriately for the the arrival and departure models (for
		 * starters, feel free to only change the arrival model but we will need to do both eventually)
		 */
		Iterator itr = null;
		int paramIndex = 0;
		// TODO: reinitialize logitParamsPlus and logitParamsMinus
		if(shouldUpdateBetaPlus) itr = (BEAMSimTelecontrolerListener.logitParamsPlus.getChildren()).iterator();
		if(shouldUpdateBetaMinus) itr = (BEAMSimTelecontrolerListener.logitParamsMinus.getChildren()).iterator();
		if(shouldUpdateLogitParams){
			itr = (BEAMSimTelecontrolerListener.logitParams.getChildren()).iterator();
			paramsDelta = new ArrayList<>();
		}
		while (itr != null && itr.hasNext()) {
			Element element = (Element) itr.next();

			if (element.getAttribute("name").getValue().toLowerCase().equals("arrival")) {
				Iterator itrArrival = element.getChildren().iterator();
				paramIndex = 0;
				while (itrArrival.hasNext()) {
					// CODE HERE
					Element subElement = (Element) itrArrival.next();
					paramsList.add(Double.valueOf(subElement.getText()));
					// NEED TO WORK HERE!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
					if(shouldUpdateBetaPlus){
						boolean delta = StdRandom.bernoulli();
						paramsDelta.add((double) ((delta ? 1 : 0) * 2 - 1));
						subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * ((delta?1:0)*2-1)));
					}else if(shouldUpdateBetaMinus) subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * paramsDelta.get(paramIndex)));
					else if(shouldUpdateLogitParams && !isFirstIteration){
						grad = diff/(2*c*paramsDelta.get(paramIndex));
						subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) - a * grad));
					}

					//					subElement.setText("9999");
					// TODO: change the values of the parameters
					int j = 0;
					paramIndex++;
				}
			} else if (element.getAttribute("name").getValue().toLowerCase().equals("departure")) {
				// TODO: update params for departure
			} else {
				DebugLib.stopSystemAndReportInconsistency("Charging Strategy config file has a nestedLogit element with an unrecongized name."
						+ " Expecting one of 'arrival' or 'departure' but found: " + element.getAttribute("name").getValue());
			}
		}

		/*
		 * This code you shouldn't need to touch, it just loops through all agents and replaces their choice models with
		 * fresh models based on the new params defined above.
		 */
		Element elem = null;
		if(shouldUpdateBetaPlus) elem = BEAMSimTelecontrolerListener.logitParamsPlus;
		if(shouldUpdateBetaMinus) elem = BEAMSimTelecontrolerListener.logitParamsMinus;
		if(shouldUpdateLogitParams) elem = BEAMSimTelecontrolerListener.logitParams;
		for (Person person : event.getServices().getScenario().getPopulation().getPersons().values()) {
			StrategySequence sequence = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategiesForTheDay();
			for(int i = 0; i < sequence.getSequenceLength(); i++){
				ChargingStrategy strategy = sequence.getStrategy(i);
				if(strategy instanceof ChargingStrategyNestedLogit){
					ChargingStrategyNestedLogit logitStrategy = (ChargingStrategyNestedLogit)strategy;
					logitStrategy.resetDecisions();
					logitStrategy.setParameters(elem);
				}
			}
		}

	}

	/**
	 * Load the recent charging strategies from XML
	 */
	public Element loadChargingStrategies() throws Exception {
		SAXBuilder saxBuilder = new SAXBuilder();
		FileInputStream stream;
		Document document = null;
		try {
			stream = new FileInputStream(EVGlobalData.data.CHARGING_STRATEGIES_FILEPATH);
			document = saxBuilder.build(stream);
		} catch (JDOMException | IOException e) {
			DebugLib.stopSystemAndReportInconsistency(e.getMessage());
		}

		for(int i = 0; i < (document != null ? document.getRootElement().getChildren().size() : 0); i++){
			Element elem = (Element)document.getRootElement().getChildren().get(i);
			if(elem.getName().toLowerCase().equals("strategy")){
				return elem.getChild("parameters");
			}else{
				throw new Exception("Error in loading charging strategies: no child element named parameters!");
			}
		}
		return null;
	}

	/**
	 * Return utility params in double array list
	 * @param rootElem: nested logit strategy elements
	 * @return
	 */
	public ArrayList<Double> getUtilityParams(Element rootElem){
		ArrayList<Double> paramArr = new ArrayList<>();
		for (Object o : (rootElem.getChildren())) {
			Element element = (Element) o;
			if (element.getName().toLowerCase().equals("param")) {
				paramArr.add(Double.valueOf(element.getValue()));
			}
		}
		return paramArr;
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
