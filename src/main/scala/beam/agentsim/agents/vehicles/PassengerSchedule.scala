package beam.agentsim.agents.vehicles

import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * BEAM
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, Manifest]){
  def isEmpty = schedule.isEmpty

  def initialSpacetime() = {
    schedule.firstKey.travelPath.toTrajectory.location(schedule.firstKey.startTime)
  }
  def terminalSpacetime() = {
    val lastLeg = schedule.lastKey
    val endTime = lastLeg.startTime + lastLeg.endTime
    lastLeg.travelPath.toTrajectory.location(endTime)
  }
  def getStartLeg() = {
    schedule.head._1
  }

  def addLegs(legs: Seq[BeamLeg]) = {
    legs.withFilter(leg => !(schedule contains leg)).map(leg => schedule.put(leg, Manifest()))
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
    schedule.get(firstLeg).get.boarders += passenger.vehicleId
    val lastLeg = legs.last
    schedule.get(lastLeg).get.alighters += passenger.vehicleId
  }
}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(mutable.TreeMap[BeamLeg, Manifest]()(BeamLeg.beamLegOrdering))
}

case class VehiclePersonId(vehicleId: Id[Vehicle], personId: Id[Person])

class Manifest(val riders: mutable.ListBuffer[VehiclePersonId], val boarders: mutable.ListBuffer[Id[Vehicle]], val alighters: mutable.ListBuffer[Id[Vehicle]] ){
  def isEmpty: Boolean = riders.size == 0
}

object Manifest{
  def apply(): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
  def apply(passenger: VehiclePersonId): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](passenger),mutable.ListBuffer[Id[Vehicle]](),mutable.ListBuffer[Id[Vehicle]]())
}
