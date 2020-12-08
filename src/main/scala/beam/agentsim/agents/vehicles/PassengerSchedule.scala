package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.PassengerSchedule.Manifest
import beam.router.BeamRouter.Location
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import scala.collection.immutable.TreeMap

/**
  * Holds information about the numbers and identities of agents in on board a vehicle
  * and the BeamLegs they are taking through the network
  */
case class PassengerSchedule(schedule: TreeMap[BeamLeg, Manifest]) {

  def addLegs(legs: Seq[BeamLeg]): PassengerSchedule = {
    PassengerSchedule(schedule ++ legs.map(leg => (leg, Manifest())))
  }

  def addPassenger(passenger: PersonIdWithActorRef, legs: Seq[BeamLeg]): PassengerSchedule = {
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

  def removePassengerBoarding(passenger: PersonIdWithActorRef): PassengerSchedule = {
    var newSchedule = TreeMap[BeamLeg, Manifest]()(BeamLegOrdering)
    schedule.foreach { legAndMan =>
      newSchedule = newSchedule + (legAndMan._1 -> legAndMan._2.copy(boarders = legAndMan._2.boarders - passenger))
    }
    new PassengerSchedule(newSchedule)
  }

  def legsBeforePassengerBoards(passenger: PersonIdWithActorRef): List[BeamLeg] = {
    schedule.takeWhile(legManifest => !legManifest._2.riders.contains(passenger)).keys.toList
  }

  def legsWithPassenger(passenger: PersonIdWithActorRef): List[BeamLeg] = {
    schedule.filter(legManifest => legManifest._2.riders.contains(passenger)).keys.toList
  }

  def updateStartTimes(newStartTimeOfFirstLeg: Int): PassengerSchedule = {
    var newSchedule = TreeMap[BeamLeg, Manifest]()(BeamLegOrdering)
    var runningStartTime = newStartTimeOfFirstLeg
    schedule.foreach { legAndMan =>
      val newLeg = legAndMan._1.updateStartTime(Math.max(runningStartTime, legAndMan._1.startTime))
      runningStartTime = newLeg.endTime
      newSchedule = newSchedule + (newLeg -> legAndMan._2)
    }
    new PassengerSchedule(newSchedule)
  }

  def uniquePassengers: Set[PersonIdWithActorRef] = schedule.values.flatMap(_.riders).toSet

  def passengersWhoNeverBoard: Set[PersonIdWithActorRef] = {
    val allBoarders = schedule.values.flatMap(_.boarders).toSet
    uniquePassengers.diff(allBoarders)
  }

  def numUniquePassengers: Int = schedule.values.flatMap(_.riders).toSet.size

  def numLegsWithPassengersAfter(legIndex: Int): Int =
    schedule.slice(legIndex, schedule.size).values.count(_.riders.nonEmpty)

  def linkAtTime(tick: Int): Int = {
    if (tick < schedule.keys.head.startTime) {
      schedule.keys.head.travelPath.linkIds.head
    } else {
      schedule.keys.toList.reverse.find(_.startTime <= tick).map(_.travelPath.linkAtTime(tick)).get
    }
  }

  def locationAtTime(tick: Int, beamServices: BeamServices): Location = {
    beamServices.networkHelper.getLink(linkAtTime(tick)).get.getCoord
  }

  override def toString: String = {
    schedule.map(keyVal => s"${keyVal._1.toString} -> ${keyVal._2.toString}").mkString("--")
  }

}

//Specialized copy of Ordering.by[Tuple2] so we can control compare
//Also has the benefit of not requiring allocation of a Tuple2, which turned out to be costly at scale
object BeamLegOrdering extends Ordering[BeamLeg] {

  def compare(a: BeamLeg, b: BeamLeg): Int = {
    val compare1 = java.lang.Long.compare(a.startTime, b.startTime)
    if (compare1 != 0) compare1
    else {
      val compare2 = java.lang.Long.compare(a.duration, b.duration)
      if (compare2 != 0) compare2
      else {
        BeamPath.compare(a.travelPath, b.travelPath)
      }
    }
  }
}

object PassengerSchedule {

  def apply(): PassengerSchedule =
    new PassengerSchedule(TreeMap[BeamLeg, Manifest]()(BeamLegOrdering))

  def newFromMap(beamLegToManifest: Map[BeamLeg, Manifest]): PassengerSchedule = {
    new PassengerSchedule(TreeMap(beamLegToManifest.toArray: _*)(BeamLegOrdering))
  }

  case class Manifest(
    riders: Set[PersonIdWithActorRef] = Set.empty,
    boarders: Set[PersonIdWithActorRef] = Set.empty,
    alighters: Set[PersonIdWithActorRef] = Set.empty
  ) {
    override def toString: String = {
      s"[${riders.size}riders;${boarders.size}boarders;${alighters.size}alighters]"
    }
  }
}

case class PersonIdWithActorRef(personId: Id[Person], personRef: ActorRef)
