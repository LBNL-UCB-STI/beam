package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.router.RoutingModel.BeamLeg

import scala.collection.mutable

/**
  * BEAM
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, Manifest]){
  def addPassenger(passenger: ActorRef, legs: Vector[BeamLeg]) = {
    legs.foreach(leg =>
      schedule.get(leg) match {
        case Some(manifest) =>
          manifest.boarders += passenger
        case None =>
          schedule.put(leg, Manifest(passenger))
      }
    )
    val lastLeg = legs(legs.size - 1)
    schedule.get(lastLeg).get.alighters += passenger
  }
}


object PassengerSchedule {
  implicit val beamLegOrdering: Ordering[BeamLeg] = Ordering.by(_.startTime)
  def apply(): PassengerSchedule = new PassengerSchedule(mutable.TreeMap[BeamLeg, Manifest]()(beamLegOrdering))
}

class Manifest(val riders: mutable.ListBuffer[ActorRef], val boarders: mutable.ListBuffer[ActorRef], val alighters: mutable.ListBuffer[ActorRef] )

object Manifest{
  def apply(): Manifest = new Manifest(mutable.ListBuffer[ActorRef](),mutable.ListBuffer[ActorRef](),mutable.ListBuffer[ActorRef]())
  def apply(passenger: ActorRef): Manifest = new Manifest(mutable.ListBuffer[ActorRef](passenger),mutable.ListBuffer[ActorRef](passenger),mutable.ListBuffer[ActorRef]())
}
