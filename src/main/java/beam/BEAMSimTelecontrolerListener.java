package beam;

import beam.parking.lib.DebugLib;
import beam.replanning.ChargingStrategy;
import beam.replanning.StrategySequence;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
import beam.utils.CSVUtil;
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
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import java.io.*;
import java.util.*;

public class BEAMSimTelecontrolerListener implements BeforeMobsimListener, AfterMobsimListener, ShutdownListener, IterationStartsListener, IterationEndsListener {
	private static final Logger log = Logger.getLogger(BEAMSimTelecontrolerListener.class);
	private static Element logitParams, logitParamsPlus, logitParamsMinus;
	private double a0=0.5f, c0=0.5f, alpha=1f, gamma= 0.4f, a,c, diff, maxDiff = 0, grad;
	private boolean shouldUpdateBeta = true; // true when updating objective function
	private boolean shouldUpdateBetaPlus, shouldUpdateBetaMinus, isFirstIteration;
	private ArrayList<Double> paramsList = new ArrayList<>(), paramsPlus = new ArrayList<>(), paramsMinus = new ArrayList<>(), paramsDelta = new ArrayList<>();
	private LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>>
			observedLoadHashMap = new LinkedHashMap<>(),
			betaLoadHashMap =new LinkedHashMap<>(),
			betaPlusLoadHashMap = new LinkedHashMap<>(),
			betaMinusLoadHashMap = new LinkedHashMap<>();
	private ArrayList<Double> loadProfileBeta = new ArrayList<>(), loadProfileBetaPlus = new ArrayList<>(), loadProfileBetaMinus = new ArrayList<>(), loadProfileChargingPoint = new ArrayList<>();

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
		log.info(EVGlobalData.data.router.toString());
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		if (EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH != null)
			EVGlobalData.data.newTripInformationCache.serializeHotCacheKryo(EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH);
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		log.info("notifyIterationStarts is called");

