package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class RealizedModeGraphTest {
    private static class RealizedModeHandler implements BasicEventHandler {

        private final RealizedModeAnalysis realizedModeStats;

        RealizedModeHandler(RealizedModeAnalysis stats) {
            this.realizedModeStats = stats;
        }

        @Override
        public void handleEvent(Event event) {
            if (event instanceof ReplanningEvent || event.getEventType().equalsIgnoreCase(ReplanningEvent.EVENT_TYPE)) {
                realizedModeStats.processStats(event);
            }
            if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)) {
                realizedModeStats.processStats(event);
            }
        }
    }

    private Map<Integer, Map<String, Integer>> stats;
    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENT_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/replanning.event.xml";

    private RealizedModeAnalysis realizedModeStats = new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }

    },true);

    @Before
    public void setUpCRC() {
        createDummySimWithXML(new RealizedModeHandler(realizedModeStats),EVENT_FILE_PATH);
        realizedModeStats.buildModesFrequencyDataset();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        int expectedWalkResult = 3;
        int expectedCarResult = 5;
        int expectedRideHailResult = 1;
        int hour = 19;

        int actaulWalkResult = stats.get(hour).get(WALK);
        int actaulCarResult = stats.get(hour).get(CAR);
        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCRCUnitHour() {

        int expectedDriveTransitResult = 1;
        int expectedCarResult = 1;
        int expectedRideHailResult = 1;
        int expectedWalkTransitResult = 2;
        int hour = 6;

        int actaulDriveTransitResult = stats.get(hour).get(DRIVE_TRANS);
        int actaulCarResult = stats.get(hour).get(CAR);
        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        int actaulWalkTransitResult = stats.get(hour).get(WALK_TRANS);

        assertEquals(expectedDriveTransitResult, actaulDriveTransitResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedWalkTransitResult, actaulWalkTransitResult);

    }

    @Test
    public void testShouldPassShouldReturnReplannigCountModeChoice(){
        Integer expectedCount = 10;
        int hour = 19;
        Integer actualCount = realizedModeStats.getAffectedModeCount().get(hour);
        assertEquals(expectedCount ,actualCount);
    }

}