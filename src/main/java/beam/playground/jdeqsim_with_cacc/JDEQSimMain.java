package beam.playground.jdeqsim_with_cacc;

import beam.playground.jdeqsim.CountEnterLinkEvents;
import beam.playground.jdeqsim_with_cacc.jdeqsim.JDEQSimConfigGroup;
import beam.playground.jdeqsim_with_cacc.jdeqsim.JDEQSimulation;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.HashMap;
import java.util.*;
public class JDEQSimMain {



    public static void main(String[] args) {
        Config config = ConfigUtils.loadConfig("/Users/uthmanmomen/desktop/scenarios/equil/config.xml");

        Scenario scenario = ScenarioUtils.loadScenario(config);

        // TODO: add function like
        // getTravelTime(percentageOfCACC on link) -> result=freeFlow*pct -> linear


        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        CountEnterLinkEvents countEnterLinkEvents = new CountEnterLinkEvents();

        // TODO: add eventHandler, which
            // simple: print out %of cacc on link, freeFlowTravelTime, travelTimeWithTraffic
            // create graphs
        SpeedCalc speedCalc = new SpeedCalc(scenario);
        eventsManager.addHandler(speedCalc);

        eventsManager.addHandler(countEnterLinkEvents);
        EventWriterXML eventsWriter=new EventWriterXML("/Users/uthmanmomen/desktop/scenarios/equil/output/events.xml");
        eventsManager.addHandler(eventsWriter);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();


        // TODO: include mapping from person(vehicle) to isCacc HashMap<key=vehicleId,value=Boolean>
        ////////CHANGES/////////
        HashMap<String ,Boolean> isCACCVehicle = new HashMap<>();


        boolean isCACC;

        for (Person person : scenario.getPopulation().getPersons().values()) {
            isCACC = new Random().nextBoolean();
            isCACCVehicle.put(person.getId().toString(), isCACC);

        }


        // TODO: pass vehicle to isCACC mapping to JDEQSim //Done\\


        JDEQSimulation jdeqSimulation=new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager, isCACCVehicle);

        jdeqSimulation.run();

        //eventsManager.finishProcessing();
        System.out.println("enter link count: " +  countEnterLinkEvents.getLinkEnterCount());
        eventsWriter.closeFile();

        //speedCalc.printArray();
    }

}
