package beam.analysis.plots;

import beam.analysis.*;
import beam.analysis.StatsFactory.StatsType;
import beam.calibration.impl.example.ErrorComparisonType;
import beam.calibration.impl.example.ModeChoiceObjectiveFunction;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

import java.util.*;
import java.util.stream.Collectors;


/**
 * @Authors asif and rwaraich.
 */
public class GraphsStatsAgentSimEventsListener implements BasicEventHandler, IterationStatsProvider {

    public static final String CAR = "car";
    public static final String RIDE = "ride";
    public static final String TNC = "tnc";
    public static final String WALK = "walk";

    public static final String RIDE_HAILING = "ride_hail";

    public static final String TNC_DEAD_HEADING_DISTANCE = "tnc_deadheading_distance";
    public static final int GRAPH_HEIGHT = 600;
    public static final int GRAPH_WIDTH = 800;
    private static final int SECONDS_IN_HOUR = 3600;
    // Static Initializer
    private final StatsFactory statsFactory;
    private final BeamConfig beamConfig;

    private final Logger log = LoggerFactory.getLogger(GraphsStatsAgentSimEventsListener.class);

    // No Arg Constructor
    public GraphsStatsAgentSimEventsListener(BeamServices services) {
        this.beamConfig = services.beamConfig();
        statsFactory = new StatsFactory(services);
    }

    // Constructor
    public GraphsStatsAgentSimEventsListener(EventsManager eventsManager,
                                             OutputDirectoryHierarchy controlerIO,
                                             BeamServices services, BeamConfig beamConfig) {
        this(services);
        try{
            statsFactory.createStats();
        }catch (Exception e){
            log.error("Error trying to create stats", e);
        }

        eventsManager.addHandler(this);
        PathTraversalSpatialTemporalTableGenerator.setVehicles(JavaConverters.mapAsJavaMap(services.beamScenario().vehicleTypes()));
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
        statsFactory.getBeamAnalysis().forEach(BeamAnalysis::resetStats);
    }

    @Override
    public void handleEvent(Event event) {
        for (BeamAnalysis stat : statsFactory.getBeamAnalysis()) stat.processStats(event);
        DeadHeadingAnalysis deadHeadingStats = (DeadHeadingAnalysis) statsFactory.getAnalysis(StatsType.DeadHeading);
        deadHeadingStats.collectEvents(event);
    }

    public void createGraphs(IterationEndsEvent event) {
            try {
                for (GraphAnalysis stat : statsFactory.getGraphAnalysis()) stat.createGraph(event);
                DeadHeadingAnalysis deadHeadingStats = (DeadHeadingAnalysis) statsFactory.getAnalysis(StatsType.DeadHeading);
                deadHeadingStats.createGraph(event, "TNC0");
                if (event.getServices().getControlerIO() != null) {
                    // TODO: Asif - benchmarkFileLoc also part of calibraiton yml -> remove there (should be just in config file)

                    // TODO: Asif there should be no need to write to root and then read (just quick hack) -> update interface on methods, which need that data to pass in memory
                    ModeChosenAnalysis modeChoseStats = (ModeChosenAnalysis) statsFactory.getAnalysis(StatsType.ModeChosen);
                    String outPath = event.getServices().getControlerIO().getOutputFilename(ModeChosenAnalysis.getModeChoiceFileBaseName() + ".csv");
                    modeChoseStats.writeToRootCSV(outPath);
                    if (beamConfig.beam().calibration().mode().benchmarkFilePath().trim().length() > 0) {
                        Double modesAbsoluteError = new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFilePath()).evaluateFromRun(outPath, ErrorComparisonType.AbsoluteError());
                        log.info("modesAbsoluteError: " + modesAbsoluteError);
                        Double modesRMSPError = new ModeChoiceObjectiveFunction(beamConfig.beam().calibration().mode().benchmarkFilePath()).evaluateFromRun(outPath, ErrorComparisonType.RMSPE());
                        log.info("modesRMSPError: " + modesRMSPError);
                    }
                }
            } catch (Exception e) {
                log.error("Error trying to create a graph", e);
            }

    }

    private final static String ON_DEMAND_RIDE = "onDemandRide";
    @Override
    public Map<String, Double> getSummaryStats() {
        return statsFactory.getSummaryAnalysis().stream()
                .map(IterationSummaryAnalysis::getSummaryStats)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(e -> e.getKey().replaceAll(RideHailWaitingAnalysis.RIDE_HAIL, ON_DEMAND_RIDE), Map.Entry::getValue));
    }
}