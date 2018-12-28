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
    this.copy(
      linkTravelTime = this.linkTravelTime.map(travelTime => Math.round(travelTime.toDouble * scaleBy).toInt),
      endPoint = this.endPoint.copy(time = this.startPoint.time + (this.duration.toDouble * scaleBy).toInt)
    )
  }

}

//case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
object BeamPath {
  val empty = BeamPath(Vector[Int](), Vector(), None, null, null, 0)
}
