package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class FuelUsageGraphTest {
    private static class FuelUsageHandler implements BasicEventHandler {

        private final FuelUsageStats fuelUsageStats;

        FuelUsageHandler(FuelUsageStats fuelUsageStats) {
            this.fuelUsageStats = fuelUsageStats;
        }

        @Override
        public void handleEvent(Event event) {
            if (event instanceof PathTraversalEvent || event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
                fuelUsageStats.processStats(event);
            }
        }
    }

    private Map<Integer, Map<String, Double>> stats;

    private FuelUsageStats fuelUsageStats = new FuelUsageStats(new FuelUsageStats.FuelUsageStatsComputation() {
        @Override
        public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
            stats = stat.getFirst();
            return super.compute(stat);
        }
    });

    @Before
    public void setUpClass() {
        GraphTestUtil.createDummySimWithXML(new FuelUsageHandler(fuelUsageStats));
        fuelUsageStats.compute();
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPathTraversalEventCarFuel() {
        int expectedResult = 965;//1114;//1113.5134131391999 ;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(CAR, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPathTraversalBusFuel() {
        int expectedResult = 4237;//4236.828591738598;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(BUS, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPathTraversalEventSubwayFuel() {
        int expectedResult = 22;//21.71915184736;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(SUBWAY, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPathTraversalEventWalkFuel() {
        int expectedResult = 34;//29;//28.3868926185;
        int maxHour = getMaxHour(stats.keySet());
        int actualResult = getFuelageHoursDataCountOccurrenceAgainstMode(WALK, maxHour, stats);
        assertEquals(expectedResult, actualResult);
    }

    private int getMaxHour(Set<Integer> hoursSet) {
        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(hoursSet);
        return hoursList.get(hoursList.size() - 1);
    }

    private int getFuelageHoursDataCountOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stats) {
        double count = 0;
        double[] modeOccurrencePerHour = getFuelageHourDataAgainstMode(modeChosen, maxHour, stats);
        for (double aModeOccurrencePerHour : modeOccurrencePerHour) {
            count = count + aModeOccurrencePerHour;
        }
        return (int) Math.ceil(count);
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
