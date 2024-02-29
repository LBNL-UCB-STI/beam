import os

import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import pyarrow.csv as pv

plt.style.use('ggplot')
meter_to_mile = 0.000621371
mps_to_mph = 2.23694
second_to_hours = 1 / 3600.0


def calculate_metrics(group):
    volume = group['volume']
    vmt = group.loc[:, 'linkLength'] * volume * meter_to_mile
    vht = group.loc[:, 'traveltime'] * volume * second_to_hours

    # Calculate mean volume and vmt, and sum vmt and vht
    avg_volume = np.mean(volume)
    avg_vmt = np.mean(vmt)
    sum_vmt = np.sum(vmt)
    sum_vht = np.sum(vht)
    link_speed = sum_vmt / sum_vht if sum_vht != 0 else np.nan

    # Return a Series with all calculated metrics
    return pd.Series({
        'volume': avg_volume,
        'vmt': avg_vmt,
        'speed': link_speed
    })


def read_link_stats(link_stats_file_paths, link_stats_scenario_labels):
    dfs = []
    for file_path, scenario in zip(link_stats_file_paths, link_stats_scenario_labels):
        df = pd.read_csv(file_path)
        df['scenario'] = scenario
        dfs.append(df)
    return dfs


def agg_npmrds_to_hourly_link_speeds(npmrds_data, model_network_tmcs_maybe):
    npmrds_data = npmrds_data.copy()
    if len(model_network_tmcs_maybe) > 0:
        npmrds_data = npmrds_data.loc[npmrds_data['tmc_code'].isin(model_network_tmcs_maybe)]
    npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(npmrds_data.loc[:, 'measurement_tstamp'],
                                                          format="%Y-%m-%d %H:%M:%S")
    npmrds_data.loc[:, 'weekday'] = npmrds_data.loc[:, 'formatted_time'].dt.weekday
    npmrds_data.loc[:, 'hour'] = npmrds_data.loc[:, 'formatted_time'].dt.hour
    npmrds_data = npmrds_data[(npmrds_data['hour'] >= 0) & (npmrds_data['hour'] < 24)]

    # 0: Monday => 6: Sunday
    npmrds_data = npmrds_data.loc[npmrds_data['weekday'] < 5]

    npmrds_data_hourly = npmrds_data.groupby(['tmc_code', 'hour', 'scenario'])[['speed']].mean()
    npmrds_data_hourly = npmrds_data_hourly.reset_index()
    npmrds_data_hourly.columns = ['tmc', 'hour', 'scenario', 'speed']
    return npmrds_data_hourly


def agg_beam_to_hourly_link_speeds(link_stats, model_network, demand_sample_fraction, assume_daylight_savings=False):
    demand_scaling = 1 / demand_sample_fraction
    model_network.loc[:, 'fromNodeId'] = model_network.loc[:, 'fromNodeId'].astype(int)
    model_network.loc[:, 'toNodeId'] = model_network.loc[:, 'toNodeId'].astype(int)
    model_24h = link_stats.copy()
    if assume_daylight_savings:
        model_24h.loc[:, 'hour'] = model_24h.loc[:, 'hour'] - 1
    model_24h = link_stats[(link_stats['hour'] >= 0) & (link_stats['hour'] < 24)].copy()

    model_24h = pd.merge(model_24h, model_network, left_on=['link', 'from', 'to'],
                         right_on=['linkId', 'fromNodeId', 'toNodeId'], how='inner')
    model_24h.rename(columns={'Tmc': 'tmc', 'scenario_x': 'scenario'}, inplace=True)

    # NOW Volume does not contain Trucks, but in the future Trucks will be included in Volume.
    model_24h.loc[:, 'volume'] = model_24h.loc[:, 'volume'] * demand_scaling
    if 'TruckVolume' in model_24h.columns:
        model_24h.loc[:, 'volume'] = model_24h.loc[:, 'volume'] + (model_24h.loc[:, 'TruckVolume'] * demand_scaling)

    model_hourly_link_stats = model_24h.groupby(['tmc', 'hour', 'scenario']).apply(calculate_metrics).reset_index()
    return model_hourly_link_stats


