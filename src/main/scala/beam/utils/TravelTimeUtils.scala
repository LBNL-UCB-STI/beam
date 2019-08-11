package beam.utils

object TravelTimeUtils {

  def scaleTravelTime(
    newTravelTime: Int,
    originalTravelTime: Int,
    linkTravelTime: IndexedSeq[Double]
  ): IndexedSeq[Double] = {
    if (linkTravelTime.nonEmpty) {
      if (originalTravelTime != 0) {
        val ratio = newTravelTime.toDouble / originalTravelTime
        val newLinkTravelTimes = linkTravelTime.map { _ * ratio }.toArray
        val delta = newTravelTime - newLinkTravelTimes.sum
        val newLast = newLinkTravelTimes.last + delta
        newLinkTravelTimes.update(newLinkTravelTimes.length - 1, newLast)
        if (newLinkTravelTimes.sum != newTravelTime) {
          val i = 0
        }
        newLinkTravelTimes
      } else {
        linkTravelTime
      }
    } else {
      IndexedSeq.empty
    }
  }
}
