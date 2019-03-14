package beam.physsim.jdeqsim.cacc;

import beam.physsim.jdeqsim.cacc.handler.EventCollector;
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.Hao2018CaccRoadCapacityAdjustmentFunction;
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
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

        EventCollector eventCollector = new EventCollector();
        runSimulation(scenario, eventCollector);

        scenario = ScenarioUtils.loadScenario(config);
        EventCollector caccEventHandler = new EventCollector();
        runSimulationWithCacc(scenario, caccEventHandler, 0);
        //eventsWriter.closeFile();

        compareEvents(eventCollector, caccEventHandler);
    }

    private static void compareEvents(EventCollector eventCollector, EventCollector caccEventHandler) {

        List<Event> normalEvents = eventCollector.getEvents();
        List<Event> caccEvents = caccEventHandler.getEvents();

        /*eventCollector.logEvents();
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

    public static void runSimulation(Scenario scenario, EventCollector eventCollector) {

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        eventsManager.addHandler(eventCollector);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        org.matsim.core.mobsim.jdeqsim.JDEQSimulation jdeqSimulation = new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);
        jdeqSimulation.run();
    }

    public static void runSimulationWithCacc(Scenario scenario, EventCollector caccEventHanlder, double caccShare){
        double speedAdjustmentFactor = 1.0;
        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        eventsManager.addHandler(caccEventHanlder);
        /*EventWriterXML eventsWriter = new EventWriterXML(basePath + "/output/events.xml");
        eventsManager.addHandler(eventsWriter);*/
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        // If caccVehicles is empty let say 10 of 100 are caccvehicles
        Map caccVehicles = getRandomCaccVehicles(scenario, caccShare);

        CACCSettings caccSettings=new CACCSettings(caccVehicles,new Hao2018CaccRoadCapacityAdjustmentFunction(2000,20));
        JDEQSimulation jdeqSimulationWithCacc = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager, caccSettings, speedAdjustmentFactor);
        jdeqSimulationWithCacc.run();
        //eventsManager.finishProcessing();
    }

    /*
        We can set 10% cacc share out of the total vehicles
    */
    public static Map<String, Boolean> getRandomCaccVehicles(Scenario scenario, double percentageOfCaccShare){

        Map<String ,Boolean> isCACCVehicle = new HashMap<>();
        boolean isCACC;
        Random rand = new Random(System.currentTimeMillis());

        int numCaccVehicle = 0;
        int numOfVehicles = scenario.getPopulation().getPersons().values().size();

        double percentAchieved = 0;

        for (Person person : scenario.getPopulation().getPersons().values()) {

            percentAchieved = (numCaccVehicle * 100.0d) / numOfVehicles;

            if(percentageOfCaccShare == 0 || (percentAchieved >= percentageOfCaccShare)) {

                isCACCVehicle.put(person.getId().toString(), false);
            }else{
                isCACC = rand.nextBoolean();
                isCACCVehicle.put(person.getId().toString(), isCACC);

                if(isCACC == true) {

                    numCaccVehicle++;
                }
            }
        }

        return isCACCVehicle;
    }

}
