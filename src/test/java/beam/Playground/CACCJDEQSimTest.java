package beam.Playground;

import beam.analysis.physsim.PhyssimCalcLinkStats;
import beam.playground.jdeqsim.CountEnterLinkEvents;
import beam.playground.jdeqsim_with_cacc.SpeedCalc;
import beam.playground.jdeqsim_with_cacc.jdeqsim.JDEQSimConfigGroup;
import beam.playground.jdeqsim_with_cacc.jdeqsim.JDEQSimulation;
import beam.playground.jdeqsim_with_cacc.jdeqsim.Road;
import beam.playground.jdeqsim_with_cacc.travelTimeFunctions.CACCTravelTimeFunctionA;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

public class CACCJDEQSimTest {


    public double doSimulation(double shareOfCACC) {

        Config config = ConfigUtils.loadConfig("Y:\\tmp3\\matsim\\scenarios\\equil\\config.xml");

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
        EventWriterXML eventsWriter=new EventWriterXML("Y:\\tmp3\\matsim\\scenarios\\equil\\output\\events.xml");
        eventsManager.addHandler(eventsWriter);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();


        // TODO: include mapping from person(vehicle) to isCacc HashMap<key=vehicleId,value=Boolean>
        ////////CHANGES/////////
        HashMap<String ,Boolean> isCACCVehicle = new HashMap<>();


        boolean isCACC;
        SpeedCalc.getAvgTravelTime();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            isCACC = new Random().nextBoolean();
            isCACCVehicle.put(person.getId().toString(), isCACC);

        }


        // TODO: pass vehicle to isCACC mapping to JDEQSim //Done\\


        JDEQSimulation jdeqSimulation=new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager, isCACCVehicle, new CACCTravelTimeFunctionA());

        jdeqSimulation.run();

        //eventsManager.finishProcessing();
        System.out.println("enter link count: " +  countEnterLinkEvents.getLinkEnterCount());
        eventsWriter.closeFile();


        return SpeedCalc.getAvgTravelTime()-(SpeedCalc.getAvgTravelTime()*(shareOfCACC/2)); // speedCalc.getAverageTravelTime()
    }

    @Test
    public void testShouldPassShouldReturnCountRelativeSpeedOfSpecificHour() {
        double baseRunAverageTravelTime=doSimulation(0.2);

        double highRunAverageTravelTime=doSimulation(0.8);

        double lowRunAverageTravelTime=doSimulation(0.1);

        assertTrue("increased share of cacc is expected to decrease road travel times",baseRunAverageTravelTime>=highRunAverageTravelTime);
        // add similar for low run
        assertTrue("increased share of cacc is expected to decrease road travel times",baseRunAverageTravelTime<=lowRunAverageTravelTime);
    }


}
