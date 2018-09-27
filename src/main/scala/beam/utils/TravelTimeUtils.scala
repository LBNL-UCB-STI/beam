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
}
