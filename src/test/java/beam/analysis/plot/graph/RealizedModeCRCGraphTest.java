package beam.analysis.plot.graph;

import beam.analysis.plots.RealizedModeStats;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.Map;
import static beam.analysis.plot.graph.GraphTestRealizedUtil.createDummySimWithCRCXML;
import static org.junit.Assert.assertEquals;

public class RealizedModeCRCGraphTest {
    private RealizedModeStats realizedModeStats = new RealizedModeStats();

    @BeforeClass
    public static void setUpCRC() {
        createDummySimWithCRCXML();
    }

    @Test
    public void testShouldPassShouldReturnModeChoseEventOccurrenceForCRCUnitHour() {

        int expectedWalkResult = 2;
        int expectedCarResult = 2;
        int expectedRideHailResult = 2;
        int expectedOtherResult = 4;
        int hour = 6;
        Map<Integer,Map<String,Integer>> data = realizedModeStats.getHoursDataCountOccurrenceAgainstMode( );
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
