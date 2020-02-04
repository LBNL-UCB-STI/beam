create extension postgis;
create extension postgis_topology;

create table data (
    geo_id text primary key,
    state_fp text,
    county_fp text,
    tract_code text,
    land_area bigint,
    water_area bigint,
    geometry geometry (Geometry, 4326)
);

create index data_geometry_index on data using gist (geometry);