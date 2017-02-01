package beam.metasim.playground.sid.sim

import scala.collection.JavaConversions.mapAsScalaMap
import akka.actor.{Actor, ActorRef, Props}
import akka.actor.Actor.Receive
import akka.event.Logging
import beam.metasim.agents.{Ack, NoOp}
import beam.metasim.playground.sid.agents.BeamAgent
import beam.metasim.playground.sid.events.ActorSimulationEvents.{Await, Start, StartSimulation}
import beam.playground.metasim.services.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener

import scala.collection.mutable

/**
  * Supervisor actor. Supervises population of BeamAgents
  *
  * Created by sfeygin on 1/28/17.
  */

class ActorScheduler(agentPopulation: Population) extends Actor{
  val log = Logging(context.system, this)
  val popSize: Int = agentPopulation.getPersons.size
  var matsimServices: MatsimServices = _

  // Initially, convert Population of MatSim "Agents" to untyped BeamActors and retain ActorRef to them mapped to their id
  // (for later reference)
  val initPopMap: Map[ActorRef, Id[Person]] = mapAsScalaMap(agentPopulation.getPersons) map {
    case (k, v) => (context actorOf(Props(classOf[BeamAgent], k.toString), s"beam${k.toString}"), k)
  } toMap

  private var readyToStartSet: List[ActorRef] = Nil


//  val activityAgentMap: Map[ActorRef,Id[Person]] = _

  override def preStart(): Unit = {
    // Hook to implement behavior prior to starting
  }

  def initAgents():Unit={
    initPopMap foreach {case(actorRef, id)=>
      actorRef!Await
    }
  }


  override def receive: Receive = {
    case StartSimulation =>
      initAgents()
    case Ack =>
      log.info(s"Received ack from $sender")
      readyToStartSet::=sender
      if (readyToStartSet.size == popSize){
        for (agent <- readyToStartSet) {
          agent ! Start
        }
      }

  }



}
