package beam;

/**
 * example input parameters
 * ...\MATSimPEV
 * /model-inputs/development/config_BA_secondary_qsim.xml
 * ...\output
 */

import beam.controller.corelisteners.EventsHandlingImpl;
import beam.sim.BeamMobsim;
import beam.sim.LinkAttributeLoader;
import beam.sim.traveltime.*;
import beam.transEnergySim.events.ChargingEventManager;
import com.google.inject.Provider;

import org.geotools.referencing.CRS;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.qsim.interfaces.NetsimNetwork;
import org.matsim.core.network.LinkQuadTree;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.scenario.ScenarioUtils;

import beam.analysis.ChargingLoadProfile;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.controller.EVController;
import beam.events.BeamEventHandlers;
import beam.events.EventLogger;
import beam.parking.lib.DebugLib;
import beam.charging.vehicle.ParseVehicleTypes;
import beam.replanning.ChargingStrategyManager;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.api.Vehicle;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;
import beam.transEnergySim.vehicles.impl.BatteryElectricVehicleImpl;
import beam.transEnergySim.vehicles.impl.PHEV;
import org.opengis.referencing.FactoryException;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public class EVSimTeleController {

	public static void main(String[] args) {
		EVGlobalData.simulationStaticVariableInitializer();
		EVGlobalData.data.INPUT_DIRECTORY_BASE_PATH = args[0];
		EVGlobalData.data.CONFIG_RELATIVE_PATH = args[1];
		EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH = args[2];

		EVSimTeleController evSimTeleController = new EVSimTeleController();
		evSimTeleController.init();

		evSimTeleController.startSimulation();
	}

	public void init() {
		EVGlobalData.data.config = setupConfig();
		MatsimRandom.reset(EVGlobalData.data.config.global().getRandomSeed());
		EVGlobalData.data.rand = MatsimRandom.getLocalInstance();
		initControler();
		initModeRouters();
	}

	public void initControler() {
		Scenario scenario = ScenarioUtils.loadScenario(EVGlobalData.data.config);
		EVController controler = new EVController(scenario);
		EVGlobalData.data.controler = controler;
	}

	protected void initModeRouters() {
//		EVGlobalData.data.modeRouterMapping.put(EVGlobalData.data.TELEPORTED_TRANSPORATION_MODE, BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES, BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put("pt", BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put("car", BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put("ride", BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put("bike", BeamRouterR5.class);
		EVGlobalData.data.modeRouterMapping.put("undefined", BeamRouterR5.class);
	}

	public void startSimulation() {
		prepareSimulation(EVGlobalData.data.controler);
		EVGlobalData.data.testingHooks.addTestingHooksBeforeStartOfSimulation();
		EVGlobalData.data.controler.run();
	}

	public void prepareSimulation(final EVController controler) {

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bindMobsim().toProvider(new Provider<Mobsim>() {
					@Override
					public Mobsim get() {
					   return new BeamMobsim();
//						EVGlobalData.data.qsim = (QSim) AdaptedQSimUtils.createDefaultQSim(controler.getScenario(), controler.getEvents());
//
//						Field f;
//						try {
//							f = EVGlobalData.data.qsim.getClass().getDeclaredField("mobsimEngines");
//							f.setAccessible(true);
//							Collection<MobsimEngine> mobsimEngines = (Collection<MobsimEngine>) f.get(EVGlobalData.data.qsim);
//
//							for (MobsimEngine mobilityEngine : mobsimEngines) {
//								if (mobilityEngine instanceof AdaptedTeleportationEngine) {
//									EVGlobalData.data.qsim.addDepartureHandler((AdaptedTeleportationEngine) mobilityEngine);
//									// the MobSimEngine and DepartureHandler are
//									// working together. Therefore we need to
//									// add the same
//									// handler at the end here.
//								}
//
//							}
//						} catch (NoSuchFieldException e) {
//							e.printStackTrace();
//						} catch (SecurityException e) {
//							e.printStackTrace();
//						} // NoSuchFieldException
//						catch (IllegalArgumentException e) {
//							e.printStackTrace();
//						} catch (IllegalAccessException e) {
//							e.printStackTrace();
//						}
//
//						// TODO: figure out, if this can be packed into
//						// listeners in abstract class and avoid static
//						// reference!
//						// e.g. use injection
////						EVGlobalData.data.chargingEventManagerImp.addEngine(EVGlobalData.data.beamMobsim);
//
//						return EVGlobalData.data.qsim;
					}
				});
			}
		});

		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(EventsHandling.class).to(EventsHandlingImpl.class);
				bind(BeamEventHandlers.class).asEagerSingleton();
				bind(ChargingLoadProfile.class).asEagerSingleton();
				bind(RoutingModule.class).toProvider(BeamRouterR5Provider.class);
				bind(LeastCostPathCalculatorFactory.class).to(BeamLeastCostPathCalculatorFactory.class);
				bind(LeastCostPathCalculator.class).to(BeamLeastCostPathCalculator.class);
				bind(NetsimNetwork.class).to(BeamNetsimNetwork.class);
				// addEventHandlerBinding().toInstance(observer);
				// bind(MySimulationObserver.class).toInstance(observer);

				for (String mode : EVGlobalData.data.modeRouterMapping.keySet()) {
					addRoutingModuleBinding(mode).to(EVGlobalData.data.modeRouterMapping.get(mode));
				}

			}
		});

		loadRouter();
		attachLinkTree();
		loadLinkAttributes();
		avoidRoutingDuringInitialization(controler);

		ChargingStrategyManager.data.loadChargingStrategies();

		convertCarLegsToEVLegsAndAddReplannable(controler);

		initializeChargingInfrastructure(controler);

		initializeVehicleFleet(controler);
		//scheduleGlobalActions();

		controler.addControlerListener(new AddReplanning());
		controler.addControlerListener(new BEAMSimTelecontrolerListener());
		controler.addControlerListener(new ShutdownListener() {
			
			@Override
			public void notifyShutdown(ShutdownEvent event) {
				if (event.isUnexpected()){
					EVGlobalData.data.testingHooks.unexpectedShutDown=true;
				}
				
			}
		});

