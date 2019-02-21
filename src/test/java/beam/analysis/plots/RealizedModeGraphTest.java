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

    private Map<Integer, Map<String, Double>> stats;
    private static final String BASE_PATH = Paths.get(".").toAbsolutePath().toString();
    private static final String EVENT_FILE_PATH = BASE_PATH + "/test/input/beamville/test-data/replanning.event.xml";
    private RealizedModeAnalysis realizedModeStats = new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }

    },true);

    @Before
    public void setUpCRC() {
        createDummySimWithXML(new RealizedModeHandler(realizedModeStats),EVENT_FILE_PATH);
        realizedModeStats.updatePersonCount();
        realizedModeStats.buildModesFrequencyDataset();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        Double expectedWalkResult = 4.0;
        Double expectedCarResult = 7.5;
        Double expectedRideHailResult = 2.0;
        int hour = 19;

        Double actaulWalkResult = stats.get(hour).get(WALK);
        Double actaulCarResult = stats.get(hour).get(CAR);
        Double actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCRCUnitHour() {

        Double expectedDriveTransitResult = 13.0/6.0;
        Double expectedCarResult = 8.0/3.0;
        Double expectedRideHailResult = 2.0;
        Double expectedWalkTransitResult = 7.0/3.0;
        int hour = 6;

        Double actaulDriveTransitResult = stats.get(hour).get(DRIVE_TRANS);
        Double actaulCarResult = stats.get(hour).get(CAR);
        Double actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        Double actaulWalkTransitResult = stats.get(hour).get(WALK_TRANS);

        assertEquals(expectedDriveTransitResult, actaulDriveTransitResult , 0.0000001);
        assertEquals(expectedCarResult, actaulCarResult, 0.0000001 );
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedWalkTransitResult, actaulWalkTransitResult,0.0000001);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForRepetitionModes() {

        Double expectedCarResult = 4.0 / 3.0;
        Double expectedRideHailResult = 7.0d / 3.0;
        Double expectedWalkTransitResult = 7.0 / 3.0;
        int hour = 8;

        Double actaulCarResult = stats.get(hour).get(CAR);
        Double actaulRideHailResult = stats.get(hour).get(RIDE_HAIL);
        Double actaulWalkTransitResult = stats.get(hour).get(WALK_TRANS);

        assertEquals(expectedCarResult, actaulCarResult, 0.0000001);
        assertEquals(expectedRideHailResult, actaulRideHailResult, 0.0000001);
        assertEquals(expectedWalkTransitResult, actaulWalkTransitResult, 0.0000001);

    }

    @Test
    public void testShouldPassShouldReturnReplannigCountModeChoice(){
        Integer expectedCount = 5;
        int hour = 19;
        Integer actualCount = realizedModeStats.getAffectedModeCount().get(hour);
        assertEquals(expectedCount ,actualCount);
    }

}
