create extension postgis;
create extension postgis_topology;

create table taz_info (
    geo_id text primary key,
    state_fp text,
    county_fp text,
    tract_code text,
    land_area bigint,
    water_area bigint,
    geometry geometry (Geometry, 4326)
);

create index taz_info_geometry_index on taz_info using gist (geometry);
create index taz_info_geo_id_index on taz_info using btree (geo_id);
create index taz_info_county_fp_index on taz_info using btree (county_fp);
create index taz_info_state_fp_index on taz_info using btree (state_fp);
