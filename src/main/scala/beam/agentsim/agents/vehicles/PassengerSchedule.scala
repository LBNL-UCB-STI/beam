package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * BEAM
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, Manifest]){
  def addPassenger(passenger: Id[Vehicle], legs: Vector[BeamLeg]) = {
    legs.foreach(leg =>
      schedule.get(leg) match {
        case Some(manifest) =>
          manifest.riders += passenger
        case None =>
          schedule.put(leg, Manifest(passenger))
      }
    )
    val firstLeg = legs(0)
    schedule.get(firstLeg).get.boarders += passenger
    val lastLeg = legs(legs.size - 1)
    schedule.get(lastLeg).get.alighters += passenger
  }
}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(mutable.TreeMap[BeamLeg, Manifest]()(BeamLeg.beamLegOrdering))
}

class Manifest(val riders: mutable.ListBuffer[Id[Vehicle]], val boarders: mutable.ListBuffer[Id[Vehicle]], val alighters: mutable.ListBuffer[Id[Vehicle]] )

object Manifest{
  def apply(): Manifest = new Manifest(mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
  def apply(passenger: Id[Vehicle]): Manifest = new Manifest(mutable.ListBuffer[Id[Vehicle]](passenger),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
}
