package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK

/**
  *
  * @param startTime  time in seconds from base midnight
  * @param mode       BeamMode
  * @param duration   period in seconds
  * @param travelPath BeamPath
  */
case class BeamLeg(startTime: Int, mode: BeamMode, duration: Int, travelPath: BeamPath) {
  val endTime: Int = startTime + duration

  def updateLinks(newLinks: IndexedSeq[Int]): BeamLeg =
    this.copy(travelPath = this.travelPath.copy(newLinks))

  def updateStartTime(newStartTime: Int): BeamLeg = {
    val newTravelPath = this.travelPath.updateStartTime(newStartTime)
    this
      .copy(
        startTime = newStartTime,
        duration = newTravelPath.endPoint.time - newStartTime,
        travelPath = newTravelPath
      )

  }

  def scaleLegDuration(scaleBy: Double): BeamLeg = {
    val newTravelPath = this.travelPath.scaleTravelTimes(scaleBy)
    this
      .copy(
        duration = newTravelPath.duration,
        travelPath = newTravelPath
      )

  }

  override def toString: String =
    s"BeamLeg($mode @ $startTime,dur:$duration,path: ${travelPath.toShortString})"
}

object BeamLeg {

  def dummyWalk(startTime: Int): BeamLeg =
    new BeamLeg(0, WALK, 0, BeamPath(Vector(), Vector(), None, SpaceTime.zero, SpaceTime.zero, 0))
      .updateStartTime(startTime)

  def makeLegsConsistent(legs: List[Option[BeamLeg]]): List[Option[BeamLeg]] = {
    if (legs.filter(_.isDefined).size > 0) {
      var runningStartTime = legs.find(_.isDefined).head.get.startTime
      for (legOpt <- legs) yield {
        val newLeg = legOpt.map(leg => leg.updateStartTime(runningStartTime))
        runningStartTime = newLeg.map(_.endTime).getOrElse(runningStartTime)
        newLeg
      }
    } else { legs }
  }

  def makeVectorLegsConsistent(legs: Vector[BeamLeg]): Vector[BeamLeg] = {
    var runningStartTime = legs.head.startTime
    for (leg <- legs) yield {
      val newLeg = leg.updateStartTime(runningStartTime)
      runningStartTime = newLeg.endTime
      newLeg
    }
  }
}
