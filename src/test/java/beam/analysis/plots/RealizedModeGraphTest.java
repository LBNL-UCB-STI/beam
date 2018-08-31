package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.ReplanningEvent;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class RealizedModeGraphTest {
    private static class RealizedModeHandler implements BasicEventHandler {

        private final RealizedModeStats realizedModeStats;

        RealizedModeHandler(RealizedModeStats stats) {
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
    private RealizedModeStats realizedModeStats = new RealizedModeStats(new RealizedModeStats.RealizedModesStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }
    });

    @Before
    public void setUpCRC() {
        stats = new HashMap<>();
        createDummySimWithXML(new RealizedModeHandler(realizedModeStats));
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        int expectedWalkResult = 17;
        int expectedCarResult = 15;
        int expectedRideHailResult = 11;
        int expectedOtherResult = 4;
        int hour = 6;

        int actaulWalkResult = stats.get(hour).get(WALK);
        int actaulCarResult = stats.get(hour).get(CAR);
        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        int actaulOtherResult = stats.get(hour).get(OTHERS);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCRCUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 3;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;
        int hour = 7;

        int actaulWalkResult = stats.get(hour).get(WALK);
        int actaulCarResult = stats.get(hour).get(CAR);
        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        int actaulOtherResult = stats.get(hour).get(OTHERS);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForNestedCRCRCUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 4;
        int expectedOtherResult = 3;
        int hour = 8;

        int actaulWalkResult = stats.get(hour).get(WALK);
        int actaulCarResult = stats.get(hour).get(CAR);
        int actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        int actaulOtherResult = stats.get(hour).get(OTHERS);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }

    @Test // when replanning and upper mode choice are in same hour
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCForDifferentHoursTypeA() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;

        int actaulCarResult = stats.get(9).get(CAR);
        int actaulRideHailResult = stats.get(9).get(RIDE_HAIL);
        int actaulOtherResult = stats.get(9).get(OTHERS);
        int actaulWalkResult = stats.get(10).get(WALK);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }

    @Test// when replanning and lower mode choice are in same hour
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCForDifferentHoursTypeB() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;

        int actaulCarResult = stats.get(11).get(CAR);
        int actaulRideHailResult = stats.get(11).get(RIDE_HAIL);
        int actaulOtherResult = stats.get(12).get(OTHERS);
        int actaulWalkResult = stats.get(12).get(WALK);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }
}