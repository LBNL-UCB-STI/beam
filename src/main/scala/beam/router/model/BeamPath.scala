package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.model.RoutingModel.TransitStopsInfo

/**
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

  if (
    linkTravelTime.size > 1 && math.abs(
      math.round(linkTravelTime.tail.sum).toInt - (endPoint.time - startPoint.time)
    ) > 2
  ) {
    throw new IllegalStateException("Total travel time and total sum by edges are not same")
  }

  def toShortString: String = {
    linkIds.headOption match {
      case Some(head) => s"$head .. ${linkIds(linkIds.size - 1)}"
      case None       => ""
    }
  }

  def updateStartTime(newStartTime: Int): BeamPath =
    this.copy(
      startPoint = this.startPoint.copy(time = newStartTime),
      endPoint = this.endPoint.copy(time = newStartTime + this.duration)
    )

  def scaleTravelTimes(scaleBy: Double): BeamPath = {
    val newLinkTimes = this.linkTravelTime.map(travelTime => travelTime * scaleBy)
    val newDuration = if (newLinkTimes.size > 1) { math.round(newLinkTimes.tail.sum).toInt }
    else { 0 }
    this.copy(
      linkTravelTime = newLinkTimes,
      endPoint = this.endPoint.copy(time = this.startPoint.time + newDuration)
    )
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def linkAtTime(tick: Int): Int = {
    tick - startPoint.time match {
      case secondsAlongPath if secondsAlongPath <= 0 || linkIds.size <= 1 =>
        // TODO: there is a likely bug here because linkIds.size can be 0(zero)
        linkIds.head
      case secondsAlongPath if secondsAlongPath > linkTravelTime.tail.sum =>
        linkIds.last
      case secondsAlongPath =>
        val linkTravelTimeTail = linkTravelTime.drop(1)
        val index = linkTravelTimeTail.scanLeft(0.0)((a, b) => a + b).indexWhere(_ >= secondsAlongPath) - 1
        linkIds.drop(1)(index)
    }
  }
}

//case object EmptyBeamPath extends BeamPath(Vector[String](), None, departure = SpaceTime(Double.PositiveInfinity, Double.PositiveInfinity, Long.MaxValue), arrival = SpaceTime(Double.NegativeInfinity, Double.NegativeInfinity, Long.MinValue))
object BeamPath extends Ordering[BeamPath] {
  val empty: BeamPath = BeamPath(Vector[Int](), Vector(), None, SpaceTime(0, 0, 0), SpaceTime(2, 2, 2), 0)

  import scala.annotation.tailrec

  def compareSeq[T: Numeric](xArr: IndexedSeq[T], yArr: IndexedSeq[T])(implicit ev: Numeric[T]): Int = {
    @tailrec
    def loop(idx: Int, shouldStop: Boolean, result: Int): Int = {
      if (shouldStop || idx >= xArr.length) result
      else {
        val a = xArr(idx)
        val b = yArr(idx)
        val res = ev.compare(a, b)
        if (res != 0) {
          // Found the first pairs which are not equal, so the `res` is our result and we should stop immediately
          loop(idx = idx + 1, shouldStop = true, result = res)
        } else {
          // Two elements are equal, keep moving forward
          loop(idx = idx + 1, shouldStop = false, result = res)
        }
      }
    }
    val r = xArr.length.compareTo(yArr.length)
    if (r != 0) r
    else {
      loop(idx = 0, shouldStop = false, result = 0)
    }
  }

  override def compare(x: BeamPath, y: BeamPath): Int = {
    import scala.math.Ordered.orderingToOrdered

    // Compare distance
    var r = x.distanceInM.compare(y.distanceInM)
    if (r != 0) r
    else {
      // Compare start point
      r = x.startPoint.compare(y.startPoint)
      if (r != 0) r
      else {
        // Compare end point
        r = x.endPoint.compare(y.endPoint)
        if (r != 0) r
        else {
          // Transform to the tuples
          val xTransitTuple = x.transitStops.map(TransitStopsInfo.unapply(_).get)
          val yTransitTuple = y.transitStops.map(TransitStopsInfo.unapply(_).get)
          // And compare tuples
          r = xTransitTuple.compareTo(yTransitTuple)
          if (r != 0) r
          else {
            // Compare the length of `linkIds` vector. Keep in mind that `linkIds` and `linkTravelTime` have the same size
            r = x.linkIds.length.compareTo(y.linkIds.length)
            if (r != 0) r
            else {
              // Compare the elements of `linkIds`
              r = BeamPath.compareSeq(x.linkIds, y.linkIds)
              if (r != 0) r
              else {
                // Compare the elements of `linkTravelTime`
                r = BeamPath.compareSeq(x.linkTravelTime, y.linkTravelTime)
                r
              }
            }
          }
        }
      }
    }

  }
}
