package beam.utils.google_routes_db.sql

import beam.utils.FileUtils.using
import java.sql.{Connection, PreparedStatement, Statement, Timestamp, Types}
import java.time.{Instant, LocalDateTime}

import beam.utils.mapsapi.googleapi.route.GoogleRoute

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
      googleRoute: GoogleRoute,
      requestId: String,
      departureDateTime: LocalDateTime,
      departureTime: Int,
      googleapiResponsesJsonFileUri: Option[String],
      timestamp: Instant
    ): GoogleRouteItem = GoogleRouteItem(
      requestId = requestId,
      departureTime = departureTime,
      departureDateTime = departureDateTime,
      boundNortheast = makeGeometryPoint(googleRoute.bounds.northeast),
      boundSouthwest = makeGeometryPoint(googleRoute.bounds.southwest),
      summary = googleRoute.summary,
      copyrights = googleRoute.copyrights,
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
    routeId: Int,
    distance: Int,
    distanceText: String,
    duration: Int,
    durationText: String,
    durationInTraffic: Option[Int],
    durationInTrafficText: Option[String],
    endAddress: String,
    endLocation: GeometryPoint,
    startAddress: Option[String],
    startLocation: GeometryPoint,
    steps: GeometryLinestring
  )

  object GoogleRouteLegItem {

    type InsertedGoogleRouteLegId = Int

    def create(routeId: Int, leg: GoogleRoute.Leg): GoogleRouteLegItem = GoogleRouteLegItem(
      routeId = routeId,
      distance = leg.distance.value,
      distanceText = leg.distance.text,
      duration = leg.duration.value,
      durationText = leg.duration.text,
      durationInTraffic = leg.durationInTraffic.map(_.value),
      durationInTrafficText = leg.durationInTraffic.map(_.text),
      endAddress = leg.endAddress,
      endLocation = makeGeometryPoint(leg.endLocation),
      startAddress = leg.startAddress,
      startLocation = makeGeometryPoint(leg.startLocation),
      steps = makeGeometryLinestring(
        // Take head.startLocation as first point,
        // take all other endLocations as next points (including head).
        Seq(leg.steps.head.startLocation) ++ leg.steps.map(_.endLocation)
      )
    )

    implicit val psMapping: PSMapping[Update.GoogleRouteLegItem] =
      (item: Update.GoogleRouteLegItem, ps: PreparedStatement) => {
        ps.setInt(1, item.routeId)
        ps.setInt(2, item.distance)
        ps.setString(3, item.distanceText)
        ps.setInt(4, item.duration)
        ps.setString(5, item.durationText)
        item.durationInTraffic match {
          case Some(value) => ps.setInt(6, value)
          case None => ps.setNull(6, Types.INTEGER)
        }
        item.durationInTrafficText match {
          case Some(text) => ps.setString(7, text)
          case None => ps.setNull(7, Types.VARCHAR)
        }
        ps.setString(8, item.endAddress)
        ps.setString(9, item.endLocation)
        item.startAddress match {
          case Some(value) => ps.setString(10, value)
          case None => ps.setNull(10, Types.VARCHAR)
        }
        ps.setString(11, item.startLocation)
        ps.setString(12, item.steps)
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
