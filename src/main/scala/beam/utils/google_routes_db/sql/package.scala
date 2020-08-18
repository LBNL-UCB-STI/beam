package beam.utils.google_routes_db

import java.sql.{PreparedStatement, Types}

package object sql {

  type GeometryPoint = String
  type GeometryLinestring = String

  private val projection: Int = 4326

  def makeGeometryPoint(coord: json.GoogleRoute.Coord): GeometryPoint =
    s"POINT(${coord.lat} ${coord.lng})"

  def makeGeometryLinestring(coords: Seq[json.GoogleRoute.Coord]): GeometryLinestring = {
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
        |  copyrights TEXT
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
      copyrights: String
    )

    object GoogleRoute {

      def fromJson(grJson: json.GoogleRoute): GoogleRoute = GoogleRoute(
        boundNortheast = makeGeometryPoint(grJson.bounds.northeast),
        boundSouthwest = makeGeometryPoint(grJson.bounds.southwest),
        summary = grJson.summary,
        copyrights = grJson.copyrights
      )

      val insertSql: String =
        s"""
          |INSERT INTO gr_route (
          |  bound_northeast, bound_southwest,
          |  copyrights, summary
          |) VALUES (
          |  ST_GeometryFromText(?, $projection), ST_GeometryFromText(?, $projection),
          |  ?, ?
          |) RETURNING id
          |""".stripMargin

      implicit val psMapping: PSMapping[Update.GoogleRoute] =
        (item: Update.GoogleRoute, ps: PreparedStatement) => {
          ps.setString(1, item.boundNortheast)
          ps.setString(2, item.boundSouthwest)
          ps.setString(3, item.copyrights)
          ps.setString(4, item.summary)
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

      def fromJson(routeId: Int, legJson: json.GoogleRoute.Leg): GoogleRouteLeg = GoogleRouteLeg(
        routeId = routeId,
        distance = legJson.distance.value,
        distanceText = legJson.distance.text,
        duration = legJson.duration.value,
        durationText = legJson.duration.text,
        durationInTraffic = legJson.durationInTraffic.map(_.value),
        durationInTrafficText = legJson.durationInTraffic.map(_.text),
        endAddress = legJson.endAddress,
        endLocation = makeGeometryPoint(legJson.endLocation),
        startAddress = legJson.startAddress,
        startLocation = makeGeometryPoint(legJson.startLocation),
        steps = makeGeometryLinestring(
          // Take head.startLocation as first point,
          // take all other endLocations as next points (including head).
          Seq(legJson.steps.head.startLocation) ++ legJson.steps.map(_.endLocation)
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
