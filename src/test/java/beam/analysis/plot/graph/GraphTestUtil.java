package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;

public class GraphTestUtil {
    static GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener();
    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH+"/test/input/beamville/transitVehicles.xml";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/beamville/test-data/beamville.events.xml";
    public synchronized static void createDummySimWithXML(){
        PathTraversalSpatialTemporalTableGenerator.loadVehicles(TRANSIT_VEHICLE_FILE_PATH);
        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler(graphsFromAgentSimEvents);
        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(EVENTS_FILE_PATH);
    }
}
