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
import java.util.*;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhyssimCalcLinkSpeedDistributionStatsTest {

    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENTS_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimEvents-relative-speeds.xml";
    private static final String NETWORK_FILE_PATH = BASE_PATH + "/test/input/equil-square/test-data/physSimNetwork-relative-speeds.xml";

    private static PhyssimCalcLinkSpeedDistributionStats physsimCalcLinkSpeedDistributionStats;
    private static Network network;
    private final int binCount = 10;

    @BeforeClass
    public static void createDummySimWithXML() {

        Config config = ConfigUtils.createConfig();
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        network = scenario.getNetwork();
        MatsimNetworkReader matsimNetworkReader = new MatsimNetworkReader(network);
        matsimNetworkReader.readFile(NETWORK_FILE_PATH);

        TravelTimeCalculatorConfigGroup defaultTravelTimeCalculator = config.travelTimeCalculator();
        TravelTimeCalculator travelTimeCalculator = new TravelTimeCalculator(network, defaultTravelTimeCalculator);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(travelTimeCalculator);

        physsimCalcLinkSpeedDistributionStats = new PhyssimCalcLinkSpeedDistributionStats(network, null, null);

        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(EVENTS_FILE_PATH);
        physsimCalcLinkSpeedDistributionStats.notifyIterationEnds(0, travelTimeCalculator.getLinkTravelTimes());
    }

    private int getMaxSpeed(int binCount, Network network) {
        return Stream.iterate(0, x -> x)
                .limit(binCount)
                .flatMap(bin -> network.getLinks().values()
                        .stream()
                        .map(link -> link.getFreespeed(bin * 3600))
                        .map(fs -> (int) Math.round(fs)))
                .max(Comparator.comparing(Integer::valueOf)).orElse(0);
    }

    private Stream<Integer> getDistinctSpeeds(int binCount, Network network) {
        return Stream.iterate(0, x -> x)
                .limit(binCount)
                .flatMap(bin -> network.getLinks().values()
                        .stream()
                        .map(link -> link.getFreespeed(bin * 3600))
                        .map(fs -> (int) Math.round(fs)))
                .distinct();
    }

    @Test
    public void shouldCheckIfSumOfFrequenciesPerBinEqualsTotalNumberOfLinks() {
        int linksCount = network.getLinks().size();
        Map<Integer, Integer> freeFlowSpeedFrequencies = physsimCalcLinkSpeedDistributionStats.generateInputDataForFreeFlowSpeedGraph(binCount,network);
        int sumOfFrequencies = freeFlowSpeedFrequencies.values().stream().reduce((r, s) -> r + s).orElse(0);
        assertEquals(sumOfFrequencies/binCount, linksCount);
    }

    @Test
    public void shouldHaveAtleastOneLinkWithMaximumSpeed() {
        Map<Integer, Integer> freeFlowSpeedFrequencies = physsimCalcLinkSpeedDistributionStats.generateInputDataForFreeFlowSpeedGraph(binCount,network);
        int maxSpeed = this.getMaxSpeed(binCount, network);
        Integer linksWithMaxSpeed = freeFlowSpeedFrequencies.getOrDefault(maxSpeed, 0);//.stream().filter(f -> f.equals(Integer.valueOf(maxSpeed))).count();
        assertTrue(linksWithMaxSpeed > 0);
    }

    @Test
    public void shouldHaveDomainAxesEqualsToDistinctFreeFlowSpeeds() {
        Map<Integer, Integer> freeFlowSpeedFrequencies = physsimCalcLinkSpeedDistributionStats.generateInputDataForFreeFlowSpeedGraph(binCount,network);
        Stream<Integer> distinctSpeeds = this.getDistinctSpeeds(binCount, network).sorted();
        Iterator<?> iter1 = distinctSpeeds.iterator(), iter2 = freeFlowSpeedFrequencies.keySet().stream().sorted().iterator();
        while(iter1.hasNext() && iter2.hasNext())
            assertEquals(iter1.next(), iter2.next());
        assert !iter1.hasNext() && !iter2.hasNext();
    }

}
