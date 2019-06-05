package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.model.RoutingModel.TransitStopsInfo

/**
  *
  * @param linkIds      either matsim linkId or R5 edgeIds that describes whole path
  * @param transitStops start and end stop if this path is transit (partial) route
  */
case class BeamPath(
  linkIds: IndexedSeq[Int],
  linkTravelTime: IndexedSeq[Int],
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
        point.loc == null || point.loc.getX > -180 && point.loc.getX < 180 && point.loc.getY > -90 && point.loc.getY < 90,
        s"Bad coordinate ${point.loc}"
      )
    }
  }

  def duration: Int = endPoint.time - startPoint.time

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
    val newLinkTimes = this.linkTravelTime.map(travelTime => Math.round(travelTime.toDouble * scaleBy).toInt)
    this.copy(
      linkTravelTime = newLinkTimes,
      endPoint = this.endPoint.copy(time = this.startPoint.time + newLinkTimes.tail.sum)
    )
  }
  def linkAtTime(tick: Int): Int = {
    tick - startPoint.time match {
      case secondsAlongPath if secondsAlongPath <= 0 =>
        linkIds.head
      case secondsAlongPath if secondsAlongPath > linkTravelTime.sum =>
        linkIds.last
      case secondsAlongPath =>
        linkIds(linkTravelTime.scanLeft(0)((a, b) => a + b).indexWhere(_ >= secondsAlongPath) - 1)
    }
  }
}

//case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
object BeamPath {
  val empty = BeamPath(Vector[Int](), Vector(), None, null, null, 0)
}
