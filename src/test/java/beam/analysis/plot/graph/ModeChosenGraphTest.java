package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.CreateGraphsFromAgentSimEvents;
import beam.analysis.plots.ModeChosenStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ModeChosenGraphTest {
    static CreateGraphsFromAgentSimEvents graphsFromAgentSimEvents = new CreateGraphsFromAgentSimEvents();
    private ModeChosenStats modeChosenStats = new ModeChosenStats();
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
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_CAR_OCCURENCE()  {

        int expectedResult=33 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_DRIVE_TRANSIT_OCCURENCE()  {
        int expectedResult=1 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_RIDE_HAILING_OCCURENCE()  {
        int expectedResult=20 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_WALK_OCCURENCE()  {
        int expectedResult=41 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_WALK_TRANSIT_OCCURENCE()  {
        int expectedResult=11 ;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResult = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_MODECHOSE_EVENT_OCCURENCE_FOR_SPECIFIC_HOUR()  {
        int expectedResultCar = 14;
        int expectedResultDriveTran = 1;
        int expectedResultRideHailing = 9;
        int expectedResultWalk=15;
        int expectedResultWalkTran=10;
        int maxHour = getMaxHour(modeChosenStats.getSortedHourModeFrequencyList());
        int actualResultCar = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(CAR,maxHour,6);
        int actualResultDriveTran = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(DRIVE_TRANS,maxHour,6);
        int actualResultRideHailing = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(RIDE_HAILING,maxHour,6);
        int actualResultWalk = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK ,maxHour,6);
        int actualResultWalkTran = modeChosenStats.getHoursDataCountOccurrenceAgainstMode(WALK_TRANS,maxHour,6);
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
