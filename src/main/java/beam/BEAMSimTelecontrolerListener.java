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
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
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
	private static Element logitParams, logitParamsTemp, logitParamsPlus, logitParamsMinus;
	private double a0=0.5f, c0=0.5f, alpha=1f, gamma= 0.4f, a,c, diffLoss, maxDiffLoss = 0, grad, residual, minResidual;

	//TODO: REMOVE UNNCESSARY VARIABLES
	private boolean
			shouldUpdateBeta = true, // true when updating objective function
			shouldUpdateBetaTemp = true,
			shouldUpdateBetaPlus,
			shouldUpdateBetaMinus,
			isFirstIteration;
	private ArrayList<Double>
			paramsList = new ArrayList<>(),
			paramsPlus = new ArrayList<>(),
			paramsMinus = new ArrayList<>(),
			paramsDelta = new ArrayList<>();
	private LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>>
			valHmObserved = new LinkedHashMap<>(),
			valHmBeta =new LinkedHashMap<>(),
			valHmBetaTemp =new LinkedHashMap<>(),
			valHmBetaPlus = new LinkedHashMap<>(),
			valHmBetaMinus = new LinkedHashMap<>(),
			hmObservedNum = new LinkedHashMap<>(),
			hmBetaNum =new LinkedHashMap<>(),
			hmBetaTempNum =new LinkedHashMap<>(),
			hmBetaPlusNum = new LinkedHashMap<>(),
			hmBetaMinusNum = new LinkedHashMap<>();
	private ArrayList<Double>
			loadProfileBeta = new ArrayList<>(),
			valListBetaTemp = new ArrayList<>(),
			valListBetaPlus = new ArrayList<>(),
			valListBetaMinus = new ArrayList<>(),
			valListObserved = new ArrayList<>(),
			listBetaNum = new ArrayList<>(),
			listBetaNumTemp = new ArrayList<>(),
			listBetaPlusNum = new ArrayList<>(),
			listBetaMinusNum = new ArrayList<>(),
			listObservedNum = new ArrayList<>();

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

		if(EVGlobalData.data.SHOULD_CALIBRATE_PARAMS){

		/*
		 * Here is where you would either initialize the algorithm (if this is iteration 0) or do the update.
		 *
		 * I.e. for initialization, read in the observed loads and the starting place for the parameters. For updates,
		 * read in the simulated loads, calculate the objective function, generate a new set of parameters to simulate
		 * (either from the random draw or from the update step).
		 */
			int iterPeriod = EVGlobalData.data.ITER_SET_LENGTH;
			String valueType = EVGlobalData.data.VALIDATION_VALUE_TYPE; // "chargingload" or "pluggednum"
			isFirstIteration		= (event.getIteration() == 0);
			shouldUpdateBetaPlus 	= (event.getIteration() % iterPeriod == 1);
			shouldUpdateBetaMinus 	= (event.getIteration() % iterPeriod == 2);
			shouldUpdateBetaTemp	= (iterPeriod>=4? (event.getIteration() % iterPeriod == 3) : ((event.getIteration()%iterPeriod == 0) && !isFirstIteration));
			if(iterPeriod < 3) throw new WrongIterationPeriodException("Iteration set period can't be less than 3!");

			if(isFirstIteration){
				// If we resume the calibration
				if(EVGlobalData.data.SHOULD_RESUME_CALIBRATION){
					// Initialize the parameters from the backup file
					if(new File(EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH).exists()){
						try {
							logitParams = loadChargingStrategies(EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}else{
						// load parameters from logit model XML
						try {
							logitParams = loadChargingStrategies();
						} catch (Exception e) {
							e.printStackTrace();
						}
					}

				}else{
					// load parameters from logit model XML
					try {
						logitParams = loadChargingStrategies();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				paramsList 			= getUtilityParams(logitParams);
				valHmObserved 		= getHashMapFromFile(EVGlobalData.data.CHARGING_LOAD_VALIDATION_FILEPATH,valueType);
				logitParamsTemp 	= (Element) logitParams.clone(); // Temporary logit params

				// This is for test
//				backupUpdatedParams((Element)logitParams.clone());
			}else{
				if(shouldUpdateBetaPlus){
					// Update algorithmic params
					log.info("Math.ceil(event.getIteration()/iterPeriod): " + Math.ceil((double)event.getIteration()/(double)iterPeriod));
					a 	= a0 / (Math.pow(Math.ceil((double)event.getIteration()/(double)iterPeriod),alpha));
					c 	= c0 / (Math.pow(Math.ceil((double)event.getIteration()/(double)iterPeriod),gamma));

					// Load and merge observed & simulated data
					String prevLoadFile = EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+
							EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator +
							"ITERS" + File.separator +
							"it." + (event.getIteration()-1) + File.separator +
							"run0."+ (event.getIteration()-1) + ".disaggregateLoadProfile.csv";
					valHmBeta = getHashMapFromFile(prevLoadFile,valueType);
					valListBetaTemp = getMergedArray(initDisaggFileWriter(event.getIteration(),"beta"), valHmBeta, valHmObserved, valueType);
					valListObserved = getMergedArray(initDisaggFileWriter(event.getIteration(),"observed"), valHmObserved, valHmBeta, valueType);

					// Calculate residuals
					residual = 0;
					for(int i = 0; i< valListBetaTemp.size(); i++){
						residual += Math.pow(valListObserved.get(i)- valListBetaTemp.get(i),2);
					}
					log.info("residual (observed - modeled)^2 = " + residual);
					if(event.getIteration() == 1) minResidual = residual;
					log.info("min Residual: " + minResidual);

					// Update parameter if we have an improvement in residuals
					if(event.getIteration() >= iterPeriod && residual <= minResidual){ // Check if we have an improvement with the updated parameter
						log.info("origin params: " + getUtilityParams(logitParams));

						// Update the parameter if the perturbation made an improvement
						minResidual = residual; // update the threshold
						logitParams = (Element) logitParamsTemp.clone(); // update the parameter
						log.info("current params: " + getUtilityParams(logitParams));
						log.info("YES, Parameters are updated.");

						// Write XML of the updated params
						backupUpdatedParams((Element)logitParams.clone());
					}else{
						log.info("current params: " + getUtilityParams(logitParams));
						log.warn("NO, Parameters are not updated.");
					}

					// Re-initialize params
					logitParamsTemp 	= (Element) logitParams.clone(); 	 // Temporary logit params
					logitParamsPlus 	= (Element) logitParamsTemp.clone(); // Positive perturbed logit params
					logitParamsMinus 	= (Element) logitParamsTemp.clone(); // Negative perturbed logit params
				}

				// Update Logit params
				else if(shouldUpdateBetaTemp){
					String prevLoadFile 		= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator
							+ "it." + (event.getIteration()-3) + File.separator + "run0."+ (event.getIteration()-3) + ".disaggregateLoadProfile.csv";
					String betaPlusLoadFile 	= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator
							+ "it." + (event.getIteration()-2) + File.separator + "run0."+ (event.getIteration()-2) + ".disaggregateLoadProfile.csv";
					String betaMinusLoadFile 	= EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH +File.separator+ EVGlobalData.data.OUTPUT_DIRECTORY_NAME + File.separator + "ITERS" + File.separator
							+ "it." + (event.getIteration()-1) + File.separator + "run0."+ (event.getIteration()-1) + ".disaggregateLoadProfile.csv";

					// Merge simulated data first
					valHmBeta = getMergedHashMap(getHashMapFromFile(prevLoadFile,valueType), getHashMapFromFile(betaPlusLoadFile,valueType));
					valHmBeta = getMergedHashMap(valHmBeta, getHashMapFromFile(betaMinusLoadFile,valueType));
					valHmBetaPlus = getMergedHashMap(getHashMapFromFile(betaPlusLoadFile,valueType), valHmBeta);
					valHmBetaMinus = getMergedHashMap(getHashMapFromFile(betaMinusLoadFile,valueType), valHmBeta);

					// Get target values (charging load / plugged-in num)
					valListBetaTemp = getMergedArray(initDisaggFileWriter(event.getIteration(),"beta"), valHmBeta, valHmObserved,valueType);
					valListBetaPlus = getMergedArray(initDisaggFileWriter(event.getIteration(),"betaPlus"), valHmBetaPlus, valHmObserved,valueType);
					valListBetaMinus = getMergedArray(initDisaggFileWriter(event.getIteration(),"betaMinus"), valHmBetaMinus, valHmObserved,valueType);
					valListObserved = getMergedArray(initDisaggFileWriter(event.getIteration(),"observed"), valHmObserved, valHmBeta,valueType);

					// Update gradient
					diffLoss = 0;
					for(int i = 0; i< valListBetaPlus.size(); i++){
						try{
							// Get loss function
							diffLoss += Math.pow(valListObserved.get(i) - valListBetaPlus.get(i),2)
									- Math.pow(valListObserved.get(i) - valListBetaMinus.get(i),2);
						}catch(Exception e){break;}
					}
					if(Math.abs(diffLoss) >= maxDiffLoss) maxDiffLoss = Math.abs(diffLoss);
					log.info("HERE!!!!! diffLoss: " + diffLoss);
					log.info("HERE!!!!! max diffLoss: " + maxDiffLoss);
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
			}else if(shouldUpdateBetaMinus) itr = (BEAMSimTelecontrolerListener.logitParamsMinus.getChildren()).iterator();
			else if(shouldUpdateBetaTemp)itr = (BEAMSimTelecontrolerListener.logitParamsTemp.getChildren()).iterator();

			if(!isFirstIteration && itr != null){
				while (itr.hasNext()) { //TODO: arrival/departure
					Element element = (Element) itr.next();
					Iterator itrElem = element.getChildren().iterator();
					paramIndex = 0;
					while (itrElem.hasNext()) { //TODO: yescharge/nocharge
						Element subElement = ((Element) itrElem.next());
						if(subElement.getName().toLowerCase().equals("nestedlogit")){
							for (Object obj1 : subElement.getChildren()) { //TODO: genericSitePlug...
								Element childElement = ((Element) obj1);
								if (childElement.getName().toLowerCase().equals("nestedlogit")) {
									for (Object obj2 : (childElement.getChild("utility")).getChildren()) { //TODO: parameters
										Element utilityElement = ((Element) obj2);
										if (utilityElement.getName().equals("param")) {
											// Only update intercept
											if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
												if (shouldUpdateBetaPlus) {
													boolean delta = StdRandom.bernoulli();
													paramsDelta.add(paramIndex++, (double) ((delta ? 1 : 0) * 2 - 1));
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * ((delta ? 1 : 0) * 2 - 1)));
												} else if (shouldUpdateBetaMinus) {
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * paramsDelta.get(paramIndex++)));
												} else if (shouldUpdateBetaTemp) {
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													grad = (diffLoss / maxDiffLoss)*paramMaxConst / (2 * c * paramsDelta.get(paramIndex++));
//													grad = (diffLoss) / (2 * c * paramsDelta.get(paramIndex++));
													log.info("grad: " + grad);
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
											if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
												if (shouldUpdateBetaPlus) {
													boolean delta = StdRandom.bernoulli();
													paramsDelta.add(paramIndex++, (double) ((delta ? 1 : 0) * 2 - 1));
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * ((delta ? 1 : 0) * 2 - 1)));
												} else if (shouldUpdateBetaMinus) {
													utilityElement.setText(String.valueOf(Double.valueOf(utilityElement.getText()) + c * paramsDelta.get(paramIndex++)));
												} else if (shouldUpdateBetaTemp) {
													log.info("(param update) attribute: " + utilityElement.getAttributeValue("name") + " origin param: " + utilityElement.getText());
													grad = (diffLoss / maxDiffLoss)*paramMaxConst/ (2 * c * paramsDelta.get(paramIndex++));
//													grad = (diffLoss)/ (2 * c * paramsDelta.get(paramIndex++));
													log.info("grad: " + grad);
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
					}
				}
			}


		/*
		 * This code you shouldn't need to touch, it just loops through all agents and replaces their choice models with
		 * fresh models based on the new params defined above.
		 */
			Element elem = null;
			if(isFirstIteration) elem = (Element) BEAMSimTelecontrolerListener.logitParams.clone();
			else if(shouldUpdateBetaPlus) elem = (Element) BEAMSimTelecontrolerListener.logitParamsPlus.clone();
			else if(shouldUpdateBetaMinus) elem = (Element) BEAMSimTelecontrolerListener.logitParamsMinus.clone();
			else if(shouldUpdateBetaTemp) elem = (Element) BEAMSimTelecontrolerListener.logitParamsTemp.clone();
			if(elem != null){
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
				log.warn("Run another iteration without updating parameters to get the simulation result converges..");
			}
		}else{
			log.warn("We do not calibrate parameter. Set \"shouldDoParamCalibration\" true in config to estimate parameter.");
		}
	}

	/**
	 * Write updated params in the backup file
	 * @param element
	 */
	private void backupUpdatedParams(Element element) {
		// Detach element from parent
		element.detach();

		// Set root element
		Element strategies = new Element("strategies");
		Document doc = new Document(strategies);
		doc.setRootElement(strategies);

		// Add strategy
		Element strategy = new Element("strategy");
		strategy.addContent(element);
		doc.getRootElement().addContent(strategy);

		XMLOutputter xmlOutput = new XMLOutputter();
		xmlOutput.setFormat(Format.getPrettyFormat());
		try {
			xmlOutput.output(doc, new FileWriter(EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH));
			log.info("updated params are saved in the backup file: " + EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Return LinkedHashMap that contains charging load in kW with associated time, spatial group, site type, charger type.
	 * @param filePath: charging load profile csv file path
	 * @return hashMap: charging load hashMap
	 */
	private LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> getHashMapFromFile(String filePath) {
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
	 * Return LinkedHashMap that contains charging load/Plugged-in num in kW with associated time, spatial group, site type, charger type.
	 * @param filePath
	 * @param valueType
	 * @return
	 */
	private LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> getHashMapFromFile(String filePath, String valueType) {
		LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap = new LinkedHashMap<>();
		TabularFileParser fileParser = new TabularFileParser();
		TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();
		fileParserConfig.setFileName(filePath);
		fileParserConfig.setDelimiterRegex(",");
		String valueColumn = (valueType.equals("chargingload")?"charging.load.in.kw":"num.plugged.in");
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
					if(!filePath.toLowerCase().contains("validation")){ // simulated files
						if(Double.valueOf(time) >= 27 && Double.valueOf(time) <= 51){
							time = String.valueOf(Double.valueOf(time)-27);
							String spatialGroup = CSVUtil.getValue("spatial.group",row,headerMap);
							String siteType = CSVUtil.getValue("site.type",row,headerMap);
							String chargerType = CSVUtil.getValue("charger.type",row,headerMap);
							String chargingLoad = CSVUtil.getValue(valueColumn,row,headerMap);
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
					}else{ // validation file
						String spatialGroup = CSVUtil.getValue("spatial.group",row,headerMap);
						String siteType = CSVUtil.getValue("site.type",row,headerMap);
						String chargerType = CSVUtil.getValue("charger.type",row,headerMap);
						String chargingLoad = CSVUtil.getValue(valueColumn,row,headerMap);
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
	private ArrayList<Double> getMergedArray(FileWriter writer,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap1,
			LinkedHashMap<String, LinkedHashMap<String,LinkedHashMap<String, LinkedHashMap<String, String>>>> hashMap2, String type){

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

		log.info("keySet: " + hashMapMerged.keySet());
		// Get merged array
		int count = 0;
		for (String timeKey : (hashMapMerged.keySet())) {
			for (String spatialGroupKey : new TreeSet<>(hashMapMerged.get(timeKey).keySet())) {
				for (String siteTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).keySet())) {
					for (String chargerTypeKey : new TreeSet<>(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).keySet())) {
						mergedArray.add(count++, Double.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey)));
						try {
							switch (type) {
								case "chargingload":
									CSVUtil.writeLine(writer, Arrays.asList(timeKey, spatialGroupKey, siteTypeKey, chargerTypeKey,
											String.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey)), ""));

									break;
								case "pluggednum":
									CSVUtil.writeLine(writer, Arrays.asList(timeKey, spatialGroupKey, siteTypeKey, chargerTypeKey,
											"", String.valueOf(hashMapMerged.get(timeKey).get(spatialGroupKey).get(siteTypeKey).get(chargerTypeKey))));
									break;
								default:
									throw new IllegalArgumentException("Value type is wrong! The value type must be either chargingload or pluggednum");
							}
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

	/**
	 * Merge two hash maps
	 * @param hashMap1
	 * @param hashMap2
	 * @return
	 */
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
		return loadChargingStrategies(EVGlobalData.data.CHARGING_STRATEGIES_FILEPATH);
	}

	/**
	 * Load the recent charging strategies from XML
	 */
	private Element loadChargingStrategies(String filePath) throws Exception {
		SAXBuilder saxBuilder = new SAXBuilder();
		FileInputStream stream;
		Document document = null;
		try {
			stream = new FileInputStream(filePath);
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

		Iterator itr = (rootElem.getChildren()).iterator();
		while (itr != null && itr.hasNext()) { //TODO: arrival/departure
			Element element = (Element) itr.next();
			Iterator itrElem = element.getChildren().iterator();
			while (itrElem.hasNext()) { //TODO: yescharge/nocharge
				Element subElement = ((Element) itrElem.next());
				if(subElement.getName().toLowerCase().equals("nestedlogit")){
					for (Object obj1 : subElement.getChildren()) { //TODO: genericSitePlug...
						Element childElement = ((Element) obj1);
						if (childElement.getName().toLowerCase().equals("nestedlogit")) {
							for (Object obj2 : (childElement.getChild("utility")).getChildren()) { //TODO: parameters
								Element utilityElement = ((Element) obj2);
								if (utilityElement.getName().equals("param")) {
									if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
										log.info("parameter: " + utilityElement.getAttributeValue("name") + "value: " + utilityElement.getText());
										paramArr.add(Double.valueOf(utilityElement.getText()));
									}
								}
							}
						} else if (childElement.getName().toLowerCase().equals("utility")) {
							for (Object o : childElement.getChildren()) {
								Element utilityElement = (Element) o;
								if (utilityElement.getName().equals("param")) {
									// Only update intercept
									if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
										if (utilityElement.getAttributeValue("name").toLowerCase().equals("intercept")) {
											log.info("parameter: " + utilityElement.getAttributeValue("name") + "value: " + utilityElement.getText());
											paramArr.add(Double.valueOf(utilityElement.getText()));
										}
									}
								}
							}
						}
					}
				}
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

	/**
	 * Customized exception here
	 */
	public class WrongIterationPeriodException extends RuntimeException{
		public WrongIterationPeriodException(String message) {
			super(message);
		}

	}
}

