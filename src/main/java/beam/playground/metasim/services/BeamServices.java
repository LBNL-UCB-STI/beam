package beam.playground.metasim.services;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.matsim.core.controler.MatsimServices;
import org.xml.sax.SAXException;

import com.google.inject.Inject;

import beam.parking.lib.DebugLib;
import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.agents.FiniteStateMachineGraph;
import beam.playground.metasim.agents.FiniteStateMachineGraphFactory;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.config.BeamConfigGroup;
import beam.playground.metasim.services.config.BeamEventLoggerConfigGroup;
import beam.sim.traveltime.BeamRouter;

public interface BeamServices {
	public MatsimServices getMatsimServices();
	public BeamRandom getRandom();
	public BeamRouter getRouter();
	public Scheduler getScheduler();
	public BeamConfigGroup getBeamConfigGroup();
	public BeamEventLoggerConfigGroup getBeamEventLoggerConfigGroup();
	public BeamAgentPopulation getBeamAgentPopulation();
	public ChoiceModelService getChoiceModelService();
	public FiniteStateMachineGraph getFiniteStateMachineGraphFor(Class<?> theClass);
	public void finalizeInitialization();
	
	public class Default implements BeamServices {
		private BeamConfigGroup beamConfig;
		private BeamEventLoggerConfigGroup beamEventLoggerConfig;
		private ChoiceModelService choiceModelService;
		private Scheduler scheduler;
		private BeamRandom random;
		private LinkedHashMap<Class<?>, FiniteStateMachineGraph> fsmMap;
		private FiniteStateMachineGraphFactory finiteStateMachineGraphFactory;
		private MatsimServices matsimServices;

		@Inject
		public Default(MatsimServices matsimServices, ChoiceModelService choiceModelService, Scheduler scheduler,  BeamRandom random, FiniteStateMachineGraphFactory finiteStateMachineGraphFactory) {
			super();
			this.matsimServices = matsimServices;
			this.beamConfig = (BeamConfigGroup) matsimServices.getConfig().getModules().get(BeamConfigGroup.GROUP_NAME);
			this.beamEventLoggerConfig = (BeamEventLoggerConfigGroup) matsimServices.getConfig().getModules().get(BeamEventLoggerConfigGroup.GROUP_NAME);
			this.choiceModelService = choiceModelService;
			this.scheduler = scheduler;
			this.random = random;
			this.finiteStateMachineGraphFactory = finiteStateMachineGraphFactory;
			/*
			 * Calls that involve parsing input files.
			 */
			try {
				initializeChoiceModelService();
				this.fsmMap = loadFiniteStateMachineGraphs();
			} catch (JDOMException | IOException | ClassNotFoundException | SAXException e ) {
				e.printStackTrace();
				DebugLib.stopSystemAndReportInconsistency();
			}
		}
		@Override
		public void finalizeInitialization(){
			for(FiniteStateMachineGraph fsm : fsmMap.values()){
				for(Action action : fsm.getActionMap().values()){
					choiceModelService.putDefaultChoiceModelForAction(action, choiceModelService.getDefaultChoiceModelOfClass(((Action.Default)action).getDefaultChoiceModel()));
				}
			}
		}
		private LinkedHashMap<Class<?>, FiniteStateMachineGraph> loadFiniteStateMachineGraphs() throws JDOMException, IOException, ClassNotFoundException, SAXException {
			LinkedHashMap<Class<?>, FiniteStateMachineGraph> result = new LinkedHashMap<>();
			SAXBuilder saxBuilder = new SAXBuilder();
			InputStream stream = null;
			Document document = null;
			stream = new FileInputStream(new File(beamConfig.getFiniteStateMachinesConfigFile()));
			document = saxBuilder.build(stream);

			for(int i=0; i < document.getRootElement().getChildren().size(); i++){
				Element elem = (Element)document.getRootElement().getChildren().get(i);
				if(elem.getName().toLowerCase().equals("finitestatemachine")){
					FiniteStateMachineGraph graph = finiteStateMachineGraphFactory.create(elem);
					graph.printGraphToImageFile(beamConfig.getDotConfigFile(),matsimServices.getControlerIO().getOutputPath());
					result.put(graph.getAssignedClass(), graph);
				}
			}
			return result;
		}
		private void initializeChoiceModelService() throws JDOMException, IOException, ClassNotFoundException, SAXException {
			SAXBuilder saxBuilder = new SAXBuilder();
			InputStream stream = null;
			stream = new FileInputStream(new File(beamConfig.getChoiceModelConfigFile()));
			choiceModelService.initialize(saxBuilder.build(stream));
		}
		@Override
		public BeamRandom getRandom() {
			return random;
		}
		@Override
		public BeamRouter getRouter() {
			return null;
		}
		@Override
		public Scheduler getScheduler() {
			return scheduler;
		}
		@Override
		public BeamAgentPopulation getBeamAgentPopulation() {
			return null;
		}
		@Override
		public BeamConfigGroup getBeamConfigGroup() {
			return beamConfig;
		}
		@Override
		public BeamEventLoggerConfigGroup getBeamEventLoggerConfigGroup() {
			return beamEventLoggerConfig;
		}
		@Override
		public ChoiceModelService getChoiceModelService() {
			return choiceModelService;
		}
		@Override
		public FiniteStateMachineGraph getFiniteStateMachineGraphFor(Class<?> theClass) {
			return fsmMap.get(theClass);
		}
		@Override
		public MatsimServices getMatsimServices() {
			return matsimServices;
		}


	}


