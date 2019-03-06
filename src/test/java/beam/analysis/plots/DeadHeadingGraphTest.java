package beam.analysis.plots;

import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DeadHeadingGraphTest {
    private final DeadHeadingAnalysis deadHeadingStats = new DeadHeadingAnalysis(true);

    @BeforeClass
    public static void setUpClass() {
        GraphTestUtil.createDummySimWithXML();
    }

    @Test
    public void testShouldPassShouldReturnPassengerPerTripInBusForFirstBucket() {
        int expectedResult = 1;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, GraphTestUtil.BUS);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnPassengerPerTripInBusForSecondBucket() {
        int expectedResult = 6;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(1, GraphTestUtil.BUS);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPassengerPerTripInSubWayForFirstBucket() {
        int expectedResult = 8;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, GraphTestUtil.SUBWAY);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    @Ignore
    public void testShouldPassShouldReturnPassengerPerTripForCar() {
        int expectedResult = 34;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0, GraphTestUtil.CAR);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnDeadHeadingTnc0() {
        int expectedResult = 0;
        int actualResult = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnDeadHeadingAllDistanceForSpecificHour() {
        int expectedResultOfHour[] = {0, 11, 24, 0, 0, 0, 0};
        int actualResultOfHour[] = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getDeadHeadingTnc0HourDataCount(i, 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }

    @Test
    public void testShouldPassShouldReturnDeadHeadingPassengerPerTripForSpecificHour() {
        int expectedResultOfHour[] = {0, 7, 7, 0, 0, 0, 0};
        int actualResultOfHour[] = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getPassengerPerTripCountForSpecificHour(i, "tnc", 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }
}
