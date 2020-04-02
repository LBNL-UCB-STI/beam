package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import beam.sim.common.GeoUtils
import beam.utils.{NetworkHelper, TravelTimeUtils}

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
        travelPath = newTravelPath
      )
  }

  def scaleToNewDuration(newDuration: Int): BeamLeg = {
    val newTravelPath = this.travelPath.scaleTravelTimes(newDuration.toDouble / this.duration.toDouble)
    this
      .copy(
        duration = newDuration,
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

  /**
    * SubLegBefore
    * Returns a new BeamLeg composed as if one traversed the original BeamLeg until throughTime
    */
  def subLegThrough(throughTime: Int, networkHelper: NetworkHelper, geoUtils: GeoUtils): BeamLeg = {
    val linkAtTime = this.travelPath.linkAtTime(throughTime)
    val indexOfNewEndLink = this.travelPath.linkIds.indexWhere(_ == linkAtTime)
    val newDuration = if (indexOfNewEndLink < 1) { 0 } else {
      math.round(this.travelPath.linkTravelTime.take(indexOfNewEndLink + 1).tail.sum).toInt
    }
    val newEndPoint = SpaceTime(
      geoUtils.utm2Wgs(networkHelper.getLink(this.travelPath.linkIds(indexOfNewEndLink)).get.getCoord),
      this.startTime + newDuration
    )
    val newTravelPath = this.travelPath.copy(
      linkIds = this.travelPath.linkIds.take(indexOfNewEndLink + 1),
      linkTravelTime = this.travelPath.linkTravelTime.take(indexOfNewEndLink + 1),
      endPoint = newEndPoint
    )
    this.copy(duration = newTravelPath.duration, travelPath = newTravelPath)
  }

  override def toString: String =
    s"BeamLeg($mode @ $startTime,dur:$duration,path: ${travelPath.toShortString})"
}

object BeamLeg {

  def dummyLeg(startTime: Int, location: Location, mode: BeamMode = WALK, duration: Int = 0): BeamLeg =
    new BeamLeg(
      0,
      mode,
      duration,
      BeamPath(Vector(), Vector(), None, SpaceTime(location, startTime), SpaceTime(location, startTime), 0)
    ).updateStartTime(startTime)

  def makeLegsConsistent(legs: List[Option[BeamLeg]]): List[Option[BeamLeg]] = {
    if (legs.filter(_.isDefined).nonEmpty) {
      var runningStartTime = legs.find(_.isDefined).head.get.startTime
      for (legOpt <- legs) yield {
        val newLeg = legOpt.map(leg => leg.updateStartTime(runningStartTime))
        runningStartTime = newLeg.map(_.endTime).getOrElse(runningStartTime)
        newLeg
      }
    } else { legs }
  }

  def makeVectorLegsConsistentAsTrip(legs: List[BeamLeg]): List[BeamLeg] = {
    legs.isEmpty match {
      case true =>
        legs
      case false =>
        var runningStartTime = legs.head.startTime
        for (leg <- legs) yield {
          val newLeg = leg.updateStartTime(runningStartTime)
          runningStartTime = newLeg.endTime
          newLeg
        }
    }
  }

  def makeVectorLegsConsistentAsOrderdStandAloneLegs(legs: Vector[BeamLeg]): Vector[BeamLeg] = {
    legs.isEmpty match {
      case true =>
        legs
      case false =>
        var latestEndTime = legs.head.startTime - 1
        var newLeg = legs.head
        for (leg <- legs) yield {
          if (leg.startTime < latestEndTime) {
            newLeg = leg.updateStartTime(latestEndTime)
          } else {
            newLeg = leg
          }
          latestEndTime = newLeg.endTime
          newLeg
        }
    }
  }
}
