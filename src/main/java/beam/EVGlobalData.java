package beam;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Random;

import beam.charging.management.ChargingQueueImpl;
import beam.sim.traveltime.TripInfoCache;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSiteSpatialGroup;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.network.LinkQuadTree;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import beam.charging.ChargingEventManagerImpl;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.controller.EVController;
import beam.events.EventLogger;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.replanning.ChargingStrategyManager;
import beam.sim.GlobalActions;
import beam.sim.scheduler.Scheduler;
import beam.sim.traveltime.BeamRouter;
import beam.sim.traveltime.ExogenousTravelTime;
import beam.sim.traveltime.TripInformation;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

//TODO: load all parameters from config in the end
public class EVGlobalData {
	// TODO: instead of setting values from outside, give config as input and it should set the values internally (move code from controller to this class).
	
	private static final Logger log = Logger.getLogger(EVGlobalData.class);

	public static EVGlobalData data = null;
	public CoordinateReferenceSystem targetCoordinateSystem;
	public CoordinateReferenceSystem wgs84CoordinateSystem;


	public static void simulationStaticVariableInitializer() {
		data = new EVGlobalData();
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
	public String CHARGING_LOAD_VALIDATION_FILEPATH;
	public String CHARGING_NETWORK_OPERATORS_FILEPATH;
	public String CHARGING_SITES_FILEPATH;
	public String CHARGING_PLUG_TYPES_FILEPATH;
	public String CHARGING_POINTS_FILEPATH;
	public String CHARGING_STRATEGIES_FILEPATH;
	public String UPDATED_CHARGING_STRATEGIES_FILEPATH;
	public String UPDATED_CHARGING_STRATEGIES_BACKUP_FILEPATH;
	public String VEHICLE_TYPES_FILEPATH;
	public String PERSON_VEHICLE_TYPES_FILEPATH;
	public String TRAVEL_TIME_FILEPATH;
	public String ROUTER_CACHE_READ_FILEPATH;
	public String ROUTER_CACHE_WRITE_FILEPATH;
	public int ROUTER_CACHE_IN_MEMORY_TRIP_LIMIT;
	public String LINK_ATTRIBUTE_FILEPATH;
	public Boolean IS_DEBUG_MODE;
	public boolean SHOULD_CALIBRATE_PARAMS;
	public boolean SHOULD_RESUME_CALIBRATION;
	public int ITER_SET_LENGTH;

	public ChargingInfrastructureManagerImpl chargingInfrastructureManager;
	public ChargingQueueImpl fastChargingQueue;
	public ChargingQueueImpl slowChargingQueue;
	public ArrayList<ChargingPlugType> fastChargingPlugTypes;
	public Double EN_ROUTE_SEARCH_DISTANCE; // meters
	public Double EQUALITY_EPSILON;
	public Double TIME_TO_ENGAGE_NEXT_FAST_CHARGING_SESSION;

	public ChargingEventManagerImpl chargingEventManagerImpl;
	public EVController controler;

	public Config config;
	public CoordinateTransformation transformFromWGS84;
	public LinkedHashMap<String, LinkedHashMap> vehiclePropertiesMap;
	public LinkedHashMap<String, String> personToVehicleTypeMap;
	public LinkedHashMap<String, ChargingSiteSpatialGroup> chargingSiteSpatialGroupMap;
	public LinkedHashMap<String, LinkedHashMap<String, String>> personHomeProperties;
	public LinkedHashMap<String, LinkedHashMap<String, String>> linkAttributes;
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
	public ExogenousTravelTime travelTimeFunction;
	public TripInfoCache newTripInformationCache;
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
}
