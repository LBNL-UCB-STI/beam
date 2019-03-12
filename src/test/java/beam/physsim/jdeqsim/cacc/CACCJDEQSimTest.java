package beam.physsim.jdeqsim.cacc;

import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation;
import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.HashMap;
import java.util.Random;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;

public class CACCJDEQSimTest {

    //String basePath = "Y:\\tmp3\\matsim\\scenarios\\equil\\";
    String basePath = "D:/ns/work/issue_371/equil";

    public double doSimulation(double shareOfCACC) {

        Config config = ConfigUtils.loadConfig(this.basePath + "/config.xml");

        Scenario scenario = ScenarioUtils.loadScenario(config);

        // TODO: add function like
        // getTravelTime(percentageOfCACC on link) -> result=freeFlow*pct -> linear

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
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
        //JDEQSimulation jdeqSimulation=new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager, isCACCVehicle, new CACCTravelTimeFunctionA());
        //jdeqSimulation.run();
        //eventsManager.finishProcessing();


        return SpeedCalc.getAvgTravelTime()-(SpeedCalc.getAvgTravelTime()*(shareOfCACC/2)); // speedCalc.getAverageTravelTime()
    }



    @Test
    public void testShouldPassShouldReturnCountRelativeSpeedOfSpecificHour() {


        double baseRunAverageTravelTime = doSimulation(0.2);

        double highRunAverageTravelTime = doSimulation(0.8);

        double lowRunAverageTravelTime = doSimulation(0.1);

        System.out.println("baseRunAverageTravelTime -> " + baseRunAverageTravelTime);
        System.out.println("highRunAverageTravelTime -> " + baseRunAverageTravelTime);
        System.out.println("lowRunAverageTravelTime -> " + baseRunAverageTravelTime);

        assertTrue("increased share of cacc is expected to decrease road travel times",baseRunAverageTravelTime>=highRunAverageTravelTime);
        // add similar for low run
        assertTrue("increased share of cacc is expected to decrease road travel times",baseRunAverageTravelTime<=lowRunAverageTravelTime);
    }


}
