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
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PhyssimCalcLinkStatsTest{

    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String OUTPUT_DIR_PATH = BASE_PATH+"/test/input/equil-square/test-data/output";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/equil-square/test-data/physSimEvents.relative-speeds.xml";
    private static String NETWORK_FILE_PATH = BASE_PATH+"/test/input/equil-square/test-data/physSimNetwork.relative-speeds.xml";
    private static PhyssimCalcLinkStats physsimCalcLinkStats;

    static {
        createDummySimWithXML();
    }

    private synchronized static void createDummySimWithXML(){

        Config _config = ConfigUtils.createConfig();

        OutputDirectoryHierarchy.OverwriteFileSetting overwriteExistingFiles = OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles;

        OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(OUTPUT_DIR_PATH, overwriteExistingFiles);

        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        MatsimNetworkReader nwr= new MatsimNetworkReader(sc.getNetwork());
        // Point to the metwork of the beamville scenrio
        nwr.readFile(NETWORK_FILE_PATH);

        Network network = sc.getNetwork();
        TravelTimeCalculatorConfigGroup ttccg = _config.travelTimeCalculator();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(network, ttccg);


        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler(travelTimeCalculator);

        physsimCalcLinkStats = new PhyssimCalcLinkStats(network, outputDirectoryHierarchy);

        physsimCalcLinkStats.notifyIterationStarts(events);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(EVENTS_FILE_PATH);
        physsimCalcLinkStats.notifyIterationEnds(0, travelTimeCalculator);
    }


    @Test
    public void test_Should_Pass_Should_Return_COUNT_OF_SPECIFIC_HOUR(){
        Double expectedResult=10.0;
        Double actualResult =  physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(0,7);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_Return_COUNT_OF_ALL_RELATIVE_SPEED_CATEGORY_FOR_SPECIFIC_HOUR(){
        List<Double> relativeSpeedCategoryList=physsimCalcLinkStats.getSortedListRelativeSpeedCategoryList();
        Double expectedResult=260.0;
        Double actualResult = 0.0;
        for(Double category:relativeSpeedCategoryList){
            actualResult = actualResult + physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(category.intValue(),7);
        }
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_Return_SUM_OF_RELATIVE_SPEED_CATEGORY_FOR_ALL_HOUR(){
        Double expectedResult=148.0;
        Double actualResult=physsimCalcLinkStats.getRelativeSpeedCountOfSpecificCategory(0);
        assertEquals(expectedResult, actualResult);
    }

}
