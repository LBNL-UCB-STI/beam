package beam.agentsim.agents.vehicles

import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * BEAM
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, Manifest]){
  def addLegs(legs: Seq[BeamLeg]) = {
    legs.foreach(leg =>
      schedule.get(leg) match {
        case None =>
          schedule.put(leg, Manifest())
        case Some(manifest) =>
      }
    )
  }
  def addPassenger(passenger: VehiclePersonId, legs: Seq[BeamLeg]) = {
    legs.foreach(leg =>
      schedule.get(leg) match {
        case Some(manifest) =>
          manifest.riders += passenger
        case None =>
          schedule.put(leg, Manifest(passenger))
      }
    )
    val firstLeg = legs.head
    schedule.get(firstLeg).get.boarders += passenger.passengerVehicleId
    val lastLeg = legs.last
    schedule.get(lastLeg).get.alighters += passenger.passengerVehicleId
  }
}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(mutable.TreeMap[BeamLeg, Manifest]()(BeamLeg.beamLegOrdering))
}

case class VehiclePersonId(passengerVehicleId: Id[Vehicle], personId: Id[Person])

class Manifest(val riders: mutable.ListBuffer[VehiclePersonId], val boarders: mutable.ListBuffer[Id[Vehicle]], val alighters: mutable.ListBuffer[Id[Vehicle]] ){
  def isEmpty: Boolean = riders.size == 0
}

object Manifest{
  def apply(): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
  def apply(passenger: VehiclePersonId): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](passenger),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
}
