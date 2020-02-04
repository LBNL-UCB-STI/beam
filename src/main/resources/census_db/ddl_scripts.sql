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
create index geo_id_index on data using btree (geo_id);
create index county_fp_index on data using btree (county_fp);
create index state_fp_index on data using btree (state_fp);
