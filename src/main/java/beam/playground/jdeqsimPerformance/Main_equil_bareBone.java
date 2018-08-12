package beam.playground.jdeqsimPerformance;


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

        // The following file path for config file might be provided as a program argument to the main method
        String defaultFileName = "C:/ns/matsim-project/matsim/examples/scenarios/equil/config.xml";
        Config config = ConfigUtils.loadConfig(defaultFileName);

        Scenario scenario = ScenarioUtils.loadScenario(config);

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        LogEnterLinkEvents logEnterLinkEvents = new LogEnterLinkEvents();
        eventsManager.addHandler(logEnterLinkEvents);

        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        JDEQSimulation jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);

        jdeqSimulation.run();

        eventsManager.finishProcessing();
    }
}
