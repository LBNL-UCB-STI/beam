package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import org.jfree.data.category.CategoryDataset;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;


/**
 * @Authors asif and rwaraich.
 */
public class GraphsStatsAgentSimEventsListener implements BasicEventHandler {

    public static final String CAR = "car";
    public static final String RIDE = "ride";
    public static final String TNC = "tnc";
    public static final String WALK = "walk";

    public static final String RIDE_HAILING = "ride_hail";

    public static final String TNC_DEAD_HEADING_DISTANCE = "tnc_deadheading_distance";
    public static final int GRAPH_HEIGHT = 600;
    public static final int GRAPH_WIDTH = 800;
    private static final int SECONDS_IN_HOUR = 3600;
    public static OutputDirectoryHierarchy CONTROLLER_IO;
    // Static Initializer
    private final IGraphStats deadHeadingStats = new DeadHeadingStats();
    private final IGraphStats fuelUsageStats = new FuelUsageStats(new FuelUsageStats.FuelUsageStatsComputation());
    private final IGraphStats modeChoseStats = new ModeChosenStats(new ModeChosenStats.ModeChosenComputation());
    private final IGraphStats personTravelTimeStats = new PersonTravelTimeStats(new PersonTravelTimeStats.PersonTravelTimeComputation());
    private final IGraphStats rideHailWaitingStats;
    private final IGraphStats personVehicleTransitionStats;
    private final IGraphStats rideHailingWaitingSingleStats;
    private final IGraphStats realizedModeStats = new RealizedModeStats(new RealizedModeStats.RealizedModesStatsComputation());
    private final BeamConfig beamConfig;

    private Logger log = LoggerFactory.getLogger(GraphsStatsAgentSimEventsListener.class);

    // No Arg Constructor
    public GraphsStatsAgentSimEventsListener(BeamConfig beamConfig) {
        rideHailWaitingStats = new RideHailWaitingStats(new RideHailWaitingStats.WaitingStatsComputation(), beamConfig);
        rideHailingWaitingSingleStats = new RideHailingWaitingSingleStats(beamConfig, new RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation());
        personVehicleTransitionStats = new PersonVehicleTransitionStats(beamConfig);
       this.beamConfig=beamConfig;
    }

    // Constructor
    public GraphsStatsAgentSimEventsListener(EventsManager eventsManager,
                                             OutputDirectoryHierarchy controlerIO,
                                             BeamServices services, BeamConfig beamConfig) {
        this(beamConfig);
        eventsManager.addHandler(this);
        CONTROLLER_IO = controlerIO;
        PathTraversalSpatialTemporalTableGenerator.setVehicles(services.vehicleTypes());
    }

    // helper methods
    static int getEventHour(double time) {
        return (int) time / SECONDS_IN_HOUR;
    }

    public static List<Integer> getSortedIntegerList(Set<Integer> integerSet) {
        List<Integer> list = new ArrayList<>(integerSet);
        Collections.sort(list);
        return list;
    }

    public static List<String> getSortedStringList(Set<String> stringSet) {
        List<String> graphNamesList = new ArrayList<>(stringSet);
        Collections.sort(graphNamesList);
        return graphNamesList;
    }

    @Override
    public void reset(int iteration) {
        deadHeadingStats.resetStats();
        fuelUsageStats.resetStats();
        modeChoseStats.resetStats();
        personTravelTimeStats.resetStats();
        personVehicleTransitionStats.resetStats();
        rideHailWaitingStats.resetStats();
        rideHailingWaitingSingleStats.resetStats();
        realizedModeStats.resetStats();
    }

    @Override
    public void handleEvent(Event event) {
        if (event instanceof ReplanningEvent || event.getEventType().equalsIgnoreCase(ReplanningEvent.EVENT_TYPE)) {
            realizedModeStats.processStats(event);
        }
        if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)) {
            rideHailWaitingStats.processStats(event);
            rideHailingWaitingSingleStats.processStats(event);
            modeChoseStats.processStats(event);
            realizedModeStats.processStats(event);
        } else if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            fuelUsageStats.processStats(event);
            deadHeadingStats.processStats(event);
        } else if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        } else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        } else if (event instanceof PersonEntersVehicleEvent || event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE)) {
            rideHailWaitingStats.processStats(event);
            rideHailingWaitingSingleStats.processStats(event);
            personVehicleTransitionStats.processStats(event);
        }else if (event instanceof PersonLeavesVehicleEvent || event.getEventType().equalsIgnoreCase(PersonLeavesVehicleEvent.EVENT_TYPE)) {
            personVehicleTransitionStats.processStats(event);
        }

        deadHeadingStats.collectEvents(event);
    }

    public void createGraphs(IterationEndsEvent event) throws IOException {
        modeChoseStats.createGraph(event);
        fuelUsageStats.createGraph(event);
        rideHailWaitingStats.createGraph(event);
        rideHailingWaitingSingleStats.createGraph(event);

        deadHeadingStats.createGraph(event);
        deadHeadingStats.createGraph(event, "TNC0");
        deadHeadingStats.createGraph(event, "");

        personTravelTimeStats.createGraph(event);
        personVehicleTransitionStats.createGraph(event);
        realizedModeStats.createGraph(event);

        if (CONTROLLER_IO != null) {
            try {
                // TODO: Asif - benchmarkFileLoc also part of calibraiton yml -> remove there (should be just in config file)

                // TODO: Asif there should be no need to write to root and then read (just quick hack) -> update interface on methods, which need that data to pass in memory
                ((ModeChosenStats) modeChoseStats).writeToRootCSV();
                if (beamConfig.beam().calibration().mode().benchmarkFileLoc().trim().length()>0) {
                    String outPath = CONTROLLER_IO.getOutputFilename("modeChoice.csv");
                    Double modesAbsoluteError =new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFileLoc())
                            .evaluateFromRun(outPath, ErrorComparisonType.AbsoluteError());
                    log.info("modesAbsoluteError: " + modesAbsoluteError);

                    Double modesRMSPError =new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFileLoc())
                            .evaluateFromRun(outPath, ErrorComparisonType.RMSPE());
                    log.info("modesRMSPError: " + modesRMSPError);
                }
            } catch (Exception e){
                log.error("exception: {}", e.getMessage());
            }
        }
    }

    public void notifyShutdown(ShutdownEvent event) throws Exception{
        if(modeChoseStats instanceof  ModeChosenStats){
            ModeChosenStats modeStats = (ModeChosenStats) modeChoseStats;
            modeStats.notifyShutdown(event);
        }

        if(realizedModeStats instanceof RealizedModeStats){
            RealizedModeStats realizedStats = (RealizedModeStats) realizedModeStats;
            realizedStats.notifyShutdown(event);
        }
    }
}