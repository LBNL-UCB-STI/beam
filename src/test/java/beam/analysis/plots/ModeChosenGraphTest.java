package beam.analysis.plots;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ModeChosenGraphTest {
    private ModeChosenStats modeChosenStats = new ModeChosenStats(new ModeChosenStats.ModeChosenComputation());

    @BeforeClass
    public static void setUpClass() {
        GraphTestUtil.createDummySimWithXML();
    }

    /*@Test
    public void testShouldPassShouldReturnModeChoseEventCarOccurrence() {
        int expectedResult = 33;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.CAR, maxHour);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventDriveTransitOccurrence() {
        int expectedResult = 1;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.DRIVE_TRANS, maxHour);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventRideHailOccurrence() {
        int expectedResult = 20;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.RIDE_HAIL, maxHour);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkOccurrence() {
        int expectedResult = 41;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.WALK, maxHour);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkTransitOccurrence() {
        int expectedResult = 11;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.WALK_TRANS, maxHour);
        assertEquals(expectedResult, actualResult);
    }*/

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForSpecificHour() {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHail count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
        int expectedResultOfMode[] = {14, 1, 9, 15, 10};
        int actualResultOfMode[] = new int[5];
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        /*actualResultOfMode[0] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.CAR, maxHour, 6);
        actualResultOfMode[1] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.DRIVE_TRANS, maxHour, 6);
        actualResultOfMode[2] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.RIDE_HAIL, maxHour, 6);
        actualResultOfMode[3] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.WALK, maxHour, 6);
        actualResultOfMode[4] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(GraphTestUtil.WALK_TRANS, maxHour, 6);*/
        assertArrayEquals(expectedResultOfMode, actualResultOfMode);
    }

    private int getMaxHour(List<Integer> hoursList) {
        return hoursList.get(hoursList.size() - 1);
    }
}
