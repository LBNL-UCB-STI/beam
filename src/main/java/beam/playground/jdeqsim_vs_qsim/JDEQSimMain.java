package beam.playground.jdeqsim_vs_qsim;

import beam.playground.jdeqsim.CountEnterLinkEvents;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation;
import org.matsim.core.scenario.ScenarioUtils;

public class JDEQSimMain {

    public static void main(String[] args) {
        Config config = ConfigUtils.loadConfig(
                "Y:\\tmp3\\matsim\\scenarios\\equil\\config.xml");

        Scenario scenario = ScenarioUtils.loadScenario(config);

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        CountEnterLinkEvents countEnterLinkEvents = new CountEnterLinkEvents();
        eventsManager.addHandler(countEnterLinkEvents);
        EventWriterXML eventsWriter=new EventWriterXML("Y:\\tmp3\\matsim\\scenarios\\equil\\output\\events.xml");
        eventsManager.addHandler(eventsWriter);
        eventsManager.initProcessing();

        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();
        JDEQSimulation jdeqSimulation=new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);

        jdeqSimulation.run();

        //eventsManager.finishProcessing();
        System.out.println(countEnterLinkEvents.getLinkEnterCount());
        eventsWriter.closeFile();
    }

}
