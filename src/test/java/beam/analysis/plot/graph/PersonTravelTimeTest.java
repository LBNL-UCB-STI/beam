package beam.analysis.plot.graph;

import beam.analysis.plots.PersonTravelTimeStats;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class PersonTravelTimeTest {
    private PersonTravelTimeStats personTravelTimeStats = new PersonTravelTimeStats();
      static {
        GraphTestUtil.createDummySimWithXML();
    }
    private static String CAR = "car";
    private static String DRIVE_TRANS = "drive_transit";
    private static String RIDE_HAILING = "ride_hailing";
    private static String WALK = "walk" ;
    private static String WALK_TRANS = "walk_transit";

    @Test
    public void testShouldPassShouldReturnAvgTimeForSpecificHour()  {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHailing count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
        int expectedResultOfMode[]={3,0,4,32,17};
        int actualResultOfMode[]= new int[5];
        actualResultOfMode[0] = personTravelTimeStats.getAvgCountForSpecificHour(CAR,6);
        actualResultOfMode[1] = personTravelTimeStats.getAvgCountForSpecificHour(DRIVE_TRANS,6);
        actualResultOfMode[2] = personTravelTimeStats.getAvgCountForSpecificHour(RIDE_HAILING,6);
        actualResultOfMode[3] = personTravelTimeStats.getAvgCountForSpecificHour(WALK ,6);
        actualResultOfMode[4] = personTravelTimeStats.getAvgCountForSpecificHour(WALK_TRANS,6);
        assertArrayEquals(expectedResultOfMode,actualResultOfMode);
    }

}
