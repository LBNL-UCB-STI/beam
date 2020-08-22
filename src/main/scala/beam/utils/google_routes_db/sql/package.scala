package beam.utils.google_routes_db

import java.sql.{PreparedStatement, Types}
import java.time.Instant

import beam.utils.google_routes_db.{response ⇒ resp}

package object sql {

  type GeometryPoint = String
  type GeometryLinestring = String

  private val projection: Int = 4326

  def makeGeometryPoint(coord: resp.GoogleRoute.Coord): GeometryPoint =
    s"POINT(${coord.lat} ${coord.lng})"

  def makeGeometryLinestring(coords: Seq[resp.GoogleRoute.Coord]): GeometryLinestring = {
    val coordSs = coords.map { coord ⇒ s"${coord.lat} ${coord.lng}"}
    s"LINESTRING(${coordSs.mkString(",")})"
  }

  object DDL {

    val googleRouteTable: String =
      """
        |CREATE TABLE IF NOT EXISTS gr_route (
        |  id BIGSERIAL PRIMARY KEY,
        |  bound_northeast geometry(POINT),
        |  bound_southwest geometry(POINT),
        |  summary TEXT,
        |  copyrights TEXT,
        |  googleapi_responses_json_file_uri TEXT,
        |  timestamp TIMESTAMP WITH TIME ZONE NOT NULL
        |)
        |""".stripMargin

    val legTable: String =
      """
        |CREATE TABLE IF NOT EXISTS gr_route_leg (
        |  id BIGSERIAL PRIMARY KEY,
        |  route_id BIGINT NOT NULL REFERENCES gr_route(id),
        |  distance INTEGER,
        |  distance_text TEXT,
        |  duration INTEGER,
        |  duration_text TEXT,
        |  duration_in_traffic INTEGER,
        |  duration_in_traffic_text TEXT,
        |  end_address TEXT,
        |  end_location geometry(POINT),
        |  start_address TEXT,
        |  start_location geometry(POINT),
        |  steps geometry(LINESTRING)
        |)
        |""".stripMargin

  }

  object Update {

    //
    // GoogleRoute
    //

    case class GoogleRoute(
      boundNortheast: GeometryPoint,
      boundSouthwest: GeometryPoint,
      summary: String,
      copyrights: String,
      googleapiResponsesJsonFileUri: Option[String],
      timestamp: Instant
    )

    object GoogleRoute {

      def fromResp(
        googleRoute: resp.GoogleRoute,
        googleapiResponsesJsonFileUri: Option[String],
        timestamp: Instant
      ): GoogleRoute = GoogleRoute(
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
          |  bound_northeast, bound_southwest,
          |  copyrights, summary, googleapi_responses_json_file_uri, timestamp
          |) VALUES (
          |  ST_GeometryFromText(?, $projection), ST_GeometryFromText(?, $projection),
          |  ?, ?, ?, ?
          |) RETURNING id
          |""".stripMargin

      implicit val psMapping: PSMapping[Update.GoogleRoute] =
        (item: Update.GoogleRoute, ps: PreparedStatement) => {
          ps.setString(1, item.boundNortheast)
          ps.setString(2, item.boundSouthwest)
          ps.setString(3, item.copyrights)
          ps.setString(4, item.summary)
          item.googleapiResponsesJsonFileUri match {
            case Some(value) ⇒ ps.setString(5, value)
            case None ⇒ ps.setNull(5, Types.VARCHAR)
          }
          ps.setTimestamp(6, java.sql.Timestamp.from(item.timestamp))
        }
    }

    //
    // GoogleRouteLeg
    //

    case class GoogleRouteLeg(
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

    object GoogleRouteLeg {

      def fromResp(routeId: Int, leg: resp.GoogleRoute.Leg): GoogleRouteLeg = GoogleRouteLeg(
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

      implicit val psMapping: PSMapping[Update.GoogleRouteLeg] =
        (item: Update.GoogleRouteLeg, ps: PreparedStatement) => {
          ps.setInt(1, item.routeId)
          ps.setInt(2, item.distance)
          ps.setString(3, item.distanceText)
          ps.setInt(4, item.duration)
          ps.setString(5, item.durationText)
          item.durationInTraffic match {
            case Some(value) ⇒ ps.setInt(6, value)
            case None ⇒ ps.setNull(6, Types.INTEGER)
          }
          item.durationInTrafficText match {
            case Some(text) ⇒ ps.setString(7, text)
            case None ⇒ ps.setNull(7, Types.VARCHAR)
          }
          ps.setString(8, item.endAddress)
          ps.setString(9, item.endLocation)
          item.startAddress match {
            case Some(value) ⇒ ps.setString(10, value)
            case None ⇒ ps.setNull(10, Types.VARCHAR)
          }
          ps.setString(11, item.startLocation)
          ps.setString(12, item.steps)
        }
    }
  }
}