		if(EVGlobalData.data.SHOULD_DO_PARAM_CALIBRATION){

			/*
		 * Here is where you would either initialize the algorithm (if this is iteration 0) or do the update.
		 *
		 * I.e. for initialization, read in the observed loads and the starting place for the parameters. For updates,
		 * read in the simulated loads, calculate the objective function, generate a new set of parameters to simulate
		 * (either from the random draw or from the update step).
		 */
			isFirstIteration		= (event.getIteration() == 0);
			shouldUpdateBeta 		= (event.getIteration()%3 == 0) && !isFirstIteration;
			shouldUpdateBetaPlus 	= (event.getIteration()%3 == 1);
			shouldUpdateBetaMinus 	= (event.getIteration()%3 == 2);
			a 	= a0 / (Math.pow(event.getIteration(),alpha));
			c 	= c0 / (Math.pow(event.getIteration(),gamma));

			if(isFirstIteration){
				// load parameters from logit model XML
				try {
					logitParams = loadChargingStrategies();
					paramsList 	= getUtilityParams(logitParams);
				} catch (Exception e) {
					e.printStackTrace();
				}
				observedLoadHashMap = getChargingLoadHashMap(EVGlobalData.data.CHARGING_LOAD_VALIDATION_FILEPATH);
			}else{
				if(shouldUpdateBetaPlus){
					// Re-initialize params
					logitParamsPlus 	= logitParams;
					logitParamsMinus 	= logitParams;
				}

				// Update Logit params
				if(shouldUpdateBeta){
					String prevLoadFile 		= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator + "it." + (event.getIteration()-3) + File.separator + "run0."+ (event.getIteration()-3) + ".disaggregateLoadProfile.csv";
					String betaPlusLoadFile 	= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator + "it." + (event.getIteration()-2) + File.separator + "run0."+ (event.getIteration()-2) + ".disaggregateLoadProfile.csv";
					String betaMinusLoadFile 	= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator + "it." + (event.getIteration()-1) + File.separator + "run0."+ (event.getIteration()-1) + ".disaggregateLoadProfile.csv";

					// Merge simulated data first
					betaLoadHashMap = getMergedHashMap(getChargingLoadHashMap(prevLoadFile), getChargingLoadHashMap(betaPlusLoadFile));
					betaLoadHashMap = getMergedHashMap(betaLoadHashMap, getChargingLoadHashMap(betaMinusLoadFile));

					betaPlusLoadHashMap = getMergedHashMap(getChargingLoadHashMap(betaPlusLoadFile), betaLoadHashMap);
					betaMinusLoadHashMap = getMergedHashMap(getChargingLoadHashMap(betaMinusLoadFile), betaLoadHashMap);

					loadProfileBeta 			= getMergedLoadProfile(initDisaggFileWriter(event.getIteration(),"beta"), betaLoadHashMap, observedLoadHashMap);
					loadProfileBetaPlus 		= getMergedLoadProfile(initDisaggFileWriter(event.getIteration(),"betaPlus"), betaPlusLoadHashMap, observedLoadHashMap);
					loadProfileBetaMinus 		= getMergedLoadProfile(initDisaggFileWriter(event.getIteration(),"betaMinus"), betaMinusLoadHashMap, observedLoadHashMap);
					loadProfileChargingPoint 	= getMergedLoadProfile(initDisaggFileWriter(event.getIteration(),"observed"), observedLoadHashMap, betaLoadHashMap);

					//			obj[i] = ((observed - modeled)**2) -- need to track this error somewhere

					// update gradient
					diff = 0;
					double scaler = 500/70000;
					log.info("loadProfileBeta size: " + loadProfileBeta.size());
					log.info("loadProfileBetaPlus size: " + loadProfileBetaPlus.size());
					log.info("loadProfileBetaMinus size: " + loadProfileBetaMinus.size());
					log.info("loadProfileChargingPoint size: " + loadProfileChargingPoint.size());
					for(int i =0; i<loadProfileBetaPlus.size(); i++){
						if(i==loadProfileBetaPlus.size()) break;
						try{
							diff += Math.pow(loadProfileChargingPoint.get(i)*scaler-loadProfileBetaPlus.get(i),2)
									- Math.pow(loadProfileChargingPoint.get(i)*scaler-loadProfileBetaMinus.get(i),2);
						}catch(Exception e){break;}
					}
					if(Math.abs(diff) >= maxDiff) maxDiff = Math.abs(diff);
					log.info("HERE!!!!! diff: " + diff);
					log.info("HERE!!!!! max diff: " + maxDiff);
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
			double paramMaxConst = 10, paramMinConst = -10;
			// reinitialize logitParamsPlus and logitParamsMinus
			if(shouldUpdateBetaPlus) {
				paramsDelta = new ArrayList<>();
				itr = (BEAMSimTelecontrolerListener.logitParamsPlus.getChildren()).iterator();
			}
			if(shouldUpdateBetaMinus) itr = (BEAMSimTelecontrolerListener.logitParamsMinus.getChildren()).iterator();
			if(shouldUpdateBeta){
				itr = (BEAMSimTelecontrolerListener.logitParams.getChildren()).iterator();
			}
			if(!isFirstIteration){
				while (itr != null && itr.hasNext()) { //TODO: arrival/departure
					Element element = (Element) itr.next();
					Iterator itrElem = element.getChildren().iterator();
					paramIndex = 0;
					while (itrElem.hasNext()) { //TODO: yescharge/nocharge
						Element subElement = ((Element) itrElem.next());
						log.info("subElement.getName().toLowerCase(): " + subElement.getName().toLowerCase() + "name: " + subElement.getAttributeValue("name"));
						if(subElement.getName().toLowerCase().equals("nestedlogit")){
							for (Object obj1 : subElement.getChildren()) { //TODO: genericSitePlug...
								Element childElement = ((Element) obj1);
								if (childElement.getName().toLowerCase().equals("nestedlogit")) {
									for (Object obj2 : (childElement.getChild("utility")).getChildren()) { //TODO: parameters
										Element utilityElement = ((Element) obj2);
										if (utilityElement.getName().equals("param")) {
											// Only update intercept
											log.info("subElement.getAttributeValue(\"name\").toLowerCase(): " + utilityElement.getAttributeValue("name").toLowerCase());
											if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
												if (shouldUpdateBetaPlus) {
													boolean delta = StdRandom.bernoulli();
													paramsDelta.add(paramIndex++, (double) ((delta ? 1 : 0) * 2 - 1));
													log.info("(betaPlus) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * ((delta ? 1 : 0) * 2 - 1)));
													log.info("(betaPlus) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												} else if (shouldUpdateBetaMinus) {
													log.info("(betaMinus) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * paramsDelta.get(paramIndex++)));
													log.info("(betaMinus) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												} else if (shouldUpdateBeta) {
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													grad = (diff/maxDiff)*paramMaxConst / (2 * c * paramsDelta.get(paramIndex++));
													double updatedParam = Double.valueOf(utilityElement.getText()) - a * grad;
													if(updatedParam >= paramMaxConst || updatedParam <= paramMinConst){
														if(updatedParam >= 0) updatedParam = paramMaxConst;
														if(updatedParam < 0) updatedParam = paramMinConst;
													}
													utilityElement.setText(String.valueOf(updatedParam));
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												}
											}
										}
									}
								} else if (childElement.getName().toLowerCase().equals("utility")) {
									for (Object o : childElement.getChildren()) {
										Element utilityElement = (Element) o;
										if (utilityElement.getName().equals("param")) {
											// Only update intercept
											log.info("subElement.getAttributeValue(\"name\").toLowerCase(): " + utilityElement.getAttributeValue("name").toLowerCase());
											if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
												if (shouldUpdateBetaPlus) {
													boolean delta = StdRandom.bernoulli();
													paramsDelta.add(paramIndex++, (double) ((delta ? 1 : 0) * 2 - 1));
													log.info("(betaPlus) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * ((delta ? 1 : 0) * 2 - 1)));
													log.info("(betaPlus) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												} else if (shouldUpdateBetaMinus) {
													log.info("(betaMinus) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * paramsDelta.get(paramIndex++)));
													log.info("(betaMinus) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												} else if (shouldUpdateBeta) {
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													grad = (diff/maxDiff)*paramMaxConst / (2 * c * paramsDelta.get(paramIndex++));
													double updatedParam = Double.valueOf(utilityElement.getText()) - a * grad;
													if(updatedParam >= paramMaxConst || updatedParam <= paramMinConst){
														if(updatedParam >= 0) updatedParam = paramMaxConst;
														if(updatedParam < 0) updatedParam = paramMinConst;
													}
													utilityElement.setText(String.valueOf(updatedParam));
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " updated param: " + utilityElement.getText());
												}
											}
										}
									}
								}
							}
						}
//					if(subElement.getName().toLowerCase().equals("params")){
//						// Only update intercept
//						log.info("subElement.getAttributeValue(\"name\").toLowerCase(): " + subElement.getAttributeValue("name").toLowerCase());
//						if(subElement.getAttributeValue("name").toLowerCase().equals("intercept")){
//							if(shouldUpdateBetaPlus){
//								boolean delta = StdRandom.bernoulli();
//								paramsDelta.add(paramIndex++, (double) ((delta ? 1 : 0) * 2 - 1));
//								log.info("(betaPlus) attribute: " + subElement.getAttributeValue("name") + " origin param: " + subElement.getText());
//								subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * ((delta?1:0)*2-1)));
//								log.info("(betaPlus) attribute: " + subElement.getAttributeValue("name") + " updated param: " + subElement.getText());
//							}else if(shouldUpdateBetaMinus){
//								log.info("(betaMinus) attribute: " + subElement.getAttributeValue("name") + " origin param: " + subElement.getText());
//								subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * paramsDelta.get(paramIndex++)));
//								log.info("(betaMinus) attribute: " + subElement.getAttributeValue("name") + " updated param: " + subElement.getText());
//							}else if(shouldUpdateBeta){
//								log.info("(param update) attribute: " + subElement.getAttributeValue("name") + " origin param: " + subElement.getText());
//								grad = diff/(2*c*paramsDelta.get(paramIndex++));
//								subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) - a * grad));
//								log.info("(param update) attribute: " + subElement.getAttributeValue("name") + " updated param: " + subElement.getText());
//							}
//						}
//					}
					}
//			if (element.getAttribute("name").getValue().toLowerCase().equals("arrival")) { // do we need to distinguish
//				Iterator itrArrival = element.getChildren().iterator();
//				paramIndex = 0;
//				while (itrArrival.hasNext()) {
//					// CODE HERE
//					Element subElement = (Element) itrArrival.next();
//					if(subElement.getName().toLowerCase().equals("params")){
//						if(shouldUpdateBetaPlus){
//							boolean delta = StdRandom.bernoulli();
//							paramsDelta.add((double) ((delta ? 1 : 0) * 2 - 1));
//							subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * ((delta?1:0)*2-1)));
//						}else if(shouldUpdateBetaMinus) subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) + c * paramsDelta.get(paramIndex)));
//						else if(shouldUpdateBeta && !isFirstIteration){
//							grad = diff/(2*c*paramsDelta.get(paramIndex));
//							subElement.setText(String.valueOf(Double.valueOf(subElement.getText()) - a * grad));
//						}
//
//						paramIndex++;
//					}
//				}
//			} else if (element.getAttribute("name").getValue().toLowerCase().equals("departure")) {
//				// TODO: update params for departure
//			} else {
//				DebugLib.stopSystemAndReportInconsistency("Charging Strategy config file has a nestedLogit element with an unrecongized name."
//						+ " Expecting one of 'arrival' or 'departure' but found: " + element.getAttribute("name").getValue());
//			}
				}
			}


		/*
		 * This code you shouldn't need to touch, it just loops through all agents and replaces their choice models with
		 * fresh models based on the new params defined above.
		 */
			Element elem = null;
			if(shouldUpdateBeta | isFirstIteration) elem = BEAMSimTelecontrolerListener.logitParams;
			if(shouldUpdateBetaPlus) elem = BEAMSimTelecontrolerListener.logitParamsPlus;
			if(shouldUpdateBetaMinus) elem = BEAMSimTelecontrolerListener.logitParamsMinus;
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
		}else{
			log.warn("We do not calibrate parameter. Set \"shouldDoParamCalibration\" true in config to estimate parameter.");
		}
	}

	/**
	 * Return LinkedHashMap that contains charging load in kW with associated time, spatial group, site type, charger type.
	 * @param filePath: charging load profile csv file path
	 * @return hashMap: charging load hashMap
	 */
	private LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> getChargingLoadHashMap(String filePath) {
		LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap = new LinkedHashMap<>();
		TabularFileParser fileParser = new TabularFileParser();
		TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();
		fileParserConfig.setFileName(filePath);
		fileParserConfig.setDelimiterRegex(",");
		TabularFileHandler handler = new TabularFileHandler() {
			LinkedHashMap<String, Integer> headerMap;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
					}
				} else {
					String time = CSVUtil.getValue("time",row,headerMap);
					if(!time.contains(".")) time += ".0";
					if(!filePath.toLowerCase().contains("validation")){
						if(Double.valueOf(time) >= 27 && Double.valueOf(time) <= 51){
							time = String.valueOf(Double.valueOf(time)-27);
							String spatialGroup = CSVUtil.getValue("spatial.group",row,headerMap);
							String siteType = CSVUtil.getValue("site.type",row,headerMap);
							String chargerType = CSVUtil.getValue("charger.type",row,headerMap);
							String chargingLoad = CSVUtil.getValue("charging.load.in.kw",row,headerMap);
							if(hashMap.containsKey(time)){
								if(hashMap.get(time).containsKey(spatialGroup)){
									if(hashMap.get(time).get(spatialGroup).containsKey(siteType)){
										if(!hashMap.get(time).get(spatialGroup).get(siteType).containsKey(chargerType))
											hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
									}else{
										hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
										hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
									}
								}else{
									hashMap.get(time).put(spatialGroup, new LinkedHashMap<>());
									hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
									hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
								}
							}else{
								hashMap.put(time, new LinkedHashMap<>());
								hashMap.get(time).put(spatialGroup, new LinkedHashMap<>());
								hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
								hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
							}
						}
					}else{
						String spatialGroup = CSVUtil.getValue("spatial.group",row,headerMap);
						String siteType = CSVUtil.getValue("site.type",row,headerMap);
						String chargerType = CSVUtil.getValue("charger.type",row,headerMap);
						String chargingLoad = CSVUtil.getValue("charging.load.in.kw",row,headerMap);
						if(hashMap.containsKey(time)){
							if(hashMap.get(time).containsKey(spatialGroup)){
								if(hashMap.get(time).get(spatialGroup).containsKey(siteType)){
									if(!hashMap.get(time).get(spatialGroup).get(siteType).containsKey(chargerType))
										hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
								}else{
									hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
									hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
								}
							}else{
								hashMap.get(time).put(spatialGroup, new LinkedHashMap<>());
								hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
								hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
							}
						}else{
							hashMap.put(time, new LinkedHashMap<>());
							hashMap.get(time).put(spatialGroup, new LinkedHashMap<>());
							hashMap.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
							hashMap.get(time).get(spatialGroup).get(siteType).put(chargerType, chargingLoad);
						}
					}
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);
		return hashMap;
	}

	/**
	 * Merge load profile hash maps on time, spatial group, site type, and charger type
	 * @param hashMap1
	 * @param hashMap2
	 * @return mergedArray: Array list of load profile hashMap1
	 */
	private ArrayList<Double> getMergedLoadProfile(
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap1,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap2){

		LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMapMerged = new LinkedHashMap<>(hashMap1);
		ArrayList<Double> mergedArray = new ArrayList<>();

		// Get merged hash map
		for (String time : hashMap2.keySet()) {
			for (String spatialGroup : hashMap2.get(time).keySet()) {
				for (String siteType : hashMap2.get(time).get(spatialGroup).keySet()) {
					for (String chargerType : hashMap2.get(time).get(spatialGroup).get(siteType).keySet()) {
						if(hashMapMerged.containsKey(time)){
							if(hashMapMerged.get(time).containsKey(spatialGroup)){
								if(hashMapMerged.get(time).get(spatialGroup).containsKey(siteType)){
									if(!hashMapMerged.get(time).get(spatialGroup).get(siteType).containsKey(chargerType)){
										hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
									}
								}else{
									hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
									hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
								}
							}else{
								hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
							}
						}else{
							hashMapMerged.put(time, new LinkedHashMap<>());
							hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
						}
					}
				}
			}
		}

		// Get merged array
		int count = 0;
		for (String timeKey : new TreeSet<>(hashMapMerged.keySet())) {
			for (String spatialGroupKey : new TreeSet<>(hashMapMerged.get(timeKey).keySet())) {
				for (String siteTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).keySet())) {
					for (String chargerTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).keySet())) {
						mergedArray.add(count++, Double.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey)));
					}
				}
			}
		}

