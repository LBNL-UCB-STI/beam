package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.config.BeamConfig;
import beam.utils.TestConfigUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.nio.file.Paths;

public class GraphTestUtil {

    static final String CAR = "car";
    static final String WALK = "walk" ;
    static final String BUS = "bus";
    static final String SUBWAY = "subway";
    static final String DRIVE_TRANS = "drive_transit";
    static final String RIDE_HAIL = "ride_hailing";
    static final String WALK_TRANS = "walk_transit";

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH + "/test/input/beamville/transitVehicles.xml";
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/beamville.events.xml";

    private static BeamConfig beamconfig = BeamConfig.apply(TestConfigUtils.testConfig("test/input/beamville/beam.conf"));
    static GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener(beamconfig);
    static boolean simRunFlag = false;

    public synchronized static void createDummySimWithXML() {
        if (!simRunFlag) {
            PathTraversalSpatialTemporalTableGenerator.loadVehicles(TRANSIT_VEHICLE_FILE_PATH);
            EventsManager events = EventsUtils.createEventsManager();
            events.addHandler(graphsFromAgentSimEvents);
            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(EVENTS_FILE_PATH);
            simRunFlag = true;
        }
    }
}