def agg_hourly_link_stats_by_road_class(npmrds_data_hourly_speed, model_hourly_link_stats, model_network):
    model_network = model_network.drop_duplicates(subset=['linkId'])
    modeled_road_type_lookup = {'tertiary': 'Minor collector',
                                'trunk_link': 'Freeway and major arterial',
                                'residential': 'Local',
                                'track': 'Local',
                                'footway': 'Local',
                                'motorway': 'Freeway and major arterial',
                                'secondary': 'Major collector',
                                'unclassified': 'Local',
                                'path': 'Local',
                                'secondary_link': 'Major collector',
                                'primary': 'Minor arterial',
                                'motorway_link': 'Freeway and major arterial',
                                'primary_link': 'Minor arterial',
                                'trunk': 'Freeway and major arterial',
                                'pedestrian': 'Local',
                                'tertiary_link': 'Minor collector',
                                'cycleway': 'Local',
                                np.nan: 'Local',
                                'steps': 'Local',
                                'living_street': 'Local',
                                'bus_stop': 'Local',
                                'corridor': 'Local',
                                'road': 'Local',
                                'bridleway': 'Local'}

    model_network.loc[:, 'road_class'] = model_network.loc[:, 'attributeOrigType'].map(modeled_road_type_lookup)
    tmc_county_lookup = model_network.loc[:, ['NAME', 'Tmc', 'road_class']]
    tmc_county_lookup = tmc_county_lookup.drop_duplicates(subset=['Tmc'])
    tmc_county_lookup.rename(columns={'Tmc': 'tmc'}, inplace=True)
    tmc_county_lookup.rename(columns={'NAME': 'name'}, inplace=True)

    paired_data_for_comparison = pd.merge(npmrds_data_hourly_speed, model_hourly_link_stats,
                                          on=['scenario', 'tmc', 'hour'],
                                          how='left')
    paired_data_for_comparison = pd.merge(paired_data_for_comparison, tmc_county_lookup, on=['scenario', 'tmc'],
                                          how='left')
    paired_data_for_comparison = paired_data_for_comparison.rename(
        columns={'speed_x': 'NPMRDS_speed', 'speed_y': 'BEAM_speed',
                 'vmt_x': 'NPMRDS_vmt', 'vmt_y': 'BEAM_vmt',
                 'volume_x': 'NPMRDS_volume', 'volume_y': 'BEAM_volume'})

    # pd.melt(paired_data_for_comparison, id_vars=['scenario', 'tmc', 'hour', 'road_class'],
    # value_vars=["NPMRDS_avgSpeed", "BEAM_avgSpeed"], var_name='scenario', value_name='speed (mph)')

    return paired_data_for_comparison


def map_nearest_links(df1, df2, distance_buffer, projected_crs_epsg):
    print("Mapping nearest links between two networks (within " + str(distance_buffer) + " meters)")

    # Ensure both GeoDataFrames are in an appropriate planar projection
    df1 = df1.to_crs(epsg=projected_crs_epsg)
    df2 = df2.to_crs(epsg=projected_crs_epsg)

    results = []

    # Prepare a spatial index on the second DataFrame for efficient querying
    sindex_df2 = df2.sindex
    matched_df2_indices = set()
    for index1, row1 in df1.iterrows():
        # Find the indices of the geometries in df2 that are within max_distance meters of the current geometry in df1
        possible_matches_index = list(sindex_df2.query(row1.geometry.buffer(distance_buffer), predicate="intersects"))
        possible_matches_index_filtered = [idx for idx in possible_matches_index if idx not in matched_df2_indices]
        if len(possible_matches_index_filtered) == 0:
            continue
        possible_matches = df2.iloc[possible_matches_index_filtered]

        # Calculate and filter distances
        distances = possible_matches.distance(row1.geometry)
        close_matches = distances[distances <= distance_buffer]

        if not close_matches.empty:
            min_dist_index = close_matches.idxmin()
            results.append({
                'df1_index': index1,
                'df2_index': min_dist_index,
                'distance': close_matches[min_dist_index]
            })
            matched_df2_indices.add(min_dist_index)

    # Convert results to a GeoDataFrame
    results_gdf = gpd.GeoDataFrame(pd.DataFrame(results))

    # Convert indices in results_gdf to the original indices in df1 and df2
    # results_gdf = results_gdf.set_index('df1_index').join(df1.drop(columns='geometry'), rsuffix='_df1')
    results_gdf = results_gdf.set_index('df1_index').join(df1, rsuffix='_df1')
    df2_nogeo = df2.drop(columns='geometry')
    df2_nogeo = df2_nogeo[
        ["Tmc", "AADT", "AADT_Singl", "AADT_Combi", "STATEFP", "COUNTYFP", "COUNTYNS", "RoadName", "Zip", "scenario"]]
    results_gdf = results_gdf.reset_index().set_index('df2_index').join(df2_nogeo, rsuffix='_df2')

    # Reset index to make sure we don't lose track of it
    results_gdf = results_gdf.reset_index().rename(columns={'index': 'df1_index'})

    # Assuming 'geometry' was retained from df1 during the initial creation of results_gdf
    results_gdf = gpd.GeoDataFrame(results_gdf, geometry='geometry', crs=df1.crs)
    return results_gdf


