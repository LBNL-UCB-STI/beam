package beam.utils.gtfs
import java.util

import beam.agentsim.infrastructure.geozone.WgsCoordinate

object Model {

  // routes.txt     : route_id -> route_type, route_long_name
  object Route {

    def apply(source: String)(record: java.util.Map[String, String]): Route =
      Route(record.get("route_id").trim, record.get("route_type").trim, record.get("route_long_name"), source)
  }

  case class Route(id: String, routeType: String, longName: String, source: String)

  // trips.txt      : route_id -> trip_id
  object Trip {

    def apply(record: util.Map[String, String]): Trip = {
      Trip(record.get("route_id"), record.get("trip_id"))
    }
  }

  case class Trip(routeId: String, tripId: String)

  // stop_times.txt : trip_id -> stop_id
  object StopTime {

    def apply(record: util.Map[String, String]): StopTime = {
      StopTime(record.get("trip_id").trim, record.get("stop_id").trim)
    }
  }

  case class StopTime(tripId: String, stopId: String)

  // stops.txt      : stop_id -> stop_lat, stop_lon
  object Stop {

    def apply(record: util.Map[String, String]): Stop = {
      Stop(record.get("stop_id").trim, record.get("stop_lon").trim.toDouble, record.get("stop_lat").trim.toDouble)
    }
  }

  case class Stop(id: String, lon: Double, lat: Double) {
    val wgsPoint:WgsCoordinate = WgsCoordinate(lat, lon)
  }

}
