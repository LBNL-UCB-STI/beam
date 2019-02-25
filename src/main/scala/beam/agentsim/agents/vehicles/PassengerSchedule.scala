package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.router.model.BeamLeg
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
    var newSchedule = schedule ++ legs.map(leg => {
      val manifest: Manifest = schedule.getOrElse(leg, Manifest())
      (leg, manifest.copy(riders = manifest.riders + passenger))
    })
    newSchedule = newSchedule ++ legs.headOption.map(boardLeg => {
      val manifest: Manifest = newSchedule.getOrElse(boardLeg, Manifest())
      (boardLeg, manifest.copy(boarders = manifest.boarders + passenger))
    })
    newSchedule = newSchedule ++ legs.lastOption.map(alightLeg => {
      val manifest: Manifest = newSchedule.getOrElse(alightLeg, Manifest())
      (alightLeg, manifest.copy(alighters = manifest.alighters + passenger))
    })
    PassengerSchedule(newSchedule)
  }

  def legsBeforePassengerBoards(passenger: VehiclePersonId): List[BeamLeg] = {
    schedule.takeWhile(legManifest => !legManifest._2.riders.contains(passenger)).keys.toList
  }

  def legsWithPassenger(passenger: VehiclePersonId): List[BeamLeg] = {
    schedule.filter(legManifest => legManifest._2.riders.contains(passenger)).keys.toList
  }

  override def toString: String = {
    schedule.map(keyVal => s"${keyVal._1.toString} -> ${keyVal._2.toString}").mkString("--")
  }

}

//Specialized copy of Ordering.by[Tuple2] so we can control compare
//Also has the benefit of not requiring allocation of a Tuple2, which turned outWriter to be costly at scale
object BeamLegOrdering extends Ordering[BeamLeg] {

  def compare(a: BeamLeg, b: BeamLeg): Int = {
    val compare1 = java.lang.Long.compare(a.startTime, b.startTime)
    if (compare1 != 0) compare1
    else {
      val compare2 = java.lang.Long.compare(a.duration, b.duration)
      if (compare2 != 0) compare2
      else {
        val compare3 = a.travelPath == b.travelPath
        if (!compare3) 1
        else 0
      }
    }
  }
}

object PassengerSchedule {

  def apply(): PassengerSchedule =
    new PassengerSchedule(TreeMap[BeamLeg, Manifest]()(BeamLegOrdering))
}

case class VehiclePersonId(
  vehicleId: Id[Vehicle],
  personId: Id[Person],
  personRef: ActorRef
)

case class Manifest(
  riders: Set[VehiclePersonId] = Set.empty,
  boarders: Set[VehiclePersonId] = Set.empty,
  alighters: Set[VehiclePersonId] = Set.empty
) {
  override def toString: String = {
    s"[${riders.size}riders;${boarders.size}boarders;${alighters.size}alighters]"
  }
}
