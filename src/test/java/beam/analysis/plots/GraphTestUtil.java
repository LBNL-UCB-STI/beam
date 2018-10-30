package beam.analysis.plots;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.utils.TestConfigUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;

import java.nio.file.Paths;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GraphTestUtil {

    static final String CAR = "car";
    static final String WALK = "walk";
    static final String BUS = "bus";
    static final String SUBWAY = "subway";
    static final String DRIVE_TRANS = "drive_transit";
    static final String RIDE_HAIL = "ride_hail";
    static final String WALK_TRANS = "walk_transit";
    static final String OTHERS = "others";

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH + "/test/input/beamville/transitVehicles.xml";
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/beamville.events.xml";
    static boolean simRunFlag = false;
    private static BeamConfig beamconfig = BeamConfig.apply(TestConfigUtils.testConfig("test/input/beamville/beam.conf"));
    static BeamServices services = mock(BeamServices.class);
    static GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents;
    static EventsManager events;

    static {
        when(services.beamConfig()).thenReturn(beamconfig);
        events = EventsUtils.createEventsManager();
    }

    public synchronized static void createDummySimWithXML() {
        graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener(services);
        createDummySimWithXML(graphsFromAgentSimEvents);
    }

    public synchronized static void createDummySimWithXML(BasicEventHandler handler) {
        PathTraversalSpatialTemporalTableGenerator.loadVehicles(TRANSIT_VEHICLE_FILE_PATH);

        events.addHandler(handler);
        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(EVENTS_FILE_PATH);
    }
}