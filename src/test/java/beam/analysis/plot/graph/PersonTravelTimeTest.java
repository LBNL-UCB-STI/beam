package beam.analysis.plot.graph;

import beam.analysis.plots.PersonTravelTimeStats;
import org.junit.BeforeClass;
import org.junit.Test;

import static beam.analysis.plot.graph.GraphTestUtil.*;
import static org.junit.Assert.assertArrayEquals;

public class PersonTravelTimeTest {
    private PersonTravelTimeStats personTravelTimeStats = new PersonTravelTimeStats();

    @BeforeClass
    public static void setUpClass() {
        createDummySimWithXML();
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
        int actualResultOfMode[] = {
                personTravelTimeStats.getAvgCountForSpecificHour(CAR, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(DRIVE_TRANS, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(RIDE_HAIL, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(WALK, 6),
                personTravelTimeStats.getAvgCountForSpecificHour(WALK_TRANS, 6)
        };
        assertArrayEquals(expectedResultOfMode, actualResultOfMode);
    }

}
