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

  /**
    * This will append nextLeg to the current leg and update the times in the merged leg to be consistent.
    * The mode of the resulting leg will be based on this leg. Transit stops are not merged, they take the base leg value.
    */
  def appendLeg(nextLeg: BeamLeg): BeamLeg = {
    val newTravelPath = BeamPath(
      travelPath.linkIds ++ nextLeg.travelPath.linkIds,
      travelPath.linkTravelTime ++ nextLeg.travelPath.linkTravelTime,
      travelPath.transitStops,
      travelPath.startPoint,
      nextLeg.travelPath.endPoint,
      travelPath.distanceInM + nextLeg.travelPath.distanceInM
    )
    this.copy(travelPath = newTravelPath).updateStartTime(startTime)
  }

  override def toString: String =
    s"BeamLeg($mode @ $startTime,dur:$duration,path: ${travelPath.toShortString})"
}

object BeamLeg {

  def dummyLeg(startTime: Int, mode: BeamMode = WALK): BeamLeg =
    new BeamLeg(0, mode, 0, BeamPath(Vector(), Vector(), None, SpaceTime.zero, SpaceTime.zero, 0))
      .updateStartTime(startTime)

  def makeLegsConsistent(legs: List[BeamLeg]): List[BeamLeg] = {
    if (legs.size > 0) {
      var runningStartTime = legs.head.startTime
      for (leg <- legs) yield {
        val newLeg = leg.updateStartTime(runningStartTime)
        runningStartTime = newLeg.endTime
        newLeg
      }
    } else { legs }
  }
}
