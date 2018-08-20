package beam.physsim.jdeqsim.cacc;

import beam.physsim.jdeqsim.cacc.handler.EventCollector;
import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.List;

import static junit.framework.TestCase.assertTrue;

public class CACCJDEQSimTestEvents {

    String basePath = "D:/ns/work/issue_371/equil";

    @Test
    public void testCollectedEventsCountEqualWithZeroPercentCaccShare() {
        double caccShare = 0;

        EventCollector eventCollector = new EventCollector();
        EventCollector caccEventHandler = new EventCollector();


        Config config = ConfigUtils.loadConfig(basePath + "/config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        JDEQSimMain.runSimulation(scenario, eventCollector);
        JDEQSimMain.runSimulationWithCacc(scenario, caccEventHandler, caccShare);

        List<Event> normalEvents = eventCollector.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        assertTrue("Normal Events list collected by Normal Event Handler is not empty", !normalEvents.isEmpty());
        assertTrue("Cacc Events list collected by Cacc Event Handler is not empty", !caccEvents.isEmpty());

        assertTrue("Normal and Cacc event counts are equal", normalEvents.size() == caccEvents.size());
    }

    @Test
    public void testCollectedEventsCountEqualWithTenPercentCaccShare() {
        double caccShare = 10;

        EventCollector eventCollector = new EventCollector();
        EventCollector caccEventHandler = new EventCollector();


        Config config = ConfigUtils.loadConfig(basePath + "/config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        JDEQSimMain.runSimulation(scenario, eventCollector);
        JDEQSimMain.runSimulationWithCacc(scenario, caccEventHandler, caccShare);

        List<Event> normalEvents = eventCollector.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        assertTrue("Normal Events list collected by Normal Event Handler is not empty", !normalEvents.isEmpty());
        assertTrue("Cacc Events list collected by Cacc Event Handler is not empty", !caccEvents.isEmpty());

        assertTrue("Normal and Cacc event counts are equal", normalEvents.size() == caccEvents.size());
    }

    @Test
    public void testCollectedEventsTimesMatchWithZeroPercentCaccShare(){
        double caccShare = 0;

        EventCollector eventCollector = new EventCollector();
        EventCollector caccEventHandler = new EventCollector();


        Config config = ConfigUtils.loadConfig(basePath + "/config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        JDEQSimMain.runSimulation(scenario, eventCollector);
        JDEQSimMain.runSimulationWithCacc(scenario, caccEventHandler, caccShare);

        List<Event> normalEvents = eventCollector.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        assertTrue("Normal Events list collected by Normal Event Handler is not empty", !normalEvents.isEmpty());
        assertTrue("Cacc Events list collected by Cacc Event Handler is not empty", !caccEvents.isEmpty());

        assertTrue("Normal and Cacc event counts are equal", normalEvents.size() == caccEvents.size());

        int foundCount = 0;
        int notFoundCount = 0;
        for(Event event : normalEvents){

            boolean contains = JDEQSimMain.containsTimeStamp(caccEvents, event.getTime());
            if(contains) foundCount++;
            else notFoundCount++;
        }

        assertTrue("All events have matching timestamps in Normal and Cacc events", foundCount == normalEvents.size() && foundCount == caccEvents.size());

        assertTrue("No event found with differing timestamp", notFoundCount == 0);

    }



    @Test
    public void testCollectedEventsTimesMatchWithTenPercentCaccShare(){
        double caccShare = 10;

        EventCollector eventCollector = new EventCollector();
        EventCollector caccEventHandler = new EventCollector();


        Config config = ConfigUtils.loadConfig(basePath + "/config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        JDEQSimMain.runSimulation(scenario, eventCollector);
        JDEQSimMain.runSimulationWithCacc(scenario, caccEventHandler, caccShare);

        List<Event> normalEvents = eventCollector.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        assertTrue("Normal Events list collected by Normal Event Handler is not empty", !normalEvents.isEmpty());
        assertTrue("Cacc Events list collected by Cacc Event Handler is not empty", !caccEvents.isEmpty());

        assertTrue("Normal and Cacc event counts are equal", normalEvents.size() == caccEvents.size());

        int foundCount = 0;
        int notFoundCount = 0;
        for(Event event : normalEvents){

            boolean contains = JDEQSimMain.containsTimeStamp(caccEvents, event.getTime());
            if(contains) foundCount++;
            else notFoundCount++;
        }

        assertTrue("All events have matching timestamps in Normal and Cacc events", foundCount == normalEvents.size() && foundCount == caccEvents.size());

        assertTrue("No event found with differing timestamp", notFoundCount == 0);

    }

}
