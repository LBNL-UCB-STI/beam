package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

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
    private RealizedModeAnalysis realizedModeStats = new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }

    },true);

    @Before
    public void setUpCRC() {
        createDummySimWithXML(new RealizedModeHandler(realizedModeStats));
        realizedModeStats.buildModesFrequencyDataset();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        int expectedWalkResult = 37;
        int expectedCarResult = 3;
        int expectedRideHailResult = 7;
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

        int expectedDriveTransitResult = 16;
        int expectedCarResult = 2;
        int expectedRideHailResult = 7;
        int expectedWalkTransitResult = 20;
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
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForNestedCRCRCUnitHour() {

        int expectedRideHailResult = 2;
        int hour = 10;

        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);

        assertEquals(expectedRideHailResult, actaulRideHailResult);
    }

}