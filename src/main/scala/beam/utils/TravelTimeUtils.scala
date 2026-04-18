package beam.utils

object TravelTimeUtils {

  def clampTravelTimeSeconds(travelTime: Double): Double = math.max(0.0, travelTime)

  def scaleTravelTime(
    newTravelTime: Int,
    originalTravelTime: Int,
    linkTravelTime: IndexedSeq[Double]
  ): IndexedSeq[Double] = {
    if (linkTravelTime.nonEmpty) {
      if (originalTravelTime != 0) {
        val ratio = newTravelTime.toDouble / originalTravelTime
        val newLinkTravelTimes = linkTravelTime.map(time => clampTravelTimeSeconds(time * ratio)).toArray
        val delta = newTravelTime - newLinkTravelTimes.sum
        val newLast = clampTravelTimeSeconds(newLinkTravelTimes.last + delta)
        newLinkTravelTimes.update(newLinkTravelTimes.length - 1, newLast)
        newLinkTravelTimes
      } else {
        linkTravelTime.map(clampTravelTimeSeconds)
      }
    } else {
      IndexedSeq.empty
    }
  }
}