		return mergedArray;
	}

	/**
	 * Merge load profile hash maps on time, spatial group, site type, and charger type
	 * @param hashMap1
	 * @param hashMap2
	 * @return mergedArray: Array list of load profile hashMap1
	 */
	private ArrayList<Double> getMergedLoadProfile(FileWriter writer,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap1,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap2){

		LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMapMerged = new LinkedHashMap<>(hashMap1);
		ArrayList<Double> mergedArray = new ArrayList<>();

		// Get merged hash map
		for (String time : hashMap2.keySet()) {
			for (String spatialGroup : hashMap2.get(time).keySet()) {
				for (String siteType : hashMap2.get(time).get(spatialGroup).keySet()) {
					for (String chargerType : hashMap2.get(time).get(spatialGroup).get(siteType).keySet()) {
						if(hashMapMerged.containsKey(time)){
							if(hashMapMerged.get(time).containsKey(spatialGroup)){
								if(hashMapMerged.get(time).get(spatialGroup).containsKey(siteType)){
									if(!hashMapMerged.get(time).get(spatialGroup).get(siteType).containsKey(chargerType)){
										hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
									}
								}else{
									hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
									hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
								}
							}else{
								hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
							}
						}else{
							hashMapMerged.put(time, new LinkedHashMap<>());
							hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
						}
					}
				}
			}
		}

		// Get merged array
		int count = 0;
		for (String timeKey : new TreeSet<>(hashMapMerged.keySet())) {
			for (String spatialGroupKey : new TreeSet<>(hashMapMerged.get(timeKey).keySet())) {
				for (String siteTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).keySet())) {
					for (String chargerTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).keySet())) {
						mergedArray.add(count++, Double.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey)));
						try {
							CSVUtil.writeLine(writer, Arrays.asList(timeKey, spatialGroupKey, siteTypeKey, chargerTypeKey,
                                    String.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey)),""));
							writer.flush();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}

		return mergedArray;
	}

	private LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>> getMergedHashMap(
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap1,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap2){

		LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMapMerged = new LinkedHashMap<>(hashMap1);
		ArrayList<Double> mergedArray = new ArrayList<>();

		// Get merged hash map
		for (String time : hashMap2.keySet()) {
			for (String spatialGroup : hashMap2.get(time).keySet()) {
				for (String siteType : hashMap2.get(time).get(spatialGroup).keySet()) {
					for (String chargerType : hashMap2.get(time).get(spatialGroup).get(siteType).keySet()) {
						if(hashMapMerged.containsKey(time)){
							if(hashMapMerged.get(time).containsKey(spatialGroup)){
								if(hashMapMerged.get(time).get(spatialGroup).containsKey(siteType)){
									if(!hashMapMerged.get(time).get(spatialGroup).get(siteType).containsKey(chargerType)){
										hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
									}
								}else{
									hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
									hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
								}
							}else{
								hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
								hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
							}
						}else{
							hashMapMerged.put(time, new LinkedHashMap<>());
							hashMapMerged.get(time).put(spatialGroup, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).put(siteType, new LinkedHashMap<>());
							hashMapMerged.get(time).get(spatialGroup).get(siteType).put(chargerType, String.valueOf(0));
						}
					}
				}
			}
		}

		return hashMapMerged;
	}

	/**
	 * Initialize charging load csv file
	 */
	private FileWriter initDisaggFileWriter(int iteration, String type) {
		String fileName = EVGlobalData.data.OUTPUT_DIRECTORY + File.separator
				+ "ITERS" + File.separator + "it." + iteration + File.separator
				+"run0."+iteration + "." + type + ".disaggregateLoadProfile.csv";
		try {
			FileWriter writer = new FileWriter(fileName);
			CSVUtil.writeLine(writer, Arrays.asList("time","spatial.group","site.type","charger.type","charging.load.in.kw","num.plugged.in"));
			log.warn(fileName + " has Created and returned writer!");
			return writer;
		} catch (IOException e) {
			e.printStackTrace();
			log.warn(fileName + " has Created but we see the error!!!");
			return null;
		}
	}

	/**
	 * Load the recent charging strategies from XML
	 */
	private Element loadChargingStrategies() throws Exception {
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
	 * @return paramArr: ArrayList that contains logit parameters in double type
	 */
	private ArrayList<Double> getUtilityParams(Element rootElem){
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
