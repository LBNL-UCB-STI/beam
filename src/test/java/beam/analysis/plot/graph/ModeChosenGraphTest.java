package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.analysis.plots.ModeChosenStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ModeChosenGraphTest {
    private ModeChosenStats modeChosenStats = new ModeChosenStats();
    static {
        GraphTestUtil.createDummySimWithXML();
    }
    private static String CAR = "car";
    private static String DRIVE_TRANS = "drive_transit";
    private static String RIDE_HAILING = "ride_hailing";
    private static String WALK = "walk" ;
    private static String WALK_TRANS = "walk_transit";
    @Test
    public void testShouldPassShouldReturnModeChoseEventCarOccurrence()  {

        int expectedResult=33 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnModeChoseEventDriveTransitOccurrence()  {
        int expectedResult=1 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnModeChoseEventRideHailingOccurrence()  {
        int expectedResult=20 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkOccurrence()  {
        int expectedResult=41 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnModeChoseEventWalkTransitOccurrence()  {
        int expectedResult=11 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForSpecificHour()  {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHailing count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
        int expectedResultOfMode[]={14,1,9,15,10};
        int actualResultOfMode[]= new int[5];
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        actualResultOfMode[0] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour,6);
        actualResultOfMode[1] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour,6);
        actualResultOfMode[2] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour,6);
        actualResultOfMode[3] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour,6);
        actualResultOfMode[4] = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour,6);
        assertArrayEquals(expectedResultOfMode,actualResultOfMode);
    }
    private int getMaxHour(List<Integer> hoursList){
        return hoursList.get(hoursList.size() - 1);
    }
}
