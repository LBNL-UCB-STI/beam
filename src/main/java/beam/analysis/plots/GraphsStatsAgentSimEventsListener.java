package beam.analysis.plots;

import beam.analysis.*;
import beam.calibration.impl.example.ErrorComparisonType;
import beam.calibration.impl.example.ModeChoiceObjectiveFunction;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;


/**
 * @Authors asif and rwaraich.
 */
public class GraphsStatsAgentSimEventsListener implements BasicEventHandler, IterationSummaryStats {

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
    private final StatsFactory statsFactory;
    private final BeamConfig beamConfig;

    private Logger log = LoggerFactory.getLogger(GraphsStatsAgentSimEventsListener.class);

    // No Arg Constructor
    public GraphsStatsAgentSimEventsListener(BeamConfig beamConfig) {
        statsFactory = new StatsFactory(beamConfig);
        statsFactory.createStats();
        this.beamConfig = beamConfig;
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
        for (BeamStats beamStats : statsFactory.getStats()) {
            beamStats.resetStats();
        }
    }

    @Override
    public void handleEvent(Event event) {
        for (BeamStats beamStats : statsFactory.getStats()) {
            beamStats.processStats(event);
        }
        DeadHeadingStats deadHeadingStats = (DeadHeadingStats) statsFactory.getStats(StatsFactory.DeadHeading);
        deadHeadingStats.collectEvents(event);
    }

    public void createGraphs(IterationEndsEvent event) throws IOException {
        for (BeamStats stat : statsFactory.getStats()) stat.createGraph(event);
        DeadHeadingStats deadHeadingStats = (DeadHeadingStats) statsFactory.getStats(StatsFactory.DeadHeading);
        deadHeadingStats.createGraph(event, "TNC0");


        if (CONTROLLER_IO != null) {
            try {
                // TODO: Asif - benchmarkFileLoc also part of calibraiton yml -> remove there (should be just in config file)

                // TODO: Asif there should be no need to write to root and then read (just quick hack) -> update interface on methods, which need that data to pass in memory
                BeamStats modeChoseStats = statsFactory.getStats(StatsFactory.ModeChosen);
                ((ModeChosenStats) modeChoseStats).writeToRootCSV();
                if (beamConfig.beam().calibration().mode().benchmarkFileLoc().trim().length() > 0) {
                    String outPath = CONTROLLER_IO.getOutputFilename("modeChoice.csv");
                    Double modesAbsoluteError = new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFileLoc())
                            .evaluateFromRun(outPath, ErrorComparisonType.AbsoluteError());
                    log.info("modesAbsoluteError: " + modesAbsoluteError);

                    Double modesRMSPError = new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFileLoc())
                            .evaluateFromRun(outPath, ErrorComparisonType.RMSPE());
                    log.info("modesRMSPError: " + modesRMSPError);
                }
            } catch (Exception e) {
                log.error("exception: {}", e.getMessage());
            }
        }
    }

    public void notifyShutdown(ShutdownEvent event) throws Exception {
        RealizedModeStats realizedModeStats = (RealizedModeStats) statsFactory.getStats(StatsFactory.RealizedMode);
        if (realizedModeStats != null) realizedModeStats.notifyShutdown(event);
    }

    @Override
    public Map<String, Double> getIterationSummaryStats() {
        IterationSummaryStats personTravelTimes= (IterationSummaryStats)statsFactory.getStats(StatsFactory.PersonTravelTime);
        return personTravelTimes.getIterationSummaryStats();
    }
}