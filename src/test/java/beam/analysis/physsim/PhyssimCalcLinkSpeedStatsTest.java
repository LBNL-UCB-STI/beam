package beam.analysis.physsim;

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
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhyssimCalcLinkSpeedStatsTest {

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimEvents-relative-speeds.xml";
    private static final String NETWORK_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimNetwork-relative-speeds.xml";

    private static PhyssimCalcLinkSpeedStats physsimCalcLinkSpeedStats;
    private static TravelTimeCalculator travelTimeCalculator;

    @BeforeClass
    public static void createDummySimWithXML() {

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        MatsimNetworkReader matsimNetworkReader = new MatsimNetworkReader(network);
        matsimNetworkReader.readFile(NETWORK_FILE_PATH);

        TravelTimeCalculatorConfigGroup defaultTravelTimeCalculator = config.travelTimeCalculator();
        travelTimeCalculator = new TravelTimeCalculator(network, defaultTravelTimeCalculator);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(travelTimeCalculator);

        physsimCalcLinkSpeedStats = new PhyssimCalcLinkSpeedStats(network, null, null);

        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(EVENTS_FILE_PATH);
        physsimCalcLinkSpeedStats.notifyIterationEnds(0, travelTimeCalculator.getLinkTravelTimes());
    }

    @Test
    public void shouldReturnAverageRelativeSpeedPercentageOfSpecificBin() {
        double expectedResult = 96.0;
        double actualResult = physsimCalcLinkSpeedStats.getAverageSpeedPercentageOfBin(23, travelTimeCalculator.getLinkTravelTimes());
        assertEquals(expectedResult, Math.ceil(actualResult),0);
    }

    @Test
    public void shouldNotContainAverageRelativeSpeedPercentageOfHundredForAllBins() {
        double[] actualResult = physsimCalcLinkSpeedStats.getAverageSpeedPercentagesOfAllBins(travelTimeCalculator.getLinkTravelTimes());
        long nonHundredPercentages = Arrays.stream(actualResult).filter(f -> f != 100.0).count();
        assertTrue(nonHundredPercentages != 0);
    }

    @Test
    public void shouldHaveDomainAxisRangeEqualToNumberOfBins() {
        int binCount = physsimCalcLinkSpeedStats.getNumberOfBins();
        double[] actualResult = physsimCalcLinkSpeedStats.getAverageSpeedPercentagesOfAllBins(travelTimeCalculator.getLinkTravelTimes());
        assertEquals(binCount,actualResult.length);
    }

}
