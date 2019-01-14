package beam.utils

object TravelTimeUtils {

  def scaleTravelTime(newTravelTime: Int, originalTravelTime: Int, linkTravelTime: IndexedSeq[Int]): IndexedSeq[Int] = {
    if (linkTravelTime.nonEmpty) {
      if (originalTravelTime != 0) {
        val ratio = newTravelTime.toDouble / originalTravelTime
        val newLinkTravelTimes = linkTravelTime.map { t =>
          Math.round(t * ratio).toInt
        }.toArray
        val delta = newTravelTime - newLinkTravelTimes.sum
        val newLast = newLinkTravelTimes.last + delta
        newLinkTravelTimes.update(newLinkTravelTimes.length - 1, newLast)
        newLinkTravelTimes
      } else {
        linkTravelTime
      }
    } else {
      IndexedSeq.empty
    }
  }
}