	/*
	public static BeamServices data = null;

	public static void simulationStaticVariableInitializer() {
		data = new BeamServices();
		ChargingStrategyManager.init();
		PlugInVehicleAgent.init();
	}

	public Random rand;
	public String INPUT_DIRECTORY_BASE_PATH = "";
	public String CONFIG_RELATIVE_PATH = "";
	public String OUTPUT_DIRECTORY_BASE_PATH = "";
	public String OUTPUT_DIRECTORY_NAME = "";
	public File OUTPUT_DIRECTORY;

	// public TravelTimeEstimator travelTimeEstimator=new
	// TravelTimeEstimator();
	public final String PLUGIN_ELECTRIC_VEHICLE_MODULE_NAME = "PEVSim";
	public final String PLUGIN_ELECTRIC_VEHICLE_LOGGER_MODULE_NAME = "PEVSim.LogSettings";
	public final String SIMULATION_NAME = "simulationName";

	public final String PLUGIN_ELECTRIC_VEHICLES = "PEV";
	public final String CAR_MODE = "car";
	public final String TELEPORTED_TRANSPORATION_MODE = "bayPT"; // TODO: look
																	// at router
																	// for this

	public Double RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS;
	public Double INITIAL_SEARCH_RADIUS;
	public Double SEARCH_RADIUS_INCREMENTATION_FACTOR;
	public Double MAX_SEARCH_RADIUS = 3200.0; // TODO: add to config file
	public Double FRACTION_HOME_CHARGERS;

	public Double CHARGING_STATION_USE_CONSTANT_OVERHEAD_TIME;
	public Double AVERAGE_WALK_SPEED_TO_CHARGING_STATION; // m/s
	public Double AVERAGE_ENDING_ACTIVITY_DURATION = 12.0 * 3600.0; // 12 hours
	// TODO: add to config file
	public Double WALK_DISTANCE_ADJUSTMENT_FACTOR; // include
													// walking
													// detours
													// here
													// +
													// 2
													// times
													// distance
	public Double OVERHEAD_TRAVEL_TIME_FACTOR_WHEN_RUNNING_OUT_OF_BATTERY; // if
																			// running
																			// out
																			// of
																			// battery,
																			// walk
																			// by
																			// foot

	public Double CHARGING_SCORE_SCALING_FACTOR;
	public double BETA_CHARGING_COST;
	public double BETA_PARKING_COST;
	public double BETA_PARKING_WALK_TIME;
	public double BETA_LEG_TRAVEL_TIME;
	public double OVERHEAD_SCORE_PLUG_CHANGE;

	public String CHARGING_SITE_POLICIES_FILEPATH;
	public String CHARGING_NETWORK_OPERATORS_FILEPATH;
	public String CHARGING_SITES_FILEPATH;
	public String CHARGING_PLUG_TYPES_FILEPATH;
	public String CHARGING_POINTS_FILEPATH;
	public String CHARGING_STRATEGIES_FILEPATH;
	public String VEHICLE_TYPES_FILEPATH;
	public String PERSON_VEHICLE_TYPES_FILEPATH;
	public String RELAXED_TRAVEL_TIME_FILEPATH;
	public String ROUTER_CACHE_READ_FILEPATH;
	public String ROUTER_CACHE_WRITE_FILEPATH;
	public Boolean IS_DEBUG_MODE;

	public ChargingInfrastructureManagerImpl chargingInfrastructureManager;
	public Double EN_ROUTE_SEARCH_DISTANCE; // meters
	public Double EQUALITY_EPSILON;
	public Double TIME_TO_ENGAGE_NEXT_FAST_CHARGING_SESSION;

	public ChargingEventManagerImpl chargingEventManagerImpl;
	public EVController controler;

	public Config config;
	public CoordinateTransformation transformFromWGS84;
	public LinkedHashMap<String, LinkedHashMap> vehiclePropertiesMap;
	public LinkedHashMap<String, String> personToVehicleTypeMap;
	public LinkedHashMap<String, LinkedHashMap<String, String>> personHomeProperties;
	public DoubleValueHashMap<Id<Person>> simulationStartSocFraction = new DoubleValueHashMap<>();
	public int currentDay = 0;
	public double timeMarkingNewDay = 14400.0; // the number of seconds
												// from midnight at
												// which point we've
												// transitioned to the
												// next travel day

	public final double NUMBER_OF_SECONDS_IN_ONE_DAY = 3600 * 24;

	public QSim qsim;
	public Scheduler scheduler = new Scheduler();
	public GlobalActions globalActions = new GlobalActions();
	public EventLogger eventLogger;
	public double now;
	public BeamRouter router;
	public RelaxedTravelTime travelTimeFunction;
	public LinkedHashMap<String, TripInformation> tripInformationCache;
	public LinkQuadTree linkQuadTree;
	public HashMap<String, Class> modeRouterMapping = new HashMap<>();
	public double averageWalkingTimeFromParkToActivity = 120;

	public String CHARGING_POINT_REFERENCE_UTILIZATION_DATA_FILE;
	public Boolean IS_TEST_CASE = false;
	public TestingHooks testingHooks = new TestingHooks();

	// PARAMETERS with default values
	public Boolean DUMP_PLANS_AT_END_OF_RUN = true;
	public String EVENTS_FILE_OUTPUT_FORMATS = "xml.gz";
	public Boolean DUMP_PLAN_CSV = false;
	public int DUMP_PLAN_CSV_INTERVAL = 1;
	public int MAX_NUMBER_OF_EV_DAILY_PLANS_IN_MEMORY = 5;
	public String SELECTED_EV_DAILY_PLANS_FILE_NAME = "selectedEVDailyPlans.csv.gz";

	public String toString() {
		return (new Double(data.now)).toString();
	}
	*/
}
