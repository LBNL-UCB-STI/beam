package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.model.RoutingModel.TransitStopsInfo

/**
  *
  * @param linkIds      either matsim linkId or R5 edgeIds that describes whole path
  * @param transitStops start and end stop if this path is transit (partial) route
  *
  * IMPORTANT NOTE: Convention is that a BeamPath starts at the **end** of the first link and ends at the end of the last link.
  * We therefore ignore the first link in estimating travel time.
  */
case class BeamPath(
  linkIds: IndexedSeq[Int],
  linkTravelTime: IndexedSeq[Double],
  transitStops: Option[TransitStopsInfo],
  startPoint: SpaceTime,
  endPoint: SpaceTime,
  distanceInM: Double
) {

  checkCoordinates(startPoint)
  checkCoordinates(endPoint)

  private def checkCoordinates(point: SpaceTime) {
    if (point != null) {
      assert(
        point.loc == null || (point.loc.getX > -180 && point.loc.getX < 180 && point.loc.getY > -90 && point.loc.getY < 90),
        s"Bad coordinate ${point.loc}"
      )
    }
  }

  def duration: Int = endPoint.time - startPoint.time

  if (linkTravelTime.size > 1 && math.abs(math.round(linkTravelTime.tail.sum).toInt - (endPoint.time - startPoint.time)) > 2) {
    assert(false)
  }

  def toShortString: String =
    if (linkIds.nonEmpty) {
      s"${linkIds.head} .. ${linkIds(linkIds.size - 1)}"
    } else {
      ""
    }

  def updateStartTime(newStartTime: Int): BeamPath =
    this.copy(
      startPoint = this.startPoint.copy(time = newStartTime),
      endPoint = this.endPoint.copy(time = newStartTime + this.duration)
    )

  def scaleTravelTimes(scaleBy: Double): BeamPath = {
    val newLinkTimes = this.linkTravelTime.map(travelTime => travelTime * scaleBy)
    val newDuration = if (newLinkTimes.size > 1) { math.round(newLinkTimes.tail.sum).toInt } else { 0 }
    this.copy(
      linkTravelTime = newLinkTimes,
      endPoint = this.endPoint.copy(time = this.startPoint.time + newDuration)
    )
  }

  def linkAtTime(tick: Int): Int = {
    tick - startPoint.time match {
      case secondsAlongPath if secondsAlongPath <= 0 || linkIds.size <= 1 =>
        linkIds.head
      case secondsAlongPath if secondsAlongPath > linkTravelTime.tail.sum =>
        linkIds.last
      case secondsAlongPath =>
        if (linkTravelTime.tail.scanLeft(0.0)((a, b) => a + b).indexWhere(_ >= secondsAlongPath) < 0) {
          val i = 0
        }
        linkIds.tail(linkTravelTime.tail.scanLeft(0.0)((a, b) => a + b).indexWhere(_ >= secondsAlongPath) - 1)
    }
  }
}

//case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
object BeamPath {
  val empty: BeamPath = BeamPath(Vector[Int](), Vector(), None, null, null, 0)
}