//		integrateChargingEventManagerIntoSimulation(controler);
//		new EVScoreEventGenerator(controler).setInternalRangeAnxityEventHandler(new LogRangeAnxityScoringEventsAtEndOfDay());
//		new EVScoreAccumulator(controler);

		setLastActivityEndTimeToBeginActivityEndTime(controler);
	}

	private void setLastActivityEndTimeToBeginActivityEndTime(final EVController controler) {
		Network network = EVGlobalData.data.controler.getScenario().getNetwork();
		for (Person person : controler.getScenario().getPopulation().getPersons().values()) {
			Double firstActivityEndSecondOfDay=-1.0;
			Integer firstActivityEndDay=-1;
			Double nextToLastActivityEndTime=-1.0;
			List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
			int i = 0;
			for (i = 0; i < planElements.size()-1; i++) {
				PlanElement pe = planElements.get(i);
				if (pe instanceof Activity) {
					Activity act = (Activity) pe;
					if(firstActivityEndSecondOfDay<0){
						firstActivityEndSecondOfDay = act.getEndTime() % (86400);
						firstActivityEndDay = (new Double(act.getEndTime() / (86400))).intValue();
					}
					if(act.getEndTime() > 0.0){
						nextToLastActivityEndTime = act.getEndTime();
					}
				}
			}
			PlanElement pe = planElements.get(i);
			if (!(pe instanceof Activity)) {
				DebugLib.stopSystemAndReportInconsistency("Expected Activity in final plan element # "+i+" of person "+person.getId());
			}
			Activity act = (Activity) pe;
			if(act.getEndTime()<0){
				double lastActivityBeginSecondOfDay = nextToLastActivityEndTime % (86400);
				int lastActivityBeginDay = (new Double(nextToLastActivityEndTime / (86400))).intValue();
				if(firstActivityEndDay == lastActivityBeginDay){
					act.setEndTime((firstActivityEndDay+1)*86400 + firstActivityEndSecondOfDay);
				}else if(lastActivityBeginSecondOfDay < firstActivityEndSecondOfDay){
					act.setEndTime(lastActivityBeginDay*86400 + firstActivityEndSecondOfDay);
				}else{
					act.setEndTime((lastActivityBeginDay+1)*86400 + firstActivityEndSecondOfDay);
				}
			}
		}
	}

	private void avoidRoutingDuringInitialization(final EVController controler) {
		Network network = EVGlobalData.data.controler.getScenario().getNetwork();
		for (Person person : controler.getScenario().getPopulation().getPersons().values()) {
			List<PlanElement> planElements = person.getSelectedPlan().getPlanElements();
			for (int i = 0; i < planElements.size(); i++) {
				PlanElement pe = planElements.get(i);
				if (pe instanceof Activity) {
					Activity act = (Activity) pe;
                    Link link = NetworkUtils.getNearestLink(network, act.getCoord());
                    if (link != null) act.setLinkId(link.getId());
				}
			}

			for (int i = 0; i < planElements.size(); i++) {
				PlanElement pe = planElements.get(i);
				if (pe instanceof Leg) {
					Leg leg = (Leg) pe;
					Link prevActLink = network.getLinks().get(((Activity) planElements.get(i - 1)).getLinkId());
					Link nextActLink = network.getLinks().get(((Activity) planElements.get(i + 1)).getLinkId());

					if (leg.getRoute() == null) {
						LinkNetworkRouteImpl route = new LinkNetworkRouteImpl(prevActLink.getId(), nextActLink.getId());
						double distance = NetworkUtils.getEuclideanDistance(prevActLink.getCoord(), nextActLink.getCoord());
						route.setDistance(distance);
						leg.setRoute(route);
					}
				}
			}
		}
	}

	public void attachLinkTree() {
		Network network = EVGlobalData.data.controler.getScenario().getNetwork();
		// buildLinkQuadTree
		Field treeField;
		try {
			Method m = network.getClass().getDeclaredMethod("buildLinkQuadTree", null);
			m.setAccessible(true);
			m.invoke(network, null);
			treeField = network.getClass().getDeclaredField("linkQuadTree");
			treeField.setAccessible(true);
			EVGlobalData.data.linkQuadTree = (LinkQuadTree) treeField.get(network);
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException | NoSuchMethodException
				| InvocationTargetException e) {
			e.printStackTrace();
		}
	}
	public void loadLinkAttributes(){
		EVGlobalData.data.linkAttributes = LinkAttributeLoader.loadLinkAttributes();

	}

	protected void loadRouter(){
//		EVGlobalData.data.router = new BeamRouterImpl(EVGlobalData.data.TRAVEL_TIME_FILEPATH, EVGlobalData.data.ROUTER_CACHE_READ_FILEPATH);
		EVGlobalData.data.router = new BeamRouterR5();
	}

	public static void scheduleGlobalActions() {
//		EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.timeMarkingNewDay, EVGlobalData.data.globalActions, "handleDayTracking");
		EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.timeMarkingNewDay, EVGlobalData.data.globalActions, "handleDayTracking");
