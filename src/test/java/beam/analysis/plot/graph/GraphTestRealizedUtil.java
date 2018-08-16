package beam.analysis.plot.graph;

import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.config.BeamConfig;
import beam.utils.TestConfigUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.nio.file.Paths;

public class GraphTestRealizedUtil {
    public static final String CAR = "car";
    public static final String WALK = "walk";
    public static final String RIDE_HAIL = "ride_hail";
    public static final String OTHERS = "others";

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/";
    public static boolean simRunFlag = false;
    private static BeamConfig beamconfig = BeamConfig.apply(TestConfigUtils.testConfig("test/input/beamville/beam.conf"));
    private static GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener(beamconfig);

    public synchronized static void createDummySimWithCRCXML() {
        if (!simRunFlag) {
            EventsManager events = EventsUtils.createEventsManager();
            events.addHandler(graphsFromAgentSimEvents);
            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(EVENTS_FILE_PATH + "beamville.CRCrealized.events.xml");
            simRunFlag = true;
        }
    }

    public synchronized static void createDummySimWithCRCRCXML() {
        if (!simRunFlag) {
            EventsManager events = EventsUtils.createEventsManager();
            events.addHandler(graphsFromAgentSimEvents);
            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(EVENTS_FILE_PATH + "beamville.CRCRCrealized.events.xml");
            simRunFlag = true;
        }
    }

    public synchronized static void createDummySimWithNestedCRCRCXML() {
        if (!simRunFlag) {
            EventsManager events = EventsUtils.createEventsManager();
            events.addHandler(graphsFromAgentSimEvents);
            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(EVENTS_FILE_PATH + "beamville.nestedCRCRCrealized.events.xml");
            simRunFlag = true;
        }
    }
}
