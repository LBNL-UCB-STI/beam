package beam.analysis.plot.graph;

import beam.analysis.plots.DeadHeadingStats;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static beam.analysis.plot.graph.GraphTestUtil.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DeadHeadingGraphTest {
    private DeadHeadingStats deadHeadingStats = new DeadHeadingStats();

    @BeforeClass
    public static void setUpClass() {
        createDummySimWithXML();
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPassengerPerTripInBusForFirstBucket() {
        int expectedResult = 141;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, BUS);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPassengerPerTripInBusForSecondBucket() {
        int expectedResult = 2;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(1, BUS);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPassengerPerTripInSubWayForFirstBucket() {
        int expectedResult = 8;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, SUBWAY);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPassengerPerTripForCar() {
        int expectedResult = 34;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, CAR);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnDeadHeadingTnc0() {
        int expectedResult = 18;
        int actualResult = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnDeadHeadingAllDistanceForSpecificHour() {
        int expectedResultOfHour[] = {9, 23, 0, 0, 0, 0, 0};
        int actualResultOfHour[] = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getDeadHeadingTnc0HourDataCount(i, 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnDeadHeadingPassengerPerTripForSpecificHour() {
        int expectedResultOfHour[] = {9, 9, 0, 0, 0, 0, 0};
        int actualResultOfHour[] = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getPassengerPerTripCountForSpecificHour(i, "tnc", 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }
}
