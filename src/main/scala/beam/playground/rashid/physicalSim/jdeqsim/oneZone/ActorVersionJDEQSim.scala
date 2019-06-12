package beam.playground.rashid.physicalSim.jdeqsim.oneZone

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils

object ActorVersionJDEQSim {

  class ActorJDEQSim extends Actor {

    @deprecated("See beam.agentsim.sim.AgentsimServices", "2.0")
    def receive: PartialFunction[Any, Unit] = {
      case "start" =>
        val config = ConfigUtils.loadConfig(
          "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml"
        )

        val scenario = ScenarioUtils.loadScenario(config)

      //val eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
      //val eventsManager = new AkkaEventHandlerAdapter()
      //val countEnterLinkEvents = new CountEnterLinkEvents()
      //eventsManager.addHandler(countEnterLinkEvents)
      //eventsManager.initProcessing()

      //val jdeqSimConfigGroup = new JDEQSimConfigGroup()
      //val jdeqSimulation = new JDEQSimulation(jdeqSimConfigGroup, scenario, eventsManager)

      //jdeqSimulation.run()

      //eventsManager.finishProcessing()

      //println(countEnterLinkEvents.getLinkEnterCount())
      case i: Int => println("Number: " + i)
    }
  }

  class EventManager extends Actor {

    def receive: PartialFunction[Any, Unit] = {
      case s: String => println("String: " + s)
      case i: Int    => println("Number: " + i)
    }
  }

  val system = ActorSystem("SimpleSystem")
  val jdeqsimActor: ActorRef = system.actorOf(Props[ActorJDEQSim], "ActorJDEQSim")
  val eventManagerActor: ActorRef = system.actorOf(Props[EventManager], "EventManager")
}
