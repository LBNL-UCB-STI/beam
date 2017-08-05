package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.router.RoutingModel.BeamLeg

import scala.collection.mutable

/**
  * BEAM
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, mutable.ListBuffer[ActorRef]]){

}
