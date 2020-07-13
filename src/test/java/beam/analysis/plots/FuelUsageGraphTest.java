package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class FuelUsageGraphTest {
    private static class FuelUsageHandler implements BasicEventHandler {

        private final FuelUsageAnalysis fuelUsageStats;

        FuelUsageHandler(FuelUsageAnalysis fuelUsageStats) {
            this.fuelUsageStats = fuelUsageStats;
        }

        @Override
        public void handleEvent(Event event) {
            if (event instanceof PathTraversalEvent ) {
                fuelUsageStats.processStats(event);
            }
        }
    }

    private Map<Integer, Map<String, Double>> stats;

    private final FuelUsageAnalysis fuelUsageStats = new FuelUsageAnalysis(new FuelUsageAnalysis.FuelUsageStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }
    }, true, null);

    @Before
    public void setUpClass() {
        GraphTestUtil.createDummySimWithXML(new FuelUsageHandler(fuelUsageStats));
        fuelUsageStats.compute();
    }

    @Test
    public void testShouldPassShouldReturnPathTraversalEventCarFuel() {
        long expectedResult = 675705873L;
        int maxHour = getMaxHour(stats.keySet());
        long actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(CAR, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPathTraversalBusFuel() {
        long expectedResult = 135249995867L;
        int maxHour = getMaxHour(stats.keySet());
        long actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(BUS, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPathTraversalEventSubwayFuel() {
        long expectedResult = 0L;
        int maxHour = getMaxHour(stats.keySet());
        long actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(SUBWAY, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPathTraversalEventWalkFuel() {
        long expectedResult = 1787333L;
        int maxHour = getMaxHour(stats.keySet());
        long actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(WALK, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    private int getMaxHour(Set<Integer> hoursSet) {
        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hoursSet);
        return hoursList.get(hoursList.size() - 1);
    }

    private long getFuelageHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stats) {
        double count = 0;
        double[] modeOccurrencePerHour = getFuelageHourDataAgainstMode(modeChosen, maxHour, stats);
        for (double aModeOccurrencePerHour : modeOccurrencePerHour) {
            count = count + aModeOccurrencePerHour;
        }
        return Math.round(count);
    }

    private double[] getFuelageHourDataAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stats) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        int index = 0;
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Double> hourData = stats.get(hour);
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
