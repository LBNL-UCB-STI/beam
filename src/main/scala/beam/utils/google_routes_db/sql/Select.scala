package beam.utils.google_routes_db.sql

import java.sql.{PreparedStatement, ResultSet}
import java.time.LocalDateTime

import beam.agentsim.events.PathTraversalEvent
import com.google.maps.model.LatLng

import scala.collection.mutable

object Select {

  object QueryGoogleRoutes {

    case class Arg(
      originLat: Double,
      originLon: Double,
      destLat: Double,
      destLon: Double,
      departureDateTime: LocalDateTime,
      departureTime: Int
    )

    def makeArg(
      pte: PathTraversalEvent,
      departureDateTime: LocalDateTime
    ): Arg = {
      Arg(
        originLat = pte.startY,
        originLon = pte.startX,
        destLat = pte.endY,
        destLon = pte.endX,
        departureDateTime,
        pte.departureTime
      )
    }

    type GoogleRouteItemId = Int

    case class Item(
      id: GoogleRouteItemId,
      requestId: String,
      departureDateTime: LocalDateTime,
      departureTime: Int,
      distance: Long,
      duration: Long,
      durationInTraffic: Option[Long]
    )

    val sql: String =
      s"""
        |SELECT
        |  route.id as route_id,
        |  route.request_id as request_id,
        |  route.departure_date_time,
        |  route.departure_time,
        |  leg.distance,
        |  leg.duration,
        |  leg.duration_in_traffic
        |FROM
        |  (SELECT
        |     ST_GeometryFromText(?, $projection) :: GEOMETRY as origin,
        |     ST_GeometryFromText(?, $projection) :: GEOMETRY as dest,
        |     ? :: INTEGER as departure_time,
        |     ? :: TIMESTAMP as departure_date_time
        |  ) args,
        |  gr_route route,
        |  gr_route_leg leg
        |WHERE route.id = leg.route_id
        |  AND ST_Distance(leg.start_location, args.origin) < 0.0001
        |  AND ST_Distance(leg.end_location, args.dest) < 0.0001
        |  AND route.departure_time % 3600 - args.departure_time % 3600 = 0
        |  AND extract(EPOCH from date_trunc('day', route.departure_date_time)) =
        |      extract(EPOCH from date_trunc('day', args.departure_date_time))
        |ORDER BY leg.duration_in_traffic, leg.distance
        |""".stripMargin

    val argPsMapping: PSMapping[Arg] =
      (arg: Arg, ps: PreparedStatement) => {
        ps.setString(1, makeGeometryPoint(new LatLng(arg.originLat, arg.originLon)))
        ps.setString(2, makeGeometryPoint(new LatLng(arg.destLat, arg.destLon)))
        ps.setInt(3, arg.departureTime)
        ps.setTimestamp(4, java.sql.Timestamp.valueOf(arg.departureDateTime))
      }

    def readItems(rs: ResultSet): Seq[Item] = {
      val result = mutable.ArrayBuffer.empty[Item]
      while (rs.next()) {
        var durationInTraffic: Option[Long] = Some(rs.getLong("duration_in_traffic"))
        if (rs.wasNull()) { durationInTraffic = None }
        result.append(
          Item(
            id = rs.getInt("route_id"),
            requestId = rs.getString("request_id"),
            departureDateTime = rs.getTimestamp("departure_date_time").toLocalDateTime,
            departureTime = rs.getInt("departure_time"),
            distance = rs.getLong("distance"),
            duration = rs.getLong("duration"),
            durationInTraffic = durationInTraffic
          )
        )
      }
      result
    }
  }
}
