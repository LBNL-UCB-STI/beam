package beam.analysis.plot.graph;

import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.plots.CreateGraphsFromAgentSimEvents;
import beam.analysis.plots.FuelUsageStats;
import beam.analysis.plots.ModeChosenStats;
import org.junit.Test;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class FuelUsageGraphTest {
    static CreateGraphsFromAgentSimEvents graphsFromAgentSimEvents = new CreateGraphsFromAgentSimEvents();
    private FuelUsageStats fuelUsageStats = new FuelUsageStats();
    private static String BASE_PATH = new File("").getAbsolutePath();;
    private static String TRANSIT_VEHICLE_FILE_PATH = BASE_PATH+"/test/input/beamville/transitVehicles.xml";
    private static String EVENTS_FILE_PATH = BASE_PATH+"/test/input/beamville/test-data/beamville.events.xml";
    static {
        createDummySimWithXML();
    }
    private static String CAR = "car";
    private static String WALK = "walk" ;
    private static String BUS = "bus";
    private static String SUBWAY = "subway";
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_CAR_FULEUAGE()  {
        int expectedResult=965;//1114;//1113.5134131391999 ;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(CAR,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_BUS_FULEUAGE()  {
        int expectedResult= 4237;//4236.828591738598;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(BUS,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_SUBWAY_FULEUAGE()  {
        int expectedResult= 22;//21.71915184736;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(SUBWAY,maxHour);
        assertEquals(expectedResult, actualResult);
    }
    @Test
    public void test_Should_Pass_Should_RETURN_PATH_TRAVERSAL_EVENT_WALK_FULEUAGE()  {
        int expectedResult= 34;//29;//28.3868926185;
        int maxHour = getMaxHour(fuelUsageStats.getSortedHourModeFuelageList());
        int actualResult = fuelUsageStats.getFuelageHoursDataCountOccurrenceAgainstMode(WALK ,maxHour);
        assertEquals(expectedResult, actualResult);
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