def load_or_process_regional_npmrds_station(region_boundary, npmrds_geo_file, npmrds_scenario_label,
                                            regional_npmrds_station_output_file):
    if not os.path.isfile(regional_npmrds_station_output_file):
        # Load California NPMRDS station shapefile
        print("Load NPMRDS station geographic data file")
        npmrds_station = gpd.read_file(npmrds_geo_file)
        npmrds_station_proj = npmrds_station.to_crs(epsg=4326)

        # Select TMC within region boundaries
        print("Select TMC within region boundaries")
        regional_npmrds_station_out = gpd.overlay(npmrds_station_proj, region_boundary, how='intersection')
        regional_npmrds_station_out['scenario'] = npmrds_scenario_label
        regional_npmrds_station_out.to_file(regional_npmrds_station_output_file, driver='GeoJSON')
        return regional_npmrds_station_out
    else:
        print("Load regional npmrds station")
        return gpd.read_file(regional_npmrds_station_output_file)


def load_or_process_regional_npmrds_data(npmrds_data_csv_file, regional_npmrds_station_tmcs, npmrds_scenario_label,
                                         regional_npmrds_data_output_file):
    print("Load NPMRDS observations")
    if not os.path.isfile(regional_npmrds_data_output_file):
        # Load NPMRDS observations
        print(">> raw NPMRDS file")
        table = pv.read_csv(npmrds_data_csv_file)
        npmrds_data = table.to_pandas()
        npmrds_data['scenario'] = npmrds_scenario_label

        # Select NPMRDS data in SF
        print(">> Select NPMRDS data within regional boundaries")
        regional_npmrds_data = npmrds_data[npmrds_data['tmc_code'].isin(regional_npmrds_station_tmcs)]

        # Write filtered NPMRDS data to CSV
        print(">> Write filtered NPMRDS data to CSV")
        regional_npmrds_data.to_csv(regional_npmrds_data_output_file, index=False)
        return regional_npmrds_data
    else:
        print(">> Filtered NPMRDS file")
        table = pv.read_csv(regional_npmrds_data_output_file)
        return table.to_pandas()


def filter_beam_network(beam_network, beam_network_filtered_geo_output_file):
    if not os.path.isfile(beam_network_filtered_geo_output_file):
        roadway_type = ['motorway_link', 'trunk', 'trunk_link', 'primary_link', 'motorway', 'primary', 'secondary',
                        'secondary_link']
        link_modes = ['car', 'car;bike', 'car;walk;bike']

        # Filter BEAM network to only include highway and major roads
        print("Filter BEAM network to only include highway and major roads")
        beam_network_filtered = beam_network[beam_network['attributeOrigType'].isin(roadway_type)]
        beam_network_filtered = beam_network_filtered[beam_network_filtered['linkModes'].isin(link_modes)]
        beam_network_filtered_proj = beam_network_filtered.to_crs(epsg=4326)
        beam_network_filtered_proj.to_file(beam_network_filtered_geo_output_file, driver='GeoJSON')
        return beam_network_filtered_proj
    else:
        return gpd.read_file(beam_network_filtered_geo_output_file)


