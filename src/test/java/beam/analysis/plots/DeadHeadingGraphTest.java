package beam.analysis.plots;

import beam.sim.metrics.NoOpSimulationMetricCollector$;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

public class DeadHeadingGraphTest {
    private final OutputDirectoryHierarchy ioController = mock(OutputDirectoryHierarchy.class);
    private final DeadHeadingAnalysis deadHeadingStats = new DeadHeadingAnalysis(NoOpSimulationMetricCollector$.MODULE$,
            true, ioController);

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
        int expectedResult = 2;
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
        int expectedResult = 7;
        int actualResult = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnDeadHeadingAllDistanceForSpecificHour() {
        int[] expectedResultOfHour = {0, 3, 3, 0, 0, 4, 0};
        int[] actualResultOfHour = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getDeadHeadingTnc0HourDataCount(i, 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }

    @Test
    public void testShouldPassShouldReturnDeadHeadingPassengerPerTripForSpecificHour() {
        int[] expectedResultOfHour = {0, 2, 2, 0, 0, 1, 0};
        int[] actualResultOfHour = new int[7];
        for (int i = 0; i < 7; i++) {
            actualResultOfHour[i] = deadHeadingStats.getPassengerPerTripCountForSpecificHour(i, "tnc", 6);
        }
        assertArrayEquals(expectedResultOfHour, actualResultOfHour);
    }
}
