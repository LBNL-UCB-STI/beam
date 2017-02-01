package beam.metasim.playground.sid.sim

import akka.actor.{ActorRef, ActorSystem, Props}
import beam.metasim.playground.sid.agents.BeamAgent
import beam.metasim.playground.sid.events.ActorSimulationEvents.StartSimulation
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener
import org.slf4j.LoggerFactory

/**
  * Beam actor simulation
  *  - This will eventually extend MobSim, but for now, just return the [[ActorSystem]]
  * Created by sfeygin on 1/28/17.
  */
object BeamActorSimulation {
  val _system = ActorSystem("BeamActorSimulation")
  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])
}

class BeamActorSimulation extends StartupListener {

  import BeamActorSimulation._

  private var services: MatsimServices = _


  override def notifyStartup(event: StartupEvent): Unit = {
    services = event.getServices
    val scheduler: ActorRef = _system.actorOf(Props(new ActorScheduler(services.getScenario.getPopulation)), name = "BeamScheduler")
    logger.info("BeamActorSimulation sending StartSimulation event to scheduler")
    scheduler ! StartSimulation

  }
}





