package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.CreateGraphsFromAgentSimEvents;
import junit.framework.TestCase;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

public class CreateGraphsFromAgentSimEventsTest extends TestCase{

    static CreateGraphsFromAgentSimEvents graphsFromAgentSimEvents = new CreateGraphsFromAgentSimEvents();
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
    private static String BUS = "bus";
    private static String SUBWAY = "subway";

    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_CAR_OCCURENCE()  {

        int expectedResult=33 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResult = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_DRIVE_TRANSIT_OCCURENCE()  {
        int expectedResult=1 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResult = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_RIDE_HAILING_OCCURENCE()  {
        int expectedResult=20 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResult = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_WALK_OCCURENCE()  {
        int expectedResult=41 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResult = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_WALK_TRANSIT_OCCURENCE()  {
        int expectedResult=11 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResult = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_CAR_FULEUAGE()  {
        int expectedResult=965;//1114;//1113.5134131391999 ;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFuelageList());
        int actualResult = graphsFromAgentSimEvents.getFuelageHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_BUS_FULEUAGE()  {
        int expectedResult= 4237;//4236.828591738598;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFuelageList());
        int actualResult = graphsFromAgentSimEvents.getFuelageHoursDataCountOccurrenceAgainstMode(BUS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_SUBWAY_FULEUAGE()  {
        int expectedResult= 22;//21.71915184736;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFuelageList());
        int actualResult = graphsFromAgentSimEvents.getFuelageHoursDataCountOccurrenceAgainstMode(SUBWAY,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_WALK_FULEUAGE()  {
        int expectedResult= 34;//29;//28.3868926185;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFuelageList());
        int actualResult = graphsFromAgentSimEvents.getFuelageHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
    }

    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_BUS_IN_FIRST_BUCKET()  {
        int expectedResult=141;
        int actualResult = graphsFromAgentSimEvents.getBucketCountAgainstMode(0,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_BUS_IN_SECOND_BUCKET()  {
        int expectedResult=2;
        int actualResult = graphsFromAgentSimEvents.getBucketCountAgainstMode(1,BUS);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_SUBWAY_IN_FIRST_BUCKET()  {
        int expectedResult=8;
        int actualResult = graphsFromAgentSimEvents.getBucketCountAgainstMode(0,SUBWAY);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_PASSENGER_PER_TRIP_CAR()  {
        int expectedResult=34;
        int actualResult = graphsFromAgentSimEvents.getBucketCountAgainstMode(0,CAR);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_DEAD_HEADING_TNC0()  {
        int expectedResult=18;
        int actualResult = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(0);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_OCCURENCE_FOR_SPECIFIC_HOUR()  {
        int expectedResultCar = 14;
        int expectedResultDriveTran = 1;
        int expectedResultRideHailing = 9;
        int expectedResultWalk=15;
        int expectedResultWalkTran=10;
        int maxHour = getMaxHour(graphsFromAgentSimEvents.getSortedHourModeFrequencyList());
        int actualResultCar = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour,6);
        int actualResultDriveTran = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour,6);
        int actualResultRideHailing = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour,6);
        int actualResultWalk = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour,6);
        int actualResultWalkTran = graphsFromAgentSimEvents.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour,6);
        assertEquals(expectedResultCar, actualResultCar);
        assertEquals(expectedResultDriveTran, actualResultDriveTran);
        assertEquals(expectedResultRideHailing, actualResultRideHailing);
        assertEquals(expectedResultWalk, actualResultWalk);
        assertEquals(expectedResultWalkTran, actualResultWalkTran);
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

        int actualResultFor0 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(0,6);
        int actualResultFor1 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(1,6);
        int actualResultFor2 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(2,6);
        int actualResultFor3 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(3,6);
        int actualResultFor4 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(4,6);
        int actualResultFor5 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(5,6);
        int actualResultFor6 = graphsFromAgentSimEvents.getDeadHeadingTnc0HourDataCount(6,6);
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

        int actualResultFor0 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(0,"tnc",6);
        int actualResultFor1 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(1,"tnc",6);
        int actualResultFor2 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(2,"tnc",6);
        int actualResultFor3 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(3,"tnc",6);
        int actualResultFor4 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(4,"tnc",6);
        int actualResultFor5 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(5,"tnc",6);
        int actualResultFor6 = graphsFromAgentSimEvents.getPassengerPerTripCountForSpecificHour(6,"tnc",6);
        assertEquals(expectedResultFor0, actualResultFor0);
        assertEquals(expectedResultFor1, actualResultFor1);
        assertEquals(expectedResultFor2, actualResultFor2);
        assertEquals(expectedResultFor3, actualResultFor3);
        assertEquals(expectedResultFor4, actualResultFor4);
        assertEquals(expectedResultFor5, actualResultFor5);
        assertEquals(expectedResultFor6, actualResultFor6);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_AVG_TIME_FOR_SPECIFIC_HOUR()  {
        int expectedResultCar = 3;
        int expectedResultDriveTran = 0;
        int expectedResultRideHailing = 4;
        int expectedResultWalk=32;
        int expectedResultWalkTran=17;

        int actualResultCar = graphsFromAgentSimEvents.getAvgCountForSpecificHour(CAR,6);
        int actualResultDriveTran = graphsFromAgentSimEvents.getAvgCountForSpecificHour(DRIVE_TRANS,6);
        int actualResultRideHailing = graphsFromAgentSimEvents.getAvgCountForSpecificHour(RIDE_HAILING,6);
        int actualResultWalk = graphsFromAgentSimEvents.getAvgCountForSpecificHour(WALK ,6);
        int actualResultWalkTran = graphsFromAgentSimEvents.getAvgCountForSpecificHour(WALK_TRANS,6);
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
