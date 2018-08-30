package beam.playground.jdeqsim;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;
import org.matsim.core.scenario.ScenarioUtils;

public class Main_equil_bareBone {
    @Deprecated // See beam.agentsim.sim.AgentsimServices
    public static void main(String[] args) {
        Config config = ConfigUtils.loadConfig(
                "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml");

        Scenario scenario = ScenarioUtils.loadScenario(config);

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        CountEnterLinkEvents countEnterLinkEvents = new CountEnterLinkEvents();
        eventsManager.addHandler(countEnterLinkEvents);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        JDEQSimulation jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);

        jdeqSimulation.run();

        eventsManager.finishProcessing();
        System.out.println(countEnterLinkEvents.getLinkEnterCount());

    }


}