//		EVGlobalData.data.scheduler.addCallBackMethod(0.0, EVGlobalData.data.globalActions, "printRand");
//		EVGlobalData.data.scheduler.addCallBackMethod(100*3600.0, EVGlobalData.data.globalActions, "pauseForHour");
	}

	public Config setupConfig() {
		String inputDirectory = EVGlobalData.data.INPUT_DIRECTORY_BASE_PATH + File.separator;
		Config config = ConfigUtils.loadConfig(inputDirectory + EVGlobalData.data.CONFIG_RELATIVE_PATH);
		config.setParam("network", "inputNetworkFile", inputDirectory + config.getModule("network").getParams().get("inputNetworkFile"));
		config.setParam("plans", "inputPlansFile", inputDirectory + config.getModule("plans").getParams().get("inputPlansFile"));
		String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
		ConfigGroup evModule = config.getModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_MODULE_NAME);
		EVGlobalData.data.OUTPUT_DIRECTORY_NAME = evModule.getValue(EVGlobalData.data.SIMULATION_NAME) + "_" + timestamp;
		EVGlobalData.data.OUTPUT_DIRECTORY = new File(
				EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH + File.separator + EVGlobalData.data.OUTPUT_DIRECTORY_NAME);
		EVGlobalData.data.OUTPUT_DIRECTORY.mkdir();
		EVGlobalData.data.CHARGING_SITE_POLICIES_FILEPATH = inputDirectory + evModule.getValue("chargingSitePoliciesFile");
		EVGlobalData.data.CHARGING_NETWORK_OPERATORS_FILEPATH = inputDirectory + evModule.getValue("chargingNetworkOperatorsFile");
		EVGlobalData.data.CHARGING_PLUG_TYPES_FILEPATH = inputDirectory + evModule.getValue("chargingPlugTypesFile");
		EVGlobalData.data.CHARGING_SITES_FILEPATH = inputDirectory + evModule.getValue("chargingSitesFile");
		EVGlobalData.data.CHARGING_POINTS_FILEPATH = inputDirectory + evModule.getValue("chargingPointsFile");
		EVGlobalData.data.CHARGING_STRATEGIES_FILEPATH = (evModule.getValue("chargingStrategiesFile").substring(0,1).equals("/")) ? evModule.getValue("chargingStrategiesFile") : inputDirectory + evModule.getValue("chargingStrategiesFile");
		EVGlobalData.data.NETWORK_FILEPATH = (evModule.getValue("networkFilePath").substring(0,1).equals("/")) ? evModule.getValue("networkFilePath") : inputDirectory + evModule.getValue("networkFilePath");
		if (evModule.getValue("numberOfThreads") != null)
			EVGlobalData.data.NUM_THREADS = Integer.valueOf(evModule.getValue("numberOfThreads"));
		if (evModule.getValue("chargingStrategiesValidationFile") != null)
			EVGlobalData.data.CHARGING_LOAD_VALIDATION_FILEPATH = inputDirectory + evModule.getValue("chargingStrategiesValidationFile");
		if (evModule.getValue("updatedChargingStrategiesFile") != null)
			EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_FILEPATH = inputDirectory + evModule.getValue("updatedChargingStrategiesFile");
		if (evModule.getValue("updatedChargingStrategiesBackupFile") != null)
			EVGlobalData.data.UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH = inputDirectory + evModule.getValue("updatedChargingStrategiesBackupFile");
		if (evModule.getValue("iterationSetLength") != null)
			EVGlobalData.data.ITER_SET_LENGTH = Integer.valueOf(evModule.getValue("iterationSetLength"));
		else EVGlobalData.data.ITER_SET_LENGTH = 4;
		if (evModule.getValue("validationValueType") != null)
			EVGlobalData.data.VALIDATION_VALUE_TYPE = evModule.getValue("validationValueType");
		else EVGlobalData.data.VALIDATION_VALUE_TYPE = "chargingload";
		EVGlobalData.data.VEHICLE_TYPES_FILEPATH = inputDirectory + evModule.getValue("vehicleTypesFile");
		EVGlobalData.data.PERSON_VEHICLE_TYPES_FILEPATH = inputDirectory + evModule.getValue("personVehicleTypesFile");
		EVGlobalData.data.TRAVEL_TIME_FILEPATH = inputDirectory + evModule.getValue("travelTimeFile");
		EVGlobalData.data.LINK_ATTRIBUTE_FILEPATH = inputDirectory + evModule.getValue("linkAttributesFile");
		EVGlobalData.data.ROUTER_CACHE_READ_FILEPATH = ((new File(evModule.getValue("routerCacheFileRead"))).isAbsolute())
				? evModule.getValue("routerCacheFileRead") : inputDirectory + evModule.getValue("routerCacheFileRead");
		if (!evModule.getValue("routerCacheFileWrite").trim().equals("")) {
			EVGlobalData.data.ROUTER_CACHE_WRITE_FILEPATH = ((new File(evModule.getValue("routerCacheFileWrite"))).isAbsolute())
					? evModule.getValue("routerCacheFileWrite") : inputDirectory + evModule.getValue("routerCacheFileWrite");
		}
		EVGlobalData.data.INITIAL_SEARCH_RADIUS = Double.parseDouble(evModule.getValue("initialSearchRadius"));
		EVGlobalData.data.SEARCH_RADIUS_INCREMENTATION_FACTOR = Double.parseDouble(evModule.getValue("searchRadiusIncrementationFactor"));
		EVGlobalData.data.CHARGING_STATION_USE_CONSTANT_OVERHEAD_TIME = Double
				.parseDouble(evModule.getValue("chargingStationUseConstantOverHeadTime"));

		EVGlobalData.data.AVERAGE_WALK_SPEED_TO_CHARGING_STATION = Double.parseDouble(evModule.getValue("averageWalkSpeedToChargingStation"));
		EVGlobalData.data.WALK_DISTANCE_ADJUSTMENT_FACTOR = Double.parseDouble(evModule.getValue("walkDistanceAdjustmentFactor"));

		EVGlobalData.data.OVERHEAD_TRAVEL_TIME_FACTOR_WHEN_RUNNING_OUT_OF_BATTERY = Double
				.parseDouble(evModule.getValue("overheadTravelTimeFactorWhenRunningOutOfBattery"));

		EVGlobalData.data.CHARGING_SCORE_SCALING_FACTOR = Double.parseDouble(evModule.getValue("chargingScoreScalingFactor"));

		EVGlobalData.data.EN_ROUTE_SEARCH_DISTANCE = Double.parseDouble(evModule.getValue("enRouteSearchDistance"));

		EVGlobalData.data.EQUALITY_EPSILON = Double.parseDouble(evModule.getValue("equalityEpsilon"));

		EVGlobalData.data.TIME_TO_ENGAGE_NEXT_FAST_CHARGING_SESSION = Double.parseDouble(evModule.getValue("timeToEngageNextFastChargingStation"));

		EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS = Double.parseDouble(evModule.getValue("rangeAnxitySamplingIntervalInSeconds"));

		EVGlobalData.data.IS_DEBUG_MODE = Boolean.parseBoolean(evModule.getValue("isDebugMode"));

		EVGlobalData.data.SHOULD_CALIBRATE_PARAMS = evModule.getValue("shouldCalibrateParams") != null && Boolean.parseBoolean(evModule.getValue("shouldCalibrateParams"));
		EVGlobalData.data.SHOULD_CALIBRATE_SITES = evModule.getValue("shouldCalibrateSites") != null && Boolean.parseBoolean(evModule.getValue("shouldCalibrateSites"));
		EVGlobalData.data.SHOULD_RESUME_CALIBRATION = evModule.getValue("shouldResumeCalibration") != null && Boolean.parseBoolean(evModule.getValue("shouldResumeCalibration"));

		EVGlobalData.data.BETA_CHARGING_COST = Double.parseDouble(evModule.getValue("betaChargingCost"));
		EVGlobalData.data.BETA_PARKING_COST = Double.parseDouble(evModule.getValue("betaParkingCost"));
		EVGlobalData.data.BETA_PARKING_WALK_TIME = Double.parseDouble(evModule.getValue("betaParkingWalkTime"));
		EVGlobalData.data.BETA_LEG_TRAVEL_TIME = Double.parseDouble(evModule.getValue("betaLegTravelTime"));
		EVGlobalData.data.OVERHEAD_SCORE_PLUG_CHANGE = Double.parseDouble(evModule.getValue("overheadScorePlugChange"));

		// optional parameters
		if (evModule.getValue("dumpPlansAtEndOfRun") != null) {
			EVGlobalData.data.DUMP_PLANS_AT_END_OF_RUN = Boolean.parseBoolean(evModule.getValue("dumpPlansAtEndOfRun"));
		}

		if (evModule.getValue("eventsFileOutputFormats") != null) {
			EVGlobalData.data.EVENTS_FILE_OUTPUT_FORMATS = evModule.getValue("eventsFileOutputFormats");
		}

		if (evModule.getValue("chargingPointReferenceUtilizationDataFile") != null) {
			EVGlobalData.data.CHARGING_POINT_REFERENCE_UTILIZATION_DATA_FILE = evModule.getValue("chargingPointReferenceUtilizationDataFile");
		}

		if (evModule.getValue("dumpPlanCsv") != null) {
			EVGlobalData.data.DUMP_PLAN_CSV = Boolean.parseBoolean(evModule.getValue("dumpPlanCsv"));
		}

		if (evModule.getValue("dumpPlanCsvInterval") != null) {
			EVGlobalData.data.DUMP_PLAN_CSV_INTERVAL = Integer.parseInt(evModule.getValue("dumpPlanCsvInterval"));
		}

		if (evModule.getValue("maxNumberOfEVDailyPlansInMemory") != null) {
			EVGlobalData.data.MAX_NUMBER_OF_EV_DAILY_PLANS_IN_MEMORY = Integer.parseInt(evModule.getValue("maxNumberOfEVDailyPlansInMemory"));
		}

		if (evModule.getValue("selectedEVDailyPlansFileName") != null) {
			EVGlobalData.data.SELECTED_EV_DAILY_PLANS_FILE_NAME = evModule.getValue("selectedEVDailyPlansFileName");
		}
		if (evModule.getParams().get("routerCacheInMemoryTripLimit") != null) {
			EVGlobalData.data.ROUTER_CACHE_IN_MEMORY_TRIP_LIMIT = Integer.parseInt(evModule.getParams().get("routerCacheInMemoryTripLimit"));
		} else {
			EVGlobalData.data.ROUTER_CACHE_IN_MEMORY_TRIP_LIMIT = 10000;
		}

		config.setParam("controler", "outputDirectory", EVGlobalData.data.OUTPUT_DIRECTORY.getAbsolutePath());

		try {
			EVGlobalData.data.targetCoordinateSystem = CRS.decode(config.getModules().get("global").getParams().get("coordinateSystem"));
			EVGlobalData.data.wgs84CoordinateSystem = CRS.decode("EPSG:4326");
		} catch (FactoryException e) {
			e.printStackTrace();
		}

		// TODO: the following statement is inserted just at the moment to avoid
		// config consistency check problems -> this could be done properly
		// later by introducing
		// proper config group for the new module

		config.removeModule(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLE_MODULE_NAME);
		EVGlobalData.data.eventLogger = new EventLogger(config);

		return config;
	}

	private static void initializeChargingInfrastructure(EVController controler) {
		EVGlobalData.data.chargingInfrastructureManager = new ChargingInfrastructureManagerImpl();

		/*
		for(ChargingSite site : EVGlobalData.data.chargingInfrastructureManager.getAllChargingSites()) {
			System.out.println(site.getAllChargingPlugs().iterator().next().useInCalibration());
			System.out.println(site.getCoord().getX() + ", " + site.getCoord().getY());
		}
		System.exit(0);
		*/
	}

	private static void integrateChargingEventManagerIntoSimulation(final EVController controler) {
		HashMap<Id<Person>, Id<Vehicle>> personToVehicleMapping = null;
		HashSet<String> travelModeFilter = new HashSet<>();
		travelModeFilter.add(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES);
		EVGlobalData.data.chargingEventManagerImp = new ChargingEventManager(personToVehicleMapping, controler, travelModeFilter);
	}

	private void initializeVehicleFleet(final EVController controler) {
		ParseVehicleTypes.vehicleTypeLoader();
		Collection<? extends Person> persons = controler.getScenario().getPopulation().getPersons().values();

		for (Person person : persons) {
			PlugInVehicleAgent agent = PlugInVehicleAgent.getAgent(person.getId());
			LinkedHashMap<String, Object> vehicleProperties = EVGlobalData.data.vehiclePropertiesMap
					.get(EVGlobalData.data.personToVehicleTypeMap.get(person.getId().toString()));

			if (((String) vehicleProperties.get("vehicleclassname")).equals("BatteryElectricVehicleImpl")) {
				agent.setVehicle(new BatteryElectricVehicleImpl((EnergyConsumptionModel) vehicleProperties.get("electricenergyconsumptionmodel"),
						(Double) vehicleProperties.get("batterycapacityinkwh") * 3600000.0, Id.create(person.getId(), Vehicle.class)));
			} else if (((String) vehicleProperties.get("vehicleclassname")).equals("PHEV")) {
				agent.setVehicle(new PHEV((EnergyConsumptionModel) vehicleProperties.get("electricenergyconsumptionmodel"),
						(EnergyConsumptionModel) vehicleProperties.get("petroleumenergyconsumptionmodel"),
						(Double) vehicleProperties.get("batterycapacityinkwh") * 3600000.0, Id.create(person.getId(), Vehicle.class)));
			}
			agent.getVehicle().setEnergyConsumptionParameters(Double.parseDouble((String) vehicleProperties.get("fueleconomyinkwhpermile")),
					Double.parseDouble((String) vehicleProperties.get("equivalenttestweight")),
					Double.parseDouble((String) vehicleProperties.get("targetcoefa")),
					Double.parseDouble((String) vehicleProperties.get("targetcoefb")),
					Double.parseDouble((String) vehicleProperties.get("targetcoefc")));
			agent.getVehicle().setChargingFields((String) vehicleProperties.get("vehicletypename"),
					(Double) vehicleProperties.get("maxdischargingpowerinkw"), (Double) vehicleProperties.get("maxlevel2chargingpowerinkw"),
					(Double) vehicleProperties.get("maxlevel3chargingpowerinkw"),
					(LinkedHashSet<ChargingPlugType>) vehicleProperties.get("compatibleplugtypes"));
			
			agent.getVehicle().useBattery(agent.getBatteryCapacity()*(1-EVGlobalData.data.simulationStartSocFraction.get(agent.getPersonId())));
			agent.getVehicle().setVehicleAgent(agent);
			agent.setVehicleType((String) vehicleProperties.get("vehicletypename"));
			agent.configureHomeCharger();
			agent.setEstimatedTravelDistanceInDay();
		}

	}

	// private static void printEventsSingleAgent(String agentId) {
	// String eventsFile =
	// "C:\\data\\bayAreaRuns\\output\\ITERS\\it.0\\run0.0.events.xml.gz";
	//
	// EventsManager events = EventsUtils.createEventsManager();
	//
	// SingleAgentEventsPrinter singleAgentEventsPrinter = new
	// SingleAgentEventsPrinter(
	// Id.create(agentId, Person.class));
	//
	// events.addHandler(singleAgentEventsPrinter);
	//
	// EventsReaderXMLv1 reader = new EventsReaderXMLv1(events);
	// reader.readFile(eventsFile);
	// }

	private static void convertCarLegsToEVLegsAndAddReplannable(final EVController controler) {
		Collection<? extends Person> persons = controler.getScenario().getPopulation().getPersons().values();

		for (Person person : persons) {
			for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
				if (pe instanceof Leg) {
					Leg leg = (Leg) pe;
					if (leg.getMode().equalsIgnoreCase(EVGlobalData.data.CAR_MODE)) {
						leg.setMode(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES);
					}
				}
			}
			ChargingStrategyManager.data.createReplanable(person); // this is
																	// added to
																	// all
																	// agents in
																	// the
																	// simulation,
																	// as all
																	// agents
			// go through same replanning procedure
		}
	}

	private static void createNetworkWithOnlyActivityLinks(final Controler controler) {
		// thin network to match plans
		// NetworkImpl network = (NetworkImpl)
		// controler.getScenario().getNetwork();
		// Network newNetwork = NetworkUtils.createNetwork();
		// NetworkFactoryImpl factory = new NetworkFactoryImpl(newNetwork);
		//
		// Collection<? extends Person> persons =
		// controler.getScenario().getPopulation().getPersons().values();
		//
		// for (Person person : persons) {
		// for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
		// if (pe instanceof Activity) {
		// Activity act = (Activity) pe;
		// Link link = network.getNearestLinkExactly(act.getCoord());
		//
		// if (!newNetwork.getLinks().containsKey(link.getId())) {
		//
		// if (!newNetwork.getNodes().containsKey(link.getToNode().getId())) {
		// Node newNode = factory.createNode(link.getToNode().getId(),
		// link.getToNode().getCoord());
		// newNetwork.addNode(newNode);
		// }
		// if (!newNetwork.getNodes().containsKey(link.getFromNode().getId())) {
		// Node newNode = factory.createNode(link.getFromNode().getId(),
		// link.getFromNode().getCoord());
		// newNetwork.addNode(newNode);
		// }
		//
		// Link newLink = factory.createLink(link.getId(),
		// newNetwork.getNodes().get(link.getFromNode().getId()),
		// newNetwork.getNodes().get(link.getToNode().getId()), (NetworkImpl)
		// newNetwork,
		// link.getLength(), link.getFreespeed(), link.getCapacity(),
		// link.getNumberOfLanes());
		// newLink.setAllowedModes(link.getAllowedModes());
		// newNetwork.addLink(newLink);
		// }
		// }
		// }
		// }
		//
		// GeneralLib.writeNetwork(newNetwork,
		// "C:\\data\\bayAreaRuns\\input\\thinedNetwork.xml");
	}

}
