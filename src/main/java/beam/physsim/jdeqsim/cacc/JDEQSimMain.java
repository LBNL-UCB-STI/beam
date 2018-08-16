package beam.physsim.jdeqsim.cacc;

import beam.physsim.jdeqsim.cacc.handler.CaccEventHandler;
import beam.physsim.jdeqsim.cacc.handler.NormalEventHandler;
import beam.physsim.jdeqsim.cacc.sim.Vehicle;
import beam.playground.jdeqsim.CountEnterLinkEvents;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import beam.physsim.jdeqsim.cacc.travelTimeFunctions.CACCTravelTimeFunctionA;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.HashMap;
import java.util.*;
public class JDEQSimMain {

    // "Y:\\tmp3\\matsim\\scenarios\\equil
    public static String basePath = "D:/ns/work/issue_371/equil";

    /*
        Make two handlers
        Collect from both simulation with and without cacc
        See if same number of events
        See if timestamps are same
    */
    public static void main(String[] args) {

        Config config = ConfigUtils.loadConfig(basePath + "/config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        NormalEventHandler normalEventHandler = new NormalEventHandler();
        runSimulation(scenario, normalEventHandler);

        CaccEventHandler caccEventHandler = new CaccEventHandler();
        runSimulationWithCacc(scenario, caccEventHandler, null);
        //eventsWriter.closeFile();

        compareEvents(normalEventHandler, caccEventHandler);
    }

    private static void compareEvents(NormalEventHandler normalEventHandler, CaccEventHandler caccEventHandler) {

        List<Event> normalEvents = normalEventHandler.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        /*normalEventHandler.logEvents();
        caccEventHandler.logEvents();*/

        System.out.println("Normal Events Counts -> " + normalEvents.size());

        System.out.println("Cacc Events Counts -> " + caccEvents.size());


        int foundCount = 0;
        int notFoundCount = 0;
        for(Event event : normalEvents){

            boolean contains = containsTimeStamp(caccEvents, event.getTime());
            if(contains) foundCount++;
            else notFoundCount++;
        }

        System.out.println("Matches events times -> " + foundCount + " not matched events times -> " + notFoundCount);

    }

    public static boolean containsTimeStamp(final List<Event> list, final double time){
        return list.stream().filter(o -> (o.getTime() - time == 0)).findFirst().isPresent();
    }

    public static void runSimulation(Scenario scenario, NormalEventHandler normalEventHandler) {

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        eventsManager.addHandler(normalEventHandler);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        org.matsim.core.mobsim.jdeqsim.JDEQSimulation jdeqSimulation = new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);
        jdeqSimulation.run();
    }

    public static void runSimulationWithCacc(Scenario scenario, CaccEventHandler caccEventHanlder, Double caccShare){

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        eventsManager.addHandler(caccEventHanlder);
        /*EventWriterXML eventsWriter = new EventWriterXML(basePath + "/output/events.xml");
        eventsManager.addHandler(eventsWriter);*/
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        Map caccVehicles = getRandomCaccVehicles(scenario);

        JDEQSimulation jdeqSimulationWithCacc = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager, caccVehicles, new CACCTravelTimeFunctionA(), caccShare);
        jdeqSimulationWithCacc.run();
        //eventsManager.finishProcessing();
    }

    public static Map<String, Boolean> getRandomCaccVehicles(Scenario scenario){

        Map<String ,Boolean> isCACCVehicle = new HashMap<>();
        boolean isCACC;
        Random rand = new Random(System.currentTimeMillis());
        for (Person person : scenario.getPopulation().getPersons().values()) {
            isCACC = rand.nextBoolean();
            isCACCVehicle.put(person.getId().toString(), isCACC);
        }

        return isCACCVehicle;
    }

}
