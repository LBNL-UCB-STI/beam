package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.analysis.plots.FuelUsageStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FuelUsageGraphTest {
    private FuelUsageStats fuelUsageStats = new FuelUsageStats();
    static {
        GraphTestUtil.createDummySimWithXML();
    }
    private static String CAR = "car";
    private static String WALK = "walk" ;
    private static String BUS = "bus";
    private static String SUBWAY = "subway";
    @Test
    public void testShouldPassShouldReturnPathTraversalEventCarFuel()  {
        int expectedResult=965;//1114;//1113.5134131391999 ;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPathTraversalBusFuel()  {
        int expectedResult= 4237;//4236.828591738598;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(BUS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPathTraversalEventSubwayFuel()  {
        int expectedResult= 22;//21.71915184736;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(SUBWAY,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPathTraversalEventWalkFuel()  {
        int expectedResult= 34;//29;//28.3868926185;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    private int getMaxHour(List<Integer> hoursList){
        return hoursList.get(hoursList.size() - 1);
    }

}
