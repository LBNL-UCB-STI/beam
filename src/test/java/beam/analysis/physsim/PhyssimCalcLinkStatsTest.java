package beam.analysis.physsim;

import beam.sim.BeamConfigChangesObservable;
import beam.sim.config.BeamConfig;
import beam.utils.TestConfigUtils;
import com.typesafe.config.ConfigValueFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PhyssimCalcLinkStatsTest {

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimEvents-relative-speeds.xml";
    private static final String NETWORK_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimNetwork-relative-speeds.xml";

    private static PhyssimCalcLinkStats physsimCalcLinkStats;

    @BeforeClass
    public static void createDummySimWithXML() {

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        MatsimNetworkReader matsimNetworkReader = new MatsimNetworkReader(network);
        matsimNetworkReader.readFile(NETWORK_FILE_PATH);

        TravelTimeCalculatorConfigGroup defaultTravelTimeCalculator = config.travelTimeCalculator();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(network, defaultTravelTimeCalculator);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(travelTimeCalculator);

        BeamConfig beamConfig = BeamConfig.apply(TestConfigUtils.testConfig("test/input/equil-square/equil-0.001k.conf").resolve().withValue("beam.physsim.quick_fix_minCarSpeedInMetersPerSecond", ConfigValueFactory.fromAnyRef(0.0)));
        physsimCalcLinkStats = new PhyssimCalcLinkStats(network, null,  beamConfig, defaultTravelTimeCalculator, new BeamConfigChangesObservable(beamConfig) );

        //physsimCalcLinkStats = new PhyssimCalcLinkStats(network, null, null);

        physsimCalcLinkStats.notifyIterationStarts(eventsManager, defaultTravelTimeCalculator);

        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(EVENTS_FILE_PATH);
        physsimCalcLinkStats.notifyIterationEnds(0, travelTimeCalculator.getLinkTravelTimes());
    }

    @Test
    public void testShouldPassShouldReturnCountRelativeSpeedOfSpecificHour() {
        Double expectedResult = 7.0;
        Double actualResult = physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(0, 7);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnCountOfAllRelativeSpeedCategoryForSpecificHour() {
        List<Double> relativeSpeedCategoryList = physsimCalcLinkStats.getSortedListRelativeSpeedCategoryList();
        Double expectedResult = 260.0;
        Double actualRelativeSpeedSum = 0.0;
        for (Double category : relativeSpeedCategoryList) {
            actualRelativeSpeedSum = actualRelativeSpeedSum + physsimCalcLinkStats.getRelativeSpeedOfSpecificHour(category.intValue(), 7);
        }
        assertEquals(expectedResult, actualRelativeSpeedSum);
    }

    @Test
    public void testShouldPassShouldReturnSumOfRelativeSpeedForSpecificHour() {
        Double expectedResult = 111.0;
        Double actualResult = physsimCalcLinkStats.getRelativeSpeedCountOfSpecificCategory(0);
        assertEquals(expectedResult, actualResult);
    }

}
