package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.CreateGraphsFromAgentSimEvents;
import beam.analysis.plots.DeadHeadingStats;
import beam.analysis.plots.FuelUsageStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DeadHeadingGraphTest {
    static CreateGraphsFromAgentSimEvents graphsFromAgentSimEvents = new CreateGraphsFromAgentSimEvents();
    private DeadHeadingStats deadHeadingStats = new DeadHeadingStats();
    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH+"/test/input/beamville/transitVehicles.xml";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/beamville/test-data/beamville.events.xml";
    static {
        createDummySimWithXML();
    }

    private static String CAR = "car";
    private static String BUS = "bus";
    private static String SUBWAY = "subway";


    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_BUS_IN_FIRST_BUCKET()  {
        int expectedResult=141;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_BUS_IN_SECOND_BUCKET()  {
        int expectedResult=2;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(1,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_SUBWAY_IN_FIRST_BUCKET()  {
        int expectedResult=8;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,SUBWAY);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_CAR()  {
        int expectedResult=34;
        int actualResult = deadHeadingStats.getBucketCountAgainstMode(0,CAR);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_DEAD_HEADING_TNC0()  {
        int expectedResult=18;
        int actualResult = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_DEAD_HEADING_ALL_DISTANCE_FOR_SPECIFIC()  {
        int expectedResultFor0=9;
        int expectedResultFor1=23;
        int expectedResultFor2=0;
        int expectedResultFor3=0;
        int expectedResultFor4=0;
        int expectedResultFor5=0;
        int expectedResultFor6=0;

        int actualResultFor0 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(0,6);
        int actualResultFor1 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(1,6);
        int actualResultFor2 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(2,6);
        int actualResultFor3 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(3,6);
        int actualResultFor4 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(4,6);
        int actualResultFor5 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(5,6);
        int actualResultFor6 = deadHeadingStats.getDeadHeadingTnc0HourDataCount(6,6);
        assertEquals(expectedResultFor0, actualResultFor0);
        assertEquals(expectedResultFor1, actualResultFor1);
        assertEquals(expectedResultFor2, actualResultFor2);
        assertEquals(expectedResultFor3, actualResultFor3);
        assertEquals(expectedResultFor4, actualResultFor4);
        assertEquals(expectedResultFor5, actualResultFor5);
        assertEquals(expectedResultFor6, actualResultFor6);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_DEAD_HEADING_PASSENGER_PER_TRIP_FOR_SPECIFIC_HOUR()  {
        int expectedResultFor0=9;
        int expectedResultFor1=9;
        int expectedResultFor2=0;
        int expectedResultFor3=0;
        int expectedResultFor4=0;
        int expectedResultFor5=0;
        int expectedResultFor6=0;

        int actualResultFor0 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(0,"tnc",6);
        int actualResultFor1 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(1,"tnc",6);
        int actualResultFor2 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(2,"tnc",6);
        int actualResultFor3 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(3,"tnc",6);
        int actualResultFor4 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(4,"tnc",6);
        int actualResultFor5 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(5,"tnc",6);
        int actualResultFor6 = deadHeadingStats.getPassengerPerTripCountForSpecificHour(6,"tnc",6);
        assertEquals(expectedResultFor0, actualResultFor0);
        assertEquals(expectedResultFor1, actualResultFor1);
        assertEquals(expectedResultFor2, actualResultFor2);
        assertEquals(expectedResultFor3, actualResultFor3);
        assertEquals(expectedResultFor4, actualResultFor4);
        assertEquals(expectedResultFor5, actualResultFor5);
        assertEquals(expectedResultFor6, actualResultFor6);
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
