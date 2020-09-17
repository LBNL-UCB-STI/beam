package beam.utils.google_routes_db.sql

import beam.utils.FileUtils.using
import java.sql.{Connection, PreparedStatement, Statement, Timestamp, Types}
import java.time.{Instant, LocalDateTime}

import com.google.maps.model.{DirectionsLeg, DirectionsRoute}

object Update {

  //
  // GoogleRouteItem
  //

  case class GoogleRouteItem(
    requestId: String,
    departureDateTime: LocalDateTime,
    departureTime: Int,
    boundNortheast: GeometryPoint,
    boundSouthwest: GeometryPoint,
    summary: String,
    copyrights: String,
    googleapiResponsesJsonFileUri: Option[String],
    timestamp: Instant
  )

  object GoogleRouteItem {

    type InsertedGoogleRouteId = Int

    def create(
      route: DirectionsRoute,
      requestId: String,
      departureDateTime: LocalDateTime,
      departureTime: Int,
      googleapiResponsesJsonFileUri: Option[String],
      timestamp: Instant
    ): GoogleRouteItem = GoogleRouteItem(
      requestId = requestId,
      departureTime = departureTime,
      departureDateTime = departureDateTime,
      boundNortheast = makeGeometryPoint(route.bounds.northeast),
      boundSouthwest = makeGeometryPoint(route.bounds.southwest),
      summary = route.summary,
      copyrights = route.copyrights,
      googleapiResponsesJsonFileUri = googleapiResponsesJsonFileUri,
      timestamp = timestamp
    )

    val insertSql: String =
      s"""
         |INSERT INTO gr_route (
         |  request_id, departure_date_time, departure_time,
         |  bound_northeast, bound_southwest,
         |  copyrights, summary, googleapi_responses_json_file_uri, timestamp
         |) VALUES (
         |  ?, ?, ?,
         |  ST_GeometryFromText(?, $projection), ST_GeometryFromText(?, $projection),
         |  ?, ?, ?, ?
         |) RETURNING id
         |""".stripMargin

    implicit val psMapping: PSMapping[Update.GoogleRouteItem] =
      (item: Update.GoogleRouteItem, ps: PreparedStatement) => {
        var i = 1
        ps.setString(i, item.requestId); i += 1
        ps.setTimestamp(i, Timestamp.valueOf(item.departureDateTime)); i += 1
        ps.setInt(i, item.departureTime)    ; i += 1
        ps.setString(i, item.boundNortheast); i += 1
        ps.setString(i, item.boundSouthwest); i += 1
        ps.setString(i, item.copyrights)    ; i += 1
        ps.setString(i, item.summary)       ; i += 1
        item.googleapiResponsesJsonFileUri match {
          case Some(value) => ps.setString(i, value)
          case None => ps.setNull(i, Types.VARCHAR)
        }; i += 1
        ps.setTimestamp(i, Timestamp.from(item.timestamp))
      }

    def insert(
      items: Seq[GoogleRouteItem],
      con: Connection
    ): Map[GoogleRouteItem, InsertedGoogleRouteId] = {
      Update.insertMappableBatch(items, insertSql, con)
    }
  }

  //
  // GoogleRouteLegItem
  //

  case class GoogleRouteLegItem(
    routeId: Long,
    distance: Long,
    distanceText: String,
    duration: Long,
    durationText: String,
    durationInTraffic: Option[Long],
    durationInTrafficText: Option[String],
    endAddress: Option[String],
    endLocation: GeometryPoint,
    startAddress: Option[String],
    startLocation: GeometryPoint,
    steps: GeometryLinestring
  )

  object GoogleRouteLegItem {

    type InsertedGoogleRouteLegId = Int

    def create(routeId: Int, leg: DirectionsLeg): GoogleRouteLegItem = GoogleRouteLegItem(
      routeId = routeId,
      distance = leg.distance.inMeters,
      distanceText = leg.distance.humanReadable,
      duration = leg.duration.inSeconds,
      durationText = leg.duration.humanReadable,
      durationInTraffic = Option(leg.durationInTraffic).map(_.inSeconds),
      durationInTrafficText = Option(leg.durationInTraffic).map(_.humanReadable),
      endAddress = Option(leg.endAddress),
      endLocation = makeGeometryPoint(leg.endLocation),
      startAddress = Option(leg.startAddress),
      startLocation = makeGeometryPoint(leg.startLocation),
      steps = makeGeometryLinestring(
        // Take head.startLocation as first point,
        // take all other endLocations as next points (including head).
        Seq(leg.steps.head.startLocation) ++ leg.steps.map(_.endLocation)
      )
    )

    implicit val psMapping: PSMapping[Update.GoogleRouteLegItem] =
      (item: Update.GoogleRouteLegItem, ps: PreparedStatement) => {
        var i = 1
        ps.setLong(i, item.routeId)       ; i += 1
        ps.setLong(i, item.distance)      ; i += 1
        ps.setString(i, item.distanceText); i += 1
        ps.setLong(i, item.duration)      ; i += 1
        ps.setString(i, item.durationText); i += 1
        item.durationInTraffic match {
          case Some(value) => ps.setLong(i, value)
          case None        => ps.setNull(i, Types.BIGINT)
        }; i += 1
        item.durationInTrafficText match {
          case Some(value) => ps.setString(i, value)
          case None        => ps.setNull(i, Types.VARCHAR)
        }; i += 1
        item.endAddress match {
          case Some(value) => ps.setString(i, value)
          case None        => ps.setNull(i, Types.VARCHAR)
        }; i += 1
        ps.setString(i, item.endLocation)  ; i += 1
        item.startAddress match {
          case Some(value) => ps.setString(i, value)
          case None        => ps.setNull(i, Types.VARCHAR)
        }; i += 1
        ps.setString(i, item.startLocation); i += 1
        ps.setString(i, item.steps)        ; i += 1
      }

    val insertSql: String =
      s"""
         |INSERT INTO gr_route_leg (
         |  route_id, distance, distance_text,
         |  duration, duration_text,
         |  duration_in_traffic, duration_in_traffic_text,
         |  end_address, end_location,
         |  start_address, start_location,
         |  steps
         |) VALUES (
         |  ?, ?, ?,
         |  ?, ?,
         |  ?, ?,
         |  ?, ST_GeometryFromText(?, $projection),
         |  ?, ST_GeometryFromText(?, $projection),
         |  ST_GeometryFromText(?, $projection)
         |)
         |""".stripMargin

    def insert(
      items: Seq[GoogleRouteLegItem],
      con: Connection
    ): Map[GoogleRouteLegItem, InsertedGoogleRouteLegId] = {
      Update.insertMappableBatch(items, insertSql, con)
    }
  }

  private def insertMappableBatch[T : PSMapping](
    items: Seq[T],
    sql: String,
    con: Connection
  ): Map[T, Int] = {
    using(
      con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    ) { ps =>
      items.foreach { item =>
        implicitly[PSMapping[T]].mapPrepared(item, ps)
        ps.addBatch()
      }
      ps.executeBatch()

      val keysRS = ps.getGeneratedKeys

      items.map { item =>
        keysRS.next()
        (item, keysRS.getInt(1))
      }.toMap
    }
  }
}
