package beam.metasim.playground.colin

import akka.actor.{ActorRef, ActorSystem, Inbox, Props}
import beam.metasim.agents.{BeamAgentScheduler, StartSchedule, Transition, TriggerData}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener
import org.matsim.core.mobsim.framework.Mobsim
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters.mapAsScalaMap
import scala.concurrent.duration._

object BeamActorSimulation {
  val _system = ActorSystem("BeamActorSimulation")
  val logger = LoggerFactory.getLogger(classOf[BeamActorSimulation])
  var services: MatsimServices = _
  var scheduler: ActorRef = _
  var eventsManagerService: ActorRef = _
  var inbox: Inbox = _
}


class BeamActorSimulation extends StartupListener with Mobsim {
  import BeamActorSimulation._

  def notifyStartup(event: StartupEvent): Unit = {
    services = event.getServices
    scheduler = _system.actorOf(Props[BeamAgentScheduler])
    eventsManagerService = _system.actorOf(Props[EventsManagerService])
    inbox = Inbox.create(_system)

    val initPopMap: Map[ActorRef, Id[Person]] = mapAsScalaMap(services.getScenario.getPopulation.getPersons) map {
      case (k, v) => (_system.actorOf(Props[BeamAgentColin]), k)
    } toMap

    initPopMap foreach { case (actorRef, id) =>
      logger.info("initializing")
      inbox.send(actorRef, GetState);
      logger.info(inbox.receive(500.millis).toString())
    }
		Thread.sleep(100);
    logger.info("done 1")
    initPopMap foreach { case (actorRef, id) =>
      inbox.send(scheduler, Transition(new TriggerData(actorRef, 1.0)))
      inbox.send(scheduler, Transition(new TriggerData(actorRef, 10.0)))
      inbox.send(scheduler, Transition(new TriggerData(actorRef, 20.0)))
      inbox.send(scheduler, Transition(new TriggerData(actorRef, 30.0)))
    }
		Thread.sleep(100);
  }
  def run(): Unit = {
    inbox.send(scheduler, StartSchedule)
		Thread.sleep(100);
  }
}





