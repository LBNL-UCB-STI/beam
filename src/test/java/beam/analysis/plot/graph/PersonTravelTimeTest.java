package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.analysis.plots.PersonTravelTimeStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class PersonTravelTimeTest {
    static GraphsStatsAgentSimEventsListener graphsFromAgentSimEvents = new GraphsStatsAgentSimEventsListener();
    private PersonTravelTimeStats personTravelTimeStats = new PersonTravelTimeStats();
    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH+"/test/input/beamville/transitVehicles.xml";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/beamville/test-data/beamville.events.xml";
    static {
        createDummySimWithXML();
    }
    private static String CAR = "car";
    private static String DRIVE_TRANS = "drive_transit";
    private static String RIDE_HAILING = "ride_hailing";
    private static String WALK = "walk" ;
    private static String WALK_TRANS = "walk_transit";

    @Test
    public void test_Should_Pass_Should_RETURN_AVG_TIME_FOR_SPECIFIC_HOUR()  {
        int expectedResultCar = 3;
        int expectedResultDriveTran = 0;
        int expectedResultRideHailing = 4;
        int expectedResultWalk=32;
        int expectedResultWalkTran=17;

        int actualResultCar = personTravelTimeStats.getAvgCountForSpecificHour(CAR,6);
        int actualResultDriveTran = personTravelTimeStats.getAvgCountForSpecificHour(DRIVE_TRANS,6);
        int actualResultRideHailing = personTravelTimeStats.getAvgCountForSpecificHour(RIDE_HAILING,6);
        int actualResultWalk = personTravelTimeStats.getAvgCountForSpecificHour(WALK ,6);
        int actualResultWalkTran = personTravelTimeStats.getAvgCountForSpecificHour(WALK_TRANS,6);
        assertEquals(expectedResultCar, actualResultCar);
        assertEquals(expectedResultDriveTran, actualResultDriveTran);
        assertEquals(expectedResultRideHailing, actualResultRideHailing);
        assertEquals(expectedResultWalk, actualResultWalk);
        assertEquals(expectedResultWalkTran, actualResultWalkTran);
    }


    private synchronized static void createDummySimWithXML(){
        PathTraversalSpatialTemporalTableGenerator.loadVehicles(TRANSIT_VEHICLE_FILE_PATH);
        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler(graphsFromAgentSimEvents);
        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(EVENTS_FILE_PATH);
    }
    private int getMaxHour(List<Integer> hoursList){
        return hoursList.get(hoursList.size() - 1);
    }
}
