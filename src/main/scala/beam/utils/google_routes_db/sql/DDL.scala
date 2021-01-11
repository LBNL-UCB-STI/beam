package beam.utils.google_routes_db.sql

object DDL {

  val googleRouteTable: String =
    """
      |CREATE TABLE IF NOT EXISTS gr_route (
      |  id BIGSERIAL PRIMARY KEY,
      |  request_id TEXT,
      |  departure_date_time TIMESTAMP,
      |  departure_time INTEGER,
      |  bound_northeast geometry(POINT),
      |  bound_southwest geometry(POINT),
      |  summary TEXT,
      |  copyrights TEXT,
      |  googleapi_responses_json_file_uri TEXT,
      |  timestamp TIMESTAMP WITH TIME ZONE NOT NULL
      |)
      |""".stripMargin

  val googleRouteLegTable: String =
    """
      |CREATE TABLE IF NOT EXISTS gr_route_leg (
      |  id BIGSERIAL PRIMARY KEY,
      |  route_id BIGINT NOT NULL REFERENCES gr_route(id),
      |  distance BIGINT,
      |  distance_text TEXT,
      |  duration BIGINT,
      |  duration_text TEXT,
      |  duration_in_traffic BIGINT,
      |  duration_in_traffic_text TEXT,
      |  end_address TEXT,
      |  end_location geometry(POINT),
      |  start_address TEXT,
      |  start_location geometry(POINT),
      |  steps geometry(LINESTRING)
      |)
      |""".stripMargin

  val googleRouteLegStartLocationIdx: String =
    """
      |CREATE INDEX IF NOT EXISTS gr_route_leg_start_location_idx
      |ON gr_route_leg USING GIST (start_location)
      |""".stripMargin

  val googleRouteLegEndLocationIdx: String =
    """
      |CREATE INDEX IF NOT EXISTS gr_route_leg_end_location_idx
      |ON gr_route_leg USING GIST (end_location)
      |""".stripMargin
}
