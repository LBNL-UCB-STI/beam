package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.sim.config.BeamConfig;
import beam.sim.metrics.NoOpSimulationMetricCollector$;
import beam.utils.TestConfigUtils;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ModeChosenGraphTest {

    private static class ModeChosenHandler implements BasicEventHandler {

        private final ModeChosenAnalysis modeChoseStats;

        ModeChosenHandler(ModeChosenAnalysis modeChoseStats) {
            this.modeChoseStats = modeChoseStats;
        }

        @Override
        public void handleEvent(Event event) {
            if (event instanceof ModeChoiceEvent || event.getEventType().equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)) {
                modeChoseStats.processStats(event);
            }
        }
    }

    private Map<Integer, Map<String, Integer>> stats;

    private final ModeChosenAnalysis modeChoseStats = new ModeChosenAnalysis(NoOpSimulationMetricCollector$.MODULE$, new ModeChosenAnalysis.ModeChosenComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Integer>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }
    }, BeamConfig.apply(TestConfigUtils.testConfig("test/input/beamville/beam.conf").resolve()), null);

    @Before
    public void setUpClass() {
        createDummySimWithXML(new ModeChosenHandler(modeChoseStats));
        modeChoseStats.compute();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventCarOccurrence() {

        int expectedResult = 46;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getHoursDataCountOccurrenceAgainstMode(CAR, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventDriveTransitOccurrence() {
        int expectedResult = 0;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS, maxHour, stats);
        assertEquals(expectedResult, actualResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventRideHailOccurrence() {
        int expectedResult = 5;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getHoursDataCountOccurrenceAgainstMode(RIDE_HAIL, maxHour, stats);
        assertEquals(expectedResult, actualResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkOccurrence() {
        int expectedResult = 6;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getHoursDataCountOccurrenceAgainstMode(WALK, maxHour, stats);
        assertEquals(expectedResult, actualResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkTransitOccurrence() {
        int expectedResult = 2;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getHoursDataCountOccurrenceAgainstMode(WALK_TRANS, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForSpecificHour() {
        /*
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHail count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
        int[] expectedResultOfMode = {17, 0, 2, 1};
        int[] actualResultOfMode = new int[4];
        int maxHour = getMaxHour(stats.keySet());
        actualResultOfMode[0] = getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.CAR, maxHour, 6, stats);
        actualResultOfMode[1] = getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.DRIVE_TRANS, maxHour, 6, stats);
        actualResultOfMode[2] = getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.RIDE_HAIL, maxHour, 6, stats);
        actualResultOfMode[3] = getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.WALK_TRANS, maxHour, 6, stats);
        assertArrayEquals(expectedResultOfMode, actualResultOfMode);
    }

    private int getMaxHour(Set<Integer> hoursSet) {
        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hoursSet);
        return hoursList.get(hoursList.size() - 1);
    }

    private int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Integer>> stats) {
        double[] modeOccurrencePerHour = getHourDataAgainstMode(modeChosen, maxHour, stats);
        return (int) Arrays.stream(modeOccurrencePerHour).sum();
    }

    private int getHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour, int hour, Map<Integer, Map<String, Integer>> stats) {
        double[] modeOccurrencePerHour = getHourDataAgainstMode(modeChosen, maxHour, stats);
        return (int) Math.ceil(modeOccurrencePerHour[hour]);
    }

    private double[] getHourDataAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Integer>> stats) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Integer> hourData = stats.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[index] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[index] = 0;
            }
            index = index + 1;
        }
        return modeOccurrencePerHour;
    }
}