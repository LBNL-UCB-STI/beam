package beam.utils.gtfs
import java.util

import beam.agentsim.infrastructure.geozone.WgsCoordinate

object Model {

  object Agency {

    def apply(source: String)(record: java.util.Map[String, String]): Agency = {
      def str(name: String): String = record.get(name) match {
        case null      => ""
        case s: String => s.trim
      }

      Agency(
        str("agency_id"),
        str("agency_name"),
        str("agency_url"),
        source
      )
    }
  }

  case class Agency(id: String, name: String, url: String, source: String)

  // routes.txt     : route_id -> route_type, route_long_name
  object Route {

    def apply(source: String, defaultAgencyId: String)(record: java.util.Map[String, String]): Route = {
      def str(name: String, defaultValue: String = ""): String = record.get(name) match {
        case null      => defaultValue
        case s: String => s.trim
      }

      Route(
        str("route_id"),
        str("agency_id", defaultAgencyId),
        str("route_type"),
        str("route_long_name"),
        source
      )
    }
  }

  case class Route(id: String, agencyId: String, routeType: String, longName: String, source: String)

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
    val wgsPoint: WgsCoordinate = WgsCoordinate(lat, lon)
  }

}
