package beam.analysis.plots;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.NoOpSimulationMetricCollector$;
import beam.utils.EventReader;
import beam.utils.TestConfigUtils;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.handler.BasicEventHandler;

import java.nio.file.Paths;

import static org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphTestUtil {

    static final String CAR = "car";
    static final String WALK = "walk";
    static final String BUS = "bus";
    static final String SUBWAY = "subway";
    static final String DRIVE_TRANS = "drive_transit";
    static final String RIDE_HAIL = "ride_hail";
    static final String WALK_TRANS = "walk_transit";

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH + "/test/input/beamville/transitVehicles.xml";
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/beamville.events.xml";
    private static final BeamConfig beamconfig = BeamConfig.apply(TestConfigUtils.testConfig("test/input/beamville/beam.conf").resolve());
    private static final BeamServices services = mock(BeamServices.class);
    private static final MatsimServices matsimServices = mock(MatsimServices.class);
    private static final OutputDirectoryHierarchy ioController =
            new OutputDirectoryHierarchy(BASE_PATH + "/output/beam-test-output", overwriteExistingFiles);
    private static final EventsManager events;

    static {
        when(services.beamConfig()).thenReturn(beamconfig);
        when(services.simMetricCollector()).thenReturn(NoOpSimulationMetricCollector$.MODULE$);
        when(services.matsimServices()).thenReturn(matsimServices);
        when(matsimServices.getControlerIO()).thenReturn(ioController);
        events = EventsUtils.createEventsManager();
    }

    synchronized static void createDummySimWithXML() {
        GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener(services);
        createDummySimWithXML(graphsFromAgentSimEvents);
    }

    synchronized static void createDummySimWithXML(BasicEventHandler handler) {
        createDummySimWithXML(handler, EVENTS_FILE_PATH);
    }

    synchronized static void createDummySimWithXML(BasicEventHandler handler,String xmlFile) {
        PathTraversalSpatialTemporalTableGenerator.loadVehicles(TRANSIT_VEHICLE_FILE_PATH);
        events.addHandler(handler);
        EventReader.fromFile(xmlFile, events);
    }
}