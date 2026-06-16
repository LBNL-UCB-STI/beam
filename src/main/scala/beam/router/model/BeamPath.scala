package beam.router.model

import beam.agentsim.events.SpaceTime
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.utils.TravelTimeUtils

/**
  * @param linkIds      either matsim linkId or R5 edgeIds that describes whole path
  * @param transitStops start and end stop if this path is transit (partial) route
  *
  * IMPORTANT NOTE: Convention is that a BeamPath starts at the **end** of the first link and ends at the end of the last link.
  * We therefore ignore the first link in estimating travel time.
  */
case class BeamPath(
  linkIds: Array[Int],
  linkTravelTime: Array[Double],
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
    linkTravelTime.length > 1 && math.abs(
      math.round(linkTravelTime.tail.sum).toInt - (endPoint.time - startPoint.time)
    ) > 2
  ) {
    throw new IllegalStateException("Total travel time and total sum by edges are not same")
  }

  def toShortString: String = {
    linkIds.headOption match {
      case Some(head) => s"$head .. ${linkIds(linkIds.length - 1)}"
      case None       => ""
    }
  }

  def updateStartTime(newStartTime: Int): BeamPath =
    this.copy(
      startPoint = this.startPoint.copy(time = newStartTime),
      endPoint = this.endPoint.copy(time = newStartTime + this.duration)
    )

  def scaleTravelTimes(scaleBy: Double): BeamPath = {
    val newLinkTimes =
      this.linkTravelTime.map(travelTime => TravelTimeUtils.clampTravelTimeSeconds(travelTime * scaleBy))
    val newDuration = if (newLinkTimes.length > 1) { math.round(newLinkTimes.tail.sum).toInt }
    else { 0 }
    this.copy(
      linkTravelTime = newLinkTimes,
      endPoint = this.endPoint.copy(time = this.startPoint.time + newDuration)
    )
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def linkAtTime(tick: Int): Int = {
    tick - startPoint.time match {
      case secondsAlongPath if secondsAlongPath <= 0 || linkIds.length <= 1 =>
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
  val empty: BeamPath = BeamPath(Array[Int](), Array[Double](), None, SpaceTime(0, 0, 0), SpaceTime(2, 2, 2), 0)

  import scala.annotation.tailrec

  @tailrec
  private def compareIntArray(xArr: Array[Int], yArr: Array[Int], idx: Int = 0): Int = {
    if (idx >= xArr.length) 0
    else {
      val cmp = java.lang.Integer.compare(xArr(idx), yArr(idx))
      if (cmp != 0) cmp
      else compareIntArray(xArr, yArr, idx + 1)
    }
  }

  @tailrec
  private def compareDoubleArray(xArr: Array[Double], yArr: Array[Double], idx: Int = 0): Int = {
    if (idx >= xArr.length) 0
    else {
      val cmp = java.lang.Double.compare(xArr(idx), yArr(idx))
      if (cmp != 0) cmp
      else compareDoubleArray(xArr, yArr, idx + 1)
    }
  }

  // TODO: looks like a bug on scapegoat.
  //  it does not recognize the usage of implicit ev variable (used inside internal function)
  @SuppressWarnings(Array("UnusedMethodParameter"))
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
    // Compare distance
    var r = java.lang.Double.compare(x.distanceInM, y.distanceInM)
    if (r != 0) return r

    // Compare start point
    r = x.startPoint.compare(y.startPoint)
    if (r != 0) return r

    // Compare end point
    r = x.endPoint.compare(y.endPoint)
    if (r != 0) return r

    // Compare transitStops without creating tuples
    r = (x.transitStops, y.transitStops) match {
      case (None, None)                     => 0
      case (None, Some(_))                  => -1
      case (Some(_), None)                  => 1
      case (Some(xTransit), Some(yTransit)) =>
        // Compare fields directly - no tuple allocation
        var cmp = xTransit.agencyId.compareTo(yTransit.agencyId)
        if (cmp != 0) cmp
        else {
          cmp = xTransit.routeId.compareTo(yTransit.routeId)
          if (cmp != 0) cmp
          else {
            cmp = xTransit.vehicleId.compareTo(yTransit.vehicleId)
            if (cmp != 0) cmp
            else {
              cmp = java.lang.Integer.compare(xTransit.fromIdx, yTransit.fromIdx)
              if (cmp != 0) cmp
              else java.lang.Integer.compare(xTransit.toIdx, yTransit.toIdx)
            }
          }
        }
    }
    if (r != 0) return r

    // Compare array lengths
    r = java.lang.Integer.compare(x.linkIds.length, y.linkIds.length)
    if (r != 0) return r

    // Compare linkIds - specialized, no boxing
    r = compareIntArray(x.linkIds, y.linkIds)
    if (r != 0) return r

    // Compare linkTravelTime - specialized, no boxing
    compareDoubleArray(x.linkTravelTime, y.linkTravelTime)
  }

}
