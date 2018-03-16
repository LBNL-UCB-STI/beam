package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.analysis.plots.DeadHeadingStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DeadHeadingGraphTest {
    private DeadHeadingStats deadHeadingStats = new DeadHeadingStats();
   static {
       GraphTestUtil.createDummySimWithXML();
    }

    private static String CAR = "car";
    private static String BUS = "bus";
    private static String SUBWAY = "subway";


    @Test
    public void testShouldPassShouldReturnPassengerPerTripInBusForFirstBucket()  {
        int expectedResult=141;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPassengerPerTripInBusForSecondBucket()  {
        int expectedResult=2;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(1,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPassengerPerTripInSubWayForFirstBucket()  {
        int expectedResult=8;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,SUBWAY);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnPassengerPerTripForCar()  {
        int expectedResult=34;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,CAR);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnDeadHeadingTnc0()  {
        int expectedResult=18;
        int actualResult = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnDeadHeadingAllDistanceForSpecificHour()  {
        int expectedResultOfHour[]={9,23,0,0,0,0,0};
        int actualResultOfHour[] = new int[7];
        for(int i=0;i<7;i++){
            actualResultOfHour[i] = deadHeadingStats.getDeadHeadingTnc0HourDataCount(i,6);
        }
       assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }
    @Test
    public void testShouldPassShouldReturnDeadHeadingPassengerPerTripForSpecificHour()  {
        int expectedResultOfHour[]={9,9,0,0,0,0,0};
        int actualResultOfHour[] = new int[7];
        for(int i=0;i<7;i++){
            actualResultOfHour[i] = deadHeadingStats.getPassengerPerTripCountForSpecificHour(i,"tnc",6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }


}
