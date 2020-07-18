package beam.analysis

import com.conveyal.r5.transit.TransportNetwork

import scala.collection.mutable
import scala.collection.JavaConverters._

object NumberOfVehiclesAnalysisUtil {

  def estimateInUseFleet(transportNetwork: TransportNetwork): mutable.Map[String, Integer] = {
    val tripFleetSizeMap: mutable.HashMap[String, Integer] = mutable.HashMap.empty[String, Integer]
    val startStopsByTime: mutable.PriorityQueue[IdAndTime] =
      mutable.PriorityQueue[IdAndTime]()(Ordering.by(IdAndTimeOrder))
    val tripVehiclesEnRoute = mutable.HashMap.empty[String, mutable.Set[String]]
    transportNetwork.transitLayer.tripPatterns.asScala.foreach { tp =>
      if (tp.hasSchedules) {
        tp.tripSchedules.asScala.toVector foreach { ts =>
          val firstArrival: Int = ts.arrivals(0)
          val lastDeparture: Int = ts.departures(ts.getNStops - 1)
          startStopsByTime.enqueue(IdAndTime(firstArrival, ts.tripId, tp.routeId))
          startStopsByTime.enqueue(IdAndTime(lastDeparture, ts.tripId, tp.routeId))
        }
      }
      tripFleetSizeMap.put(tp.routeId, 0)
    }
    while (startStopsByTime.iterator.hasNext) {
      val nextId = startStopsByTime.dequeue()
      if (!tripVehiclesEnRoute.contains(nextId.route)) {
        tripVehiclesEnRoute.put(nextId.route, mutable.Set())
      }
      val theSet = tripVehiclesEnRoute(nextId.route)
      if (theSet.contains(nextId.id)) {
        tripFleetSizeMap.put(nextId.route, Math.max(theSet.size, tripFleetSizeMap(nextId.route)))
        theSet.remove(nextId.id)
      } else {
        theSet.add(nextId.id)
      }
    }
    tripFleetSizeMap
  }

  case class IdAndTime(time: Int, id: String, route: String)
  def IdAndTimeOrder(d: IdAndTime): Int = -d.time

}