def load_beam_network_to_geojson(region_boundary, beam_network, beam_network_by_county_output_geojson,
                                 projected_crs_epsg):
    from shapely.geometry import Point
    from shapely.geometry import LineString

    print("Load beam network to geojson")

    crs_epsg_str = "EPSG:" + str(projected_crs_epsg)

    beam_network_geo_planar = gpd.GeoDataFrame(
        beam_network,
        geometry=beam_network.apply(
            lambda x: LineString([Point(x.fromLocationX, x.fromLocationY), Point(x.toLocationX, x.toLocationY)]), axis=1
        ),
        crs=crs_epsg_str
    ).drop(columns=['fromLocationX', 'fromLocationY'])

    beam_network_geo = beam_network_geo_planar.to_crs(epsg=4326)

    # Perform intersection
    beam_network_geo_cut = gpd.overlay(beam_network_geo, region_boundary, how='intersection')

    # Save results
    beam_network_geo_cut.to_file(beam_network_by_county_output_geojson, driver="GeoJSON")

    return beam_network_geo_cut


def run_beam_npmrds_hourly_speed_mapping(npmrds_hourly_link_speed, beam_network, link_stats, sample_size, road_classes,
                                         assume_daylight_savings=False):
    print("Running beam npmrds hourly speed mapping")
    demand_scaling = 1 / sample_size

    network_stats = link_stats.merge(beam_network, left_on='link', right_on='linkId', how='left')
    if assume_daylight_savings:
        network_stats.loc[:, 'hour'] = network_stats.loc[:, 'hour'] - 1
    network_stats = network_stats[(network_stats['hour'] >= 0) & (network_stats['hour'] < 24)]

    network_stats_filtered = network_stats[network_stats['attributeOrigType'].isin(road_classes)]

    network_stats_filtered.loc[:, 'volume'] = network_stats_filtered.loc[:, 'volume'] * demand_scaling
    beam_hourly_speed = network_stats_filtered.groupby(['hour', 'scenario']).apply(calculate_metrics).reset_index()
    beam_hourly_speed = beam_hourly_speed.reset_index()
    beam_hourly_speed = beam_hourly_speed[['hour', 'scenario', 'speed']]

    npmrds_hourly_speed = npmrds_hourly_link_speed.groupby(['hour', 'scenario'])[['speed']].mean()
    npmrds_hourly_speed = npmrds_hourly_speed.reset_index()
    npmrds_hourly_speed.columns = ['hour', 'scenario', 'speed']

    return pd.concat([beam_hourly_speed, npmrds_hourly_speed], axis=0)


def run_beam_npmrds_hourly_speed_mapping_by_road_class(npmrds_hourly_link_speed, beam_network, link_stats, sample_size,
                                                       road_classes):
    print("Running beam npmrds hourly speed mapping")
    demand_scaling = 1 / sample_size

    network_stats = link_stats.merge(beam_network, left_on='link', right_on='linkId', how='left')
    network_stats = network_stats[(network_stats['hour'] >= 0) & (network_stats['hour'] < 24)]
    network_stats_filtered = network_stats[network_stats['attributeOrigType'].isin(road_classes)]

    network_stats_filtered.loc[:, 'volume'] = network_stats_filtered.loc[:, 'volume'] * demand_scaling
    beam_hourly_speed = network_stats_filtered.groupby(['hour']).apply(calculate_metrics).reset_index()
    beam_hourly_speed = beam_hourly_speed.reset_index()
    beam_hourly_speed = beam_hourly_speed[['hour', 'speed']]
    beam_hourly_speed['scenario'] = 'BEAM'

    npmrds_hourly_speed = npmrds_hourly_link_speed.groupby(['hour'])[['speed']].mean()
    npmrds_hourly_speed = npmrds_hourly_speed.reset_index()
    npmrds_hourly_speed.columns = ['hour', 'speed']
    npmrds_hourly_speed['scenario'] = 'NPMRDS'

    return pd.concat([beam_hourly_speed, npmrds_hourly_speed], axis=0)
