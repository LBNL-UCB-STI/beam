package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.agentsim.events.ReplanningEvent;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import java.io.IOException;
import java.util.*;


/**
 * @Authors asif and rwaraich.
 */
public class GraphsStatsAgentSimEventsListener implements BasicEventHandler {

    private static final int SECONDS_IN_HOUR = 3600;
    public static final String CAR = "car";
    public static final String RIDE = "ride";
    public static final String TNC = "tnc";
    public static final String WALK="walk";
    public static final String RIDE_HAILING = "ride_hailing";
    public static final String TNC_DEAD_HEADING_DISTANCE="tnc_deadheading_distance";

    public static OutputDirectoryHierarchy CONTROLLER_IO;
    public static final int GRAPH_HEIGHT=600;
    public static final int GRAPH_WIDTH =800;
    // Static Initializer

    private IGraphStats deadHeadingStats = new DeadHeadingStats();
    private IGraphStats fuelUsageStats = new FuelUsageStats();
    private IGraphStats modeChoseStats = new ModeChosenStats();
    private IGraphStats personTravelTimeStats = new PersonTravelTimeStats();
    private IGraphStats rideHailWaitingStats = new RideHailWaitingStats();
    //private IGraphStats generalStats = new RideHailStats();
    private IGraphStats rideHailingWaitingSingleStats;
    private IGraphStats realizedModeStats = new RealizedModeStats();


    // No Arg Constructor
    public GraphsStatsAgentSimEventsListener(BeamConfig beamConfig) {
        rideHailingWaitingSingleStats = new RideHailingWaitingSingleStats(beamConfig);
    }

    // Constructor
    public GraphsStatsAgentSimEventsListener(EventsManager eventsManager,
                                             OutputDirectoryHierarchy controlerIO,
                                             Scenario scenario, BeamConfig beamConfig) {
        this(beamConfig);
        eventsManager.addHandler(this);
        CONTROLLER_IO = controlerIO;
        PathTraversalSpatialTemporalTableGenerator.setVehicles(scenario.getTransitVehicles());
    }

    @Override
    public void reset(int iteration) {
        deadHeadingStats.resetStats();
        fuelUsageStats.resetStats();
        modeChoseStats.resetStats();
        personTravelTimeStats.resetStats();
        rideHailWaitingStats.resetStats();
        //generalStats.resetStats();
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
            //generalStats.processStats(event);
            fuelUsageStats.processStats(event);
            deadHeadingStats.processStats(event);
        } else if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        } else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE)) {
            personTravelTimeStats.processStats(event);
        } else if (event instanceof PersonEntersVehicleEvent || event.getEventType().equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE)){
            rideHailWaitingStats.processStats(event);
            rideHailingWaitingSingleStats.processStats(event);
        }
    }

    public void createGraphs(IterationEndsEvent event) throws IOException {
        modeChoseStats.createGraph(event);
        fuelUsageStats.createGraph(event);

        rideHailWaitingStats.createGraph(event);
        rideHailingWaitingSingleStats.createGraph(event);


        deadHeadingStats.createGraph(event,"TNC0");
        deadHeadingStats.createGraph(event,"");
        personTravelTimeStats.resetStats();
        realizedModeStats.createGraph(event);
        //generalStats.createGraph(event);
    }

     // helper methods
    public static int getEventHour(double time) {
        return (int) time / SECONDS_IN_HOUR;
    }
    public static List<Integer> getSortedIntegerList(Set<Integer> integerSet){
        List<Integer> list = new ArrayList<>(integerSet);
        Collections.sort(list);
        return list;
    }
    public static List<String> getSortedStringList(Set<String> stringSet){
        List<String> graphNamesList = new ArrayList<>(stringSet);
        Collections.sort(graphNamesList);
        return graphNamesList;
    }
}