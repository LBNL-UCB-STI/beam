CREATE TABLE gr_route (
  id BIGSERIAL PRIMARY KEY,
  bound_northeast geography(POINT),
  bound_southwest geography(POINT),
  summary TEXT,
  copyrights TEXT
)
;

CREATE TABLE gr_route_leg (
  id BIGSERIAL PRIMARY KEY,
  route_id BIGINT NOT NULL REFERENCES gr_route(id),
  distance INTEGER,
  distance_text TEXT,
  duration INTEGER,
  duration_text TEXT,
  duration_in_traffic INTEGER,
  duration_in_traffic_text TEXT,
  start_address TEXT,
  end_address TEXT,
  start_location geography(POINT),
  end_location geography(POINT),
  steps geography(LINESTRING)
)
;

CREATE INDEX gr_route_leg_steps_idx ON gr_route_leg USING gist(steps)
;


--------------------
-- INSERT example --
--------------------
-- INSERT INTO gr_route(
--   bound_northeast, bound_southwest,
--   summary, copyrights
-- ) VALUES
-- ( 'POINT(40.7017123 -73.9628506)', 'POINT(40.6477365 -74.0036009)',
--   'I-278 W', 'Map data ©2020 Google' ),
-- ( 'POINT(40.6886357 -73.9609619)', 'POINT(40.6450822 -73.9746607)',
--   'Washington Ave', 'Map data ©2020 Google' )
-- ;
