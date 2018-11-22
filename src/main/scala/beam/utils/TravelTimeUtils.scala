package beam.utils

object TravelTimeUtils {

  def getAverageTravelTime(linkTravelTime: IndexedSeq[Int]): IndexedSeq[Int] = {
    if (linkTravelTime.isEmpty) Vector()
    else {
      // FIXME There are the way to make it faster and even lazy
      // We average travel time for the first link
      linkTravelTime.zipWithIndex.map {
        case (travelTime, idx) =>
          if (idx == 0) {
            travelTime / 2
          } else {
            travelTime
          }
      }
    }
  }

  def scaleTravelTime(newTravelTime: Int, originalTravelTime: Int, linkTravelTime: IndexedSeq[Int]): IndexedSeq[Int] = {
    if (linkTravelTime.nonEmpty) {
      if (originalTravelTime != 0) {
        val ratio = newTravelTime.toDouble / originalTravelTime
        val newLinkTravelTimes = linkTravelTime.map { t => (t * ratio).toInt
        }.toArray
        val delta = newTravelTime - newLinkTravelTimes.sum
        val newLast = newLinkTravelTimes.last + delta
        newLinkTravelTimes.update(newLinkTravelTimes.length - 1, newLast)
        newLinkTravelTimes
      }
      else {
        linkTravelTime
      }
    }
    else {
      IndexedSeq.empty
    }
  }
}
