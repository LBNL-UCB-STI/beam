package beam.agentsim.playground.rashid.physicalSim.jdeqsim.oneZone

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props

import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.events.EventsUtils
import beam.playground.jdeqsim.CountEnterLinkEvents
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.mobsim.jdeqsim.JDEQSimulation
import org.matsim.core.events.EventsManagerImpl
import beam.playground.jdeqsim.akka.AkkaEventHandlerAdapter

object ActorVersionJDEQSim {
  
  class ActorJDEQSim extends Actor{
    @deprecated ("See beam.agentsim.sim.AgentsimServices")
    def receive = {
      case "start" => {
        val config = ConfigUtils.loadConfig(
      "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml")

        val scenario = ScenarioUtils.loadScenario(config)

        //val eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        //val eventsManager = new AkkaEventHandlerAdapter();
        //val countEnterLinkEvents = new CountEnterLinkEvents();
        //eventsManager.addHandler(countEnterLinkEvents);
        //eventsManager.initProcessing();
    
        //val jdeqSimConfigGroup = new JDEQSimConfigGroup();
        //val jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager);
    
        //jdeqSimulation.run();
        
        //eventsManager.finishProcessing();
    
        //println(countEnterLinkEvents.getLinkEnterCount());
        
        
      }
      case i:Int => println("Number: " + i)
    }
  }
  
  
  class EventManager extends Actor{
    def receive = {
      case s:String => println("String: " + s)
      case i:Int => println("Number: " + i)
    }
  }
  
  val system = ActorSystem("SimpleSystem")
  val jdeqsimActor = system.actorOf(Props[ActorJDEQSim],"ActorJDEQSim")
  val eventManagerActor = system.actorOf(Props[EventManager],"EventManager")
  
  
  
}