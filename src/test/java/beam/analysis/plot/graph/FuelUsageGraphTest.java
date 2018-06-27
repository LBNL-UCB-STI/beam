package beam.analysis.plot.graph;

import beam.analysis.plots.FuelUsageStats;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

import static beam.analysis.plot.graph.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class FuelUsageGraphTest {
    private FuelUsageStats fuelUsageStats = new FuelUsageStats();

    @BeforeClass
    public static void setUpClass() {
        createDummySimWithXML();
    }

    @Test @Ignore
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
