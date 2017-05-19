package beam.agentsim.playground.rashid.physicalSim.jdeqsim.oneZone

import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.events.EventsUtils
import beam.playground.jdeqsim.CountEnterLinkEvents
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation
import org.matsim.core.events.EventsManagerImpl

object NoActorVersion {

  @deprecated ("See beam.agentsim.sim.AgentsimServices")
  def main(args: Array[String]) {
    val config = ConfigUtils.loadConfig(
      "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml");

    val scenario = ScenarioUtils.loadScenario(config);

    //val eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
    val eventsManager = new EventsManagerImpl();
    
    val countEnterLinkEvents = new CountEnterLinkEvents();
    eventsManager.addHandler(countEnterLinkEvents);
    eventsManager.initProcessing();

    val jdeqSimConfigGroup = new JDEQSimConfigGroup();
    val jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);

    jdeqSimulation.run();
    
    eventsManager.finishProcessing();

    println(countEnterLinkEvents.getLinkEnterCount());
  }
  
  
} 