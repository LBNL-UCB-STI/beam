package beam.agentsim.agents.vehicles

import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

/**
  * Holds information about the numbers and identities of agents in the model
  */
class PassengerSchedule(val schedule: mutable.TreeMap[BeamLeg, Manifest]) {

  /**
    * Total number of current riders (we don't count alighters or boarders in this sum).
    */
  def curTotalNumPassengers(beamLeg: BeamLeg): Int = {
    schedule(beamLeg).riders.size
  }

  def isEmpty: Boolean = schedule.isEmpty

  def initialSpacetime: SpaceTime = {
    schedule.firstKey.travelPath.getStartPoint()
  }

  def terminalSpacetime(): SpaceTime = {
    val lastLeg = schedule.lastKey
    lastLeg.travelPath.getEndPoint()
  }

  def getStartLeg: BeamLeg = {
    schedule.head._1
  }

  def addLegs(legs: Seq[BeamLeg]): Seq[Option[Manifest]] = {
    legs.withFilter(leg => !(schedule contains leg)).map(leg => schedule.put(leg, Manifest()))
  }

  def removePassenger(passenger: VehiclePersonId): Boolean = {
    var anyRemoved = false
    schedule.foreach(lm => {
      if (lm._2.riders.contains(passenger)) {
        lm._2.riders -= passenger
        anyRemoved = true
      }
      if (lm._2.alighters.contains(passenger.vehicleId)) {
        lm._2.alighters -= passenger.vehicleId
      }
      if (lm._2.boarders.contains(passenger.vehicleId)) {
        lm._2.boarders -= passenger.vehicleId
      }
    })
    anyRemoved
  }

  def addPassenger(passenger: VehiclePersonId, legs: Seq[BeamLeg]): Unit = {
    legs.foreach(leg =>
      schedule.get(leg) match {
        case Some(manifest) =>
          manifest.riders += passenger
        case None =>
          schedule.put(leg, Manifest(passenger))
      }
    )
    if(!legs.isEmpty){
      schedule(legs.head).boarders += passenger.vehicleId
      schedule(legs.last).alighters += passenger.vehicleId
    }
  }

  override def toString: String = {
    schedule.map(keyVal => s"${keyVal._1.toString} -> ${keyVal._2.toString}").mkString("--")
  }

}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(mutable.TreeMap[BeamLeg, Manifest]()(BeamLeg.beamLegOrdering))
}

case class VehiclePersonId(vehicleId: Id[Vehicle], personId: Id[Person])

class Manifest(val riders: mutable.ListBuffer[VehiclePersonId], val boarders: mutable.ListBuffer[Id[Vehicle]], val alighters: mutable.ListBuffer[Id[Vehicle]]) {
  def isEmpty: Boolean = riders.isEmpty
  override def toString: String = {
    s"[${riders.size}riders;${boarders.size}boarders;${alighters.size}alighters]"
  }
}

object Manifest {
  def apply(): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](), mutable.ListBuffer[Id[Vehicle]](), mutable.ListBuffer[Id[Vehicle]]())

  def apply(passenger: VehiclePersonId): Manifest = new Manifest(mutable.ListBuffer[VehiclePersonId](passenger), mutable.ListBuffer[Id[Vehicle]](), mutable.ListBuffer[Id[Vehicle]]())
}
