package beam.metasim.playground.sid.sim

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import beam.metasim.agents.Ack
import beam.metasim.playground.sid.agents.BeamAgent
import beam.metasim.playground.sid.events.ActorSimulationEvents._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.core.controler.MatsimServices

import scala.collection.JavaConversions.mapAsScalaMap

/**
  * Supervisor actor. Supervises population of BeamAgents
  *
  * Created by sfeygin on 1/28/17.
  */

class ActorScheduler(agentPopulation: Population) extends Actor {
  val log = Logging(context.system, this)
  val popSize: Int = agentPopulation.getPersons.size
  var matsimServices: MatsimServices = _

  // Initially, convert Population of MatSim "Agents" to untyped BeamActors and retain ActorRef to them mapped to their id
  // (for later reference)
  val initPopMap: Map[ActorRef, Id[Person]] = mapAsScalaMap(agentPopulation.getPersons) map {
    case (k, v) => (context actorOf(Props(classOf[BeamAgent], k.toString), s"beam${k.toString}"), k)
  } toMap

  var readyToStartSet: Set[ActorRef] = Set empty
  //  val activityAgentMap: Map[ActorRef,Id[Person]] = _

  override def preStart(): Unit = {
    // Hook to implement behavior prior to starting
  }

  def initAgents(): Unit = {
    initPopMap foreach { case (actorRef, id) =>
      actorRef ! Await
    }
    context become waitingForAck(initPopMap.keySet)
  }

  def waitingForAck(notConfirmedActors: Set[ActorRef]): Receive ={
    case Ack =>
      val newNotConfirmedActors = notConfirmedActors - sender
      readyToStartSet+=sender
      log.info(s"Received ack from $sender")
      if (newNotConfirmedActors.isEmpty){
        for (agent <-  readyToStartSet) {
          agent!Start
        }
      }else{
        context become waitingForAck(newNotConfirmedActors)
      }
    case TimeOut =>
      log.info(s"Oh, no! Received timeout event from $sender, resending Await")
      sender ! Await
      val newNotConfirmedActors = notConfirmedActors + sender
      context become waitingForAck(newNotConfirmedActors)
    case AgentReady =>
      log.info(s"Received agent ready from $sender")
      readyToStartSet -= sender
      val newNotConfirmedActors = notConfirmedActors - sender
      context become waitingForAck(newNotConfirmedActors)

  }


  override def receive: Receive = {
    case StartSimulation =>
      initAgents()

  }


}
