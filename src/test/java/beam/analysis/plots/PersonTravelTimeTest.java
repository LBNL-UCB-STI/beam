package beam.analysis.plots;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class PersonTravelTimeTest {
    private PersonTravelTimeStats personTravelTimeStats = new PersonTravelTimeStats(new PersonTravelTimeStats.PersonTravelTimeComputation());

    @BeforeClass
    public static void setUpClass() {
        GraphTestUtil.createDummySimWithXML();
    }

    @Test
    public void testShouldPassShouldReturnAvgTimeForSpecificHour() {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHail count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
        int expectedResultOfMode[] = {3, 0, 4, 32, 17};
        /*int actualResultOfMode[] = {
                personTravelTimeStats.getAvgCountForSpecificHour(GraphTestUtil.CAR, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(GraphTestUtil.DRIVE_TRANS, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(GraphTestUtil.RIDE_HAIL, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(GraphTestUtil.WALK, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(GraphTestUtil.WALK_TRANS, 6)
        };*/
        //assertArrayEquals(expectedResultOfMode, actualResultOfMode);
    }

}
