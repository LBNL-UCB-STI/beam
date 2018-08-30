package beam.analysis.plots;

import beam.analysis.plots.RealizedModeStats;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Map;
import java.util.Set;


import static beam.analysis.plots.GraphTestUtil.*;
import static org.junit.Assert.assertEquals;

public class RealizedModeGraphTest {
    private RealizedModeStats realizedModeStats = new RealizedModeStats(new RealizedModeStats.RealizedModesStatsComputation());

    @BeforeClass
    public static void setUpCRC() {
        createDummySimWithXML();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        int expectedWalkResult = 17;
        int expectedCarResult = 15;
        int expectedRideHailResult = 11;
        int expectedOtherResult = 4;
        int hour = 6;

        Map<Integer,Map<String,Integer>> data = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );
        int actaulWalkResult = data.get(hour).get(WALK);
        int actaulCarResult = data.get(hour).get(CAR);
        int actaulRideHailResult = data.get(hour).get(RIDE_HAIL);
        int actaulOtherResult = data.get(hour).get(OTHERS);

        assertEquals(expectedWalkResult,actaulWalkResult );
        assertEquals(expectedCarResult,actaulCarResult );
        assertEquals(expectedRideHailResult,actaulRideHailResult );
        assertEquals(expectedOtherResult,actaulOtherResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCRCUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 3;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;
        int hour = 7;

        Map<Integer, Map<String,Integer>> data1 = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );
        int actaulWalkResult = data1.get(hour).get(WALK);
        int actaulCarResult = data1.get(hour).get(CAR);
        int actaulRideHailResult = data1.get(hour).get(RIDE_HAIL);
        int actaulOtherResult = data1.get(hour).get(OTHERS);

        assertEquals(expectedWalkResult,actaulWalkResult );
        assertEquals(expectedCarResult,actaulCarResult );
        assertEquals(expectedRideHailResult,actaulRideHailResult );
        assertEquals(expectedOtherResult,actaulOtherResult);

    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForNestedCRCRCUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 4;
        int expectedOtherResult = 3;
        int hour = 8;

        Map<Integer, Map<String,Integer>> data2 = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );
        int actaulWalkResult = data2.get(hour).get( WALK);
        int actaulCarResult = data2.get(hour).get( CAR);
        int actaulRideHailResult = data2.get(hour).get( RIDE_HAIL);
        int actaulOtherResult = data2.get(hour).get( OTHERS);

        Map<RealizedModeStats.ModePerson,Integer> sets = realizedModeStats.getPersonMode();

        assertEquals(expectedWalkResult,actaulWalkResult );
        assertEquals(expectedCarResult,actaulCarResult );
        assertEquals(expectedRideHailResult,actaulRideHailResult );
        assertEquals(expectedOtherResult,actaulOtherResult);

    }

    @Test // when replanning and upper mode choice are in same hour
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCForDifferentHoursTypeA() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;

        Map<Integer, Map<String,Integer>> data3 = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );

        int actaulCarResult = data3.get(9).get( CAR);
        int actaulRideHailResult = data3.get(9).get( RIDE_HAIL);
        int actaulOtherResult = data3.get(9).get( OTHERS);
        int actaulWalkResult = data3.get(10).get( WALK);

        assertEquals(expectedWalkResult,actaulWalkResult );
        assertEquals(expectedCarResult,actaulCarResult );
        assertEquals(expectedRideHailResult,actaulRideHailResult );
        assertEquals(expectedOtherResult,actaulOtherResult);

    }

    @Test// when replanning and lower mode choice are in same hour
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCForDifferentHoursTypeB() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 4;

        Map<Integer, Map<String, Integer>> data3 = realizedModeStats.getHoursDataCountOccurrenceAgainstMode();
        int actaulCarResult = data3.get(11).get(CAR);
        int actaulRideHailResult = data3.get(11).get( RIDE_HAIL);
        int actaulOtherResult = data3.get(12).get( OTHERS);
        int actaulWalkResult = data3.get(12).get( WALK);

        assertEquals(expectedWalkResult, actaulWalkResult);
        assertEquals(expectedCarResult, actaulCarResult);
        assertEquals(expectedRideHailResult, actaulRideHailResult);
        assertEquals(expectedOtherResult, actaulOtherResult);

    }
}