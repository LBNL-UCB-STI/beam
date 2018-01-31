package beam.analysis.physsim;

import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.io.File;

public class PhyssimCalcLinkStatsTest {

    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String OUTPUT_DIR_PATH = BASE_PATH+"/test/input/beamville/test-relative-speeds/output";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/beamville/test-relative-speeds/physsim.events.xml";
    private static String NETWORK_FILE_PATH = BASE_PATH+"/test/input/beamville/physsim-network.xml";
    private static int iteration = 0;
    private static PhyssimCalcLinkStats physsimCalcLinkStats;

    static {
        createDummySimWithXML();
    }

    private synchronized static void createDummySimWithXML(){

        Config _config = ConfigUtils.createConfig();

        OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);

        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        MatsimNetworkReader nwr= new MatsimNetworkReader(sc.getNetwork());
        // Point to the metwork of the beamville scenrio
        nwr.readFile(NETWORK_FILE_PATH);

        Network network = sc.getNetwork();
        TravelTimeCalculatorConfigGroup ttccg = _config.travelTimeCalculator();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(network, ttccg);
        //

        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler(travelTimeCalculator);

        physsimCalcLinkStats = new PhyssimCalcLinkStats(network, outputDirectoryHierarchy);

        physsimCalcLinkStats.notifyIterationStarts(events);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(EVENTS_FILE_PATH);


        physsimCalcLinkStats.notifyIterationEnds(0, travelTimeCalculator);
    }


    @Test
    public static void testRelativeSpeeds(){


        System.out.println("Test to be executed here.");
    }

    public static void main(String args[]){


        //runJdeqSimulation();
    }

}
