package beam.metasim.playground.sid.sim

import scala.collection.JavaConversions.mapAsScalaMap
import akka.actor.{Actor, ActorRef, Props}
import akka.actor.Actor.Receive
import akka.event.Logging
import beam.metasim.agents.{Ack, NoOp}
import beam.metasim.playground.sid.agents.BeamAgent
import beam.metasim.playground.sid.events.ActorSimulationEvents.Start
import beam.playground.metasim.services.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener

import scala.collection.mutable

/**
  * Supervisor actor. Re Listens to MATSim `StartupEvent`.
  *
  * Created by sfeygin on 1/28/17.
  */

class ActorScheduler(agentPopulation: Population) extends Actor with StartupListener{
  val log = Logging(context.system, this)

  var matsimServices: MatsimServices = _

  override def notifyStartup(event: StartupEvent): Unit = startSimulation(event)

  // Initially, convert Population of MatSim "Agents" to untyped BeamActors and retain ActorRef to them mapped to their id
  // (for later reference)
  val actorPopMap: Map[ActorRef, Id[Person]] = mapAsScalaMap(agentPopulation.getPersons) map {
    case (k, v) => (context actorOf(Props(classOf[BeamAgent], v.getSelectedPlan), s"beam${k.toString}"), k)
  } toMap


  val activityAgentMap: Map[ActorRef,Id[Person]] = _

  def startSimulation(startUpEvent:StartupEvent): Unit = {

    if (matsimServices == null){
      matsimServices = startUpEvent.getServices
    }

    actorPopMap.keys foreach {
      _ ! Start
    }
  }

  override def preStart(): Unit = {
    // Hook to implement behavior prior to starting
  }


  override def receive: Receive = {
    case Ack =>log.info(s"Received ack from $sender")
  }


}
