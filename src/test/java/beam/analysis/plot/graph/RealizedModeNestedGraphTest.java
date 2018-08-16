package beam.analysis.plot.graph;

import beam.analysis.plots.RealizedModeStats;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.Map;
import static beam.analysis.plot.graph.GraphTestRealizedUtil.createDummySimWithNestedCRCRCXML;
import static org.junit.Assert.assertEquals;

public class RealizedModeNestedGraphTest {
    private RealizedModeStats realizedModeStats = new RealizedModeStats();

    @BeforeClass
    public static void setUpCRC() {
        createDummySimWithNestedCRCRCXML();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCCRUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 3;
        int expectedOtherResult = 3;
        int hour = 6;
        Map<Integer, Map<String,Integer>> data = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );
        int actaulWalkResult = data.get(hour).get(GraphTestRealizedUtil.WALK);
        int actaulCarResult = data.get(hour).get(GraphTestRealizedUtil.CAR);
        int actaulRideHailResult = data.get(hour).get(GraphTestRealizedUtil.RIDE_HAIL);
        int actaulOtherResult = data.get(hour).get(GraphTestRealizedUtil.OTHERS);

        assertEquals(expectedWalkResult,actaulWalkResult );
        assertEquals(expectedCarResult,actaulCarResult );
        assertEquals(expectedRideHailResult,actaulRideHailResult );
        assertEquals(expectedOtherResult,actaulOtherResult);

    }



}
