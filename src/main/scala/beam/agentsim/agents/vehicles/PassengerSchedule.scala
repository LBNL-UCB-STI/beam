package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.TreeMap

/**
  * Holds information about the numbers and identities of agents in the model
  */
case class PassengerSchedule(schedule: TreeMap[BeamLeg, Manifest]) {

  def addLegs(legs: Seq[BeamLeg]): PassengerSchedule = {
    PassengerSchedule(schedule ++ legs.map(leg => (leg, Manifest())))
  }

  def addPassenger(passenger: VehiclePersonId, legs: Seq[BeamLeg]): PassengerSchedule = {
    var newSchedule = schedule ++ legs.map(leg => {val manifest:Manifest = schedule.getOrElse(leg, Manifest()); (leg, manifest.copy(riders = manifest.riders + passenger))})
    newSchedule = newSchedule ++ legs.headOption.map(boardLeg => {val manifest:Manifest = newSchedule.getOrElse(boardLeg, Manifest());(boardLeg, manifest.copy(boarders = manifest.boarders + passenger.vehicleId))})
    newSchedule = newSchedule ++ legs.lastOption.map(alightLeg => {val manifest:Manifest = newSchedule.getOrElse(alightLeg, Manifest());(alightLeg, manifest.copy(alighters = manifest.alighters + passenger.vehicleId))})
    PassengerSchedule(newSchedule)
  }

  override def toString: String = {
    schedule.map(keyVal => s"${keyVal._1.toString} -> ${keyVal._2.toString}").mkString("--")
  }

}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(TreeMap[BeamLeg, Manifest]()(Ordering.by(x=>(x.startTime,x.duration))))
}

case class VehiclePersonId(vehicleId: Id[Vehicle], personId: Id[Person], personRef: Option[ActorRef] = None)

case class Manifest(riders: Set[VehiclePersonId]=Set.empty, boarders: Set[Id[Vehicle]]=Set.empty, alighters: Set[Id[Vehicle]]=Set.empty) {
  override def toString: String = {
    s"[${riders.size}riders;${boarders.size}boarders;${alighters.size}alighters]"
  }
}