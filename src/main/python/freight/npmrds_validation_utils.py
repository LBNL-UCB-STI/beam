import os

import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import pyarrow.csv as pv
import seaborn as sns

plt.style.use('ggplot')
meter_to_mile = 0.000621371
mps_to_mph = 2.23694
seconds_to_hours = 1 / 3600.0


def npmrds_screeline_validation(npmrds_data, model_network, output_dir, label, show_plots=False):
    npmrds_data = npmrds_data.loc[npmrds_data['tmc_code'].isin(model_network.loc[:, 'Tmc'].unique())]
    npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(npmrds_data.loc[:, 'measurement_tstamp'],
                                                          format="%Y-%m-%d %H:%M:%S")
    npmrds_data.loc[:, 'weekday'] = npmrds_data.loc[:, 'formatted_time'].dt.weekday
    npmrds_data.loc[:, 'hour'] = npmrds_data.loc[:, 'formatted_time'].dt.hour % 24

    # 0: Monday => 6: Sunday
    npmrds_data = npmrds_data.loc[npmrds_data['weekday'] < 5]

    npmrds_data_hourly = npmrds_data.groupby(['tmc_code', 'hour'])[['speed']].mean()
    npmrds_data_hourly = npmrds_data_hourly.reset_index()
    npmrds_data_hourly.columns = ['tmc', 'hour', 'avgSpeed']

    fig, ax = plt.subplots()
    sns.lineplot(data=npmrds_data_hourly, x="hour", y="avgSpeed", errorbar=('ci', 95), ax=ax)
    plt.ylim([0, 70])
    plt.ylabel('Average Speed (mph)')
    plt.savefig(output_dir + '/plots/NPMRDS_hourly_mean_speed.png', bbox_inches='tight', dpi=300)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    npmrds_data_hourly.loc[:, 'scenario'] = label
    return npmrds_data_hourly


def build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario, demand_sample_fraction,
                            show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = modeled_vmt.copy()

    if assume_daylight_savings:
        model_vmt_24_hour.loc[:, 'hour'] = (model_vmt_24_hour.loc[:, 'hour'] - 1) % 24
    else:
        model_vmt_24_hour.loc[:, 'hour'] = model_vmt_24_hour.loc[:, 'hour'] % 24

    model_network.loc[:, 'fromNodeId'] = model_network.loc[:, 'fromNodeId'].astype(int)
    model_network.loc[:, 'toNodeId'] = model_network.loc[:, 'toNodeId'].astype(int)
    model_vmt_24_hour = pd.merge(model_vmt_24_hour, model_network, left_on=['link', 'from', 'to'],
                                 right_on=['linkId', 'fromNodeId', 'toNodeId'], how='inner')
    model_vmt_24_hour.rename(columns={'Tmc': 'tmc'}, inplace=True)

    demand_scaling = 1 / demand_sample_fraction

    # NOW Volume does not contain Trucks, but in the future Trucks will be included in Volume.
    model_vmt_24_hour.loc[:, 'volume'] = model_vmt_24_hour.loc[:, 'volume'] * demand_scaling
    if 'TruckVolume' in model_vmt_24_hour.columns:
        model_vmt_24_hour.loc[:, 'volume'] = model_vmt_24_hour.loc[:, 'volume'] + (
                model_vmt_24_hour.loc[:, 'TruckVolume'] * demand_scaling)

    model_vmt_24_hour.loc[:, 'vmt'] = meter_to_mile * model_vmt_24_hour.loc[:, 'linkLength'] * model_vmt_24_hour.loc[:,
                                                                                               'volume']
    model_vmt_24_hour.loc[:, 'vht'] = seconds_to_hours * model_vmt_24_hour.loc[:, 'traveltime'] * model_vmt_24_hour.loc[
                                                                                                  :, 'volume']

    model_vmt_24_hour.loc[:, 'speed'] = model_vmt_24_hour.loc[:, 'vmt'] / model_vmt_24_hour.loc[:, 'vht']

    fig, ax = plt.subplots()
    model_vmt_24_hour["speed"].plot(kind="hist", bins=30, ax=ax)
    plt.xlabel('Hourly speed (mph)')
    plt.savefig(output_dir + '/plots/modeled_speed_distribution.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    model_vmt_24_hour.loc[:, 'scenario'] = scenario
    return model_vmt_24_hour


def beam_screeline_validation(modeled_vmt, model_network, output_dir, scenario, demand_sample_fraction,
                              show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario,
                                                demand_sample_fraction, show_plots, assume_daylight_savings)

    model_vmt_hour_volume = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour'])[['volume', 'vmt']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['scenario', 'tmc', 'hour', 'avgVolume', 'avgVmt']

    model_vmt_hour_speed = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour']).apply(
        lambda x: np.sum(x['vmt']) / np.sum(x['vht']) if np.sum(x['vht']) != 0 else np.nan
    )

    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['scenario', 'tmc', 'hour', 'avgSpeed']

    beam_data_hourly = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on=['scenario', 'tmc', 'hour'], how='left')

    fig, ax = plt.subplots()
    sns.lineplot(x='hour', y='avgSpeed', data=beam_data_hourly, errorbar=('ci', 95), ax=ax)
    # plt.ylim([0, 70])
    plt.savefig(output_dir + '/plots/modeled_speed_NPMRDS_screenline.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    sns.lineplot(x='hour', y='avgVolume', data=beam_data_hourly, errorbar=('ci', 95))
    # plt.ylim([0, 70])
    # plt.ylabel('volume (veh/lane/hour)')
    plt.savefig(output_dir + '/plots/modeled_volume_NPMRDS_screenline.png', dpi=200)
    if show_plots:
        plt.show()
    else:
        plt.clf()

    return beam_data_hourly


def beam_screeline_validation_per_road_class(npmrds_data_hourly_speed, modeled_vmt, model_network, output_dir, scenario,
                                             demand_sample_fraction, show_plots=False, assume_daylight_savings=False):
    model_vmt_24_hour = build_model_vmt_24_hour(modeled_vmt, model_network, output_dir, scenario,
                                                demand_sample_fraction, show_plots, assume_daylight_savings)

    model_vmt_hour_volume = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour'])[['avgVolume', 'vmt']].mean()
    model_vmt_hour_volume = model_vmt_hour_volume.reset_index()
    model_vmt_hour_volume.columns = ['scenario', 'tmc', 'hour', 'avgVolume', 'vmt']

    model_vmt_hour_speed = model_vmt_24_hour.groupby(['scenario', 'tmc', 'hour']).apply(
        lambda x: np.sum(x['vmt']) / np.sum(x['vht']) if np.sum(x['vht']) != 0 else np.nan
    )
    model_vmt_hour_speed = model_vmt_hour_speed.reset_index()
    model_vmt_hour_speed.columns = ['scenario', 'tmc', 'hour', 'avgSpeed']

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

    model_vmt_hour_data = pd.merge(model_vmt_hour_volume, model_vmt_hour_speed, on=['scenario', 'tmc', 'hour'],
                                   how='left')
    paired_data_for_comparison = pd.merge(npmrds_data_hourly_speed, model_vmt_hour_data, on=['scenario', 'tmc', 'hour'],
                                          how='left')
    paired_data_for_comparison = pd.merge(paired_data_for_comparison, tmc_county_lookup, on=['scenario', 'tmc'],
                                          how='left')
    paired_data_for_comparison = paired_data_for_comparison.rename(
        columns={'avgSpeed_x': 'NPMRDS_avgSpeed', 'avgSpeed_y': 'BEAM_avgSpeed'})
    paired_data_for_comparison = paired_data_for_comparison.dropna(subset=['BEAM_avgSpeed'])

    pd.melt(paired_data_for_comparison, id_vars=['scenario', 'tmc', 'hour', 'road_class'],
            value_vars=["NPMRDS_avgSpeed", "BEAM_avgSpeed"], var_name='scenario', value_name='speed (mph)')

    vmt_by_hour = paired_data_for_comparison.groupby(['scenario', 'hour', 'road_class'])[['vmt']].sum()
    vmt_by_hour = vmt_by_hour.reset_index()

    return vmt_by_hour


def map_nearest_links(df1, df2, distance_buffer, projected_crs_epsg):
    print("Mapping nearest links between two networks (within " + str(distance_buffer) + " meters)")

    # Ensure both GeoDataFrames are in an appropriate planar projection
    df1 = df1.to_crs(epsg=projected_crs_epsg)
    df2 = df2.to_crs(epsg=projected_crs_epsg)

    results = []

    # Prepare a spatial index on the second DataFrame for efficient querying
    sindex_df2 = df2.sindex

    for index1, row1 in df1.iterrows():
        # Find the indices of the geometries in df2 that are within max_distance meters of the current geometry in df1
        possible_matches_index = list(sindex_df2.query(row1.geometry.buffer(distance_buffer), predicate="intersects"))
        possible_matches = df2.iloc[possible_matches_index]

        # Calculate and filter distances
        distances = possible_matches.distance(row1.geometry)
        close_matches = distances[distances <= distance_buffer]

        for index2, dist in close_matches.items():
            results.append({
                'df1_index': index1,
                'df2_index': index2,
                'distance': dist,
                # Add additional fields as needed
            })

    # Convert results to a GeoDataFrame
    results_gdf = gpd.GeoDataFrame(pd.DataFrame(results))
    # Optionally join additional attributes from df1 and df2

    # Convert indices in results_gdf to the original indices in df1 and df2
    # results_gdf = results_gdf.set_index('df1_index').join(df1.drop(columns='geometry'), rsuffix='_df1')
    results_gdf = results_gdf.set_index('df1_index').join(df1, rsuffix='_df1')
    df2_nogeo = df2.drop(columns='geometry')
    df2_nogeo = df2_nogeo[
        ["Tmc", "AADT", "AADT_Singl", "AADT_Combi", "STATEFP", "COUNTYFP", "COUNTYNS", "RoadName", "Zip"]]
    results_gdf = results_gdf.reset_index().set_index('df2_index').join(df2_nogeo, rsuffix='_df2')

    # Reset index to make sure we don't lose track of it
    results_gdf = results_gdf.reset_index().rename(columns={'index': 'df1_index'})

    # Assuming 'geometry' was retained from df1 during the initial creation of results_gdf
    results_gdf = gpd.GeoDataFrame(results_gdf, geometry='geometry', crs=df1.crs)
    return results_gdf


def load_or_process_regional_npmrds_station(region_boundary, npmrds_geo_file, regional_npmrds_station_output_file):
    if not os.path.isfile(regional_npmrds_station_output_file):
        # Load California NPMRDS station shapefile
        print("Load NPMRDS station geographic data file")
        npmrds_station = gpd.read_file(npmrds_geo_file)
        npmrds_station_proj = npmrds_station.to_crs(epsg=4326)

        # Select TMC within region boundaries
        print("Select TMC within region boundaries")
        regional_npmrds_station_out = gpd.overlay(npmrds_station_proj, region_boundary, how='intersection')
        regional_npmrds_station_out.to_file(regional_npmrds_station_output_file, driver='GeoJSON')
        return regional_npmrds_station_out
    else:
        print("Load regional npmrds station")
        return gpd.read_file(regional_npmrds_station_output_file)


def load_or_process_regional_npmrds_data(npmrds_data_csv_file, regional_npmrds_station_tmcs,
                                         regional_npmrds_data_output_file):
    print("Load NPMRDS observations")
    if not os.path.isfile(regional_npmrds_data_output_file):
        # Load NPMRDS observations
        print(">> raw NPMRDS file")
        table = pv.read_csv(npmrds_data_csv_file)
        npmrds_data = table.to_pandas()

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

    # Convert DataFrame to GeoDataFrame for origin nodes
    gdf_onode = gpd.GeoDataFrame(
        beam_network,
        geometry=[Point(xy) for xy in zip(beam_network.fromLocationX, beam_network.fromLocationY)],
        crs=crs_epsg_str
    ).drop(columns=['fromLocationX', 'fromLocationY'])

    # Convert DataFrame to GeoDataFrame for destination nodes
    gdf_dnode = gpd.GeoDataFrame(
        beam_network,
        geometry=[Point(xy) for xy in zip(beam_network.toLocationX, beam_network.toLocationY)],
        crs=crs_epsg_str
    ).drop(columns=['toLocationX', 'toLocationY'])

    # Combine origin and destination nodes back into a single frame
    gdf_combined = pd.concat([gdf_onode, gdf_dnode]).groupby('linkId', as_index=False).agg({
        'linkLength': 'mean',
        'linkFreeSpeed': 'mean',
        'linkCapacity': 'mean',
        'numberOfLanes': 'mean',
        'linkModes': 'first',
        'attributeOrigId': 'first',
        'attributeOrigType': 'first',
        'fromNodeId': 'first',
        'toNodeId': 'first',
        'geometry': lambda x: list(x)
    })

    # gdf_combined.reset_index(drop=True, inplace=True)

    # Convert points to LineString
    gdf_combined['geometry'] = gdf_combined['geometry'].apply(lambda x: LineString(x[:2]))

    # Convert back to GeoDataFrame
    line_gdf = gpd.GeoDataFrame(gdf_combined, geometry='geometry', crs=crs_epsg_str)

    line_gdf_transformed = line_gdf.to_crs(epsg=4326)
    # line_gdf_transformed.to_file(beam_network_output_geojson, driver="GeoJSON")

    # Perform intersection
    beam_network_splits = gpd.overlay(line_gdf_transformed, region_boundary, how='intersection')

    # Filter by linkLength > 0.001 (assuming linkLength is in kilometers if CRS is EPSG:4326)
    beam_network_splits_filtered = beam_network_splits[beam_network_splits['linkLength'] > 0.001]

    # Save results
    beam_network_splits_filtered.to_file(beam_network_by_county_output_geojson, driver="GeoJSON")
    # beam_network_splits_filtered.drop(columns=['geometry']).to_csv(f"{beam_network_by_county_output_geojson}.csv")

    return beam_network_splits_filtered


def run_beam_npmrds_hourly_speed_mapping(regional_npmrds_data, beam_network, link_stats, demand_sample_size):
    print("Running beam npmrds hourly speed mapping")
    demand_scaling = 1 / demand_sample_size

    network_stats = link_stats.merge(beam_network, left_on='link', right_on='linkId', how='left')
    network_stats.loc[:, 'hour'] = network_stats.loc[:, 'hour'] % 24
    network_stats.loc[:, 'volume'] = network_stats.loc[:, 'volume'] * demand_scaling
    network_stats.loc[:, 'vmt'] = meter_to_mile * network_stats.loc[:, 'linkLength'] * network_stats.loc[:, 'volume']
    network_stats.loc[:, 'vht'] = seconds_to_hours * network_stats.loc[:, 'traveltime'] * network_stats.loc[:, 'volume']

    road_classes = ['motorway', 'motorway_link']
    link_stats_filtered = network_stats[network_stats['attributeOrigType'].isin(road_classes)]

    beam_hourly_speed = link_stats_filtered.groupby(['hour']).apply(
        lambda x: np.sum(x['vmt']) / np.sum(x['vht']) if np.sum(x['vht']) != 0 else np.nan
    )
    beam_hourly_speed = beam_hourly_speed.reset_index()
    beam_hourly_speed.columns = ['hour', 'speed']
    beam_hourly_speed['scenario'] = 'BEAM'

    regional_npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(regional_npmrds_data.loc[:, 'measurement_tstamp'],
                                                                   format="%Y-%m-%d %H:%M:%S")
    regional_npmrds_data.loc[:, 'weekday'] = regional_npmrds_data.loc[:, 'formatted_time'].dt.weekday
    regional_npmrds_data.loc[:, 'hour'] = regional_npmrds_data.loc[:, 'formatted_time'].dt.hour % 24
    # 0: Monday => 6: Sunday
    npmrds_data = regional_npmrds_data.loc[regional_npmrds_data['weekday'] < 5]
    npmrds_hourly_speed = npmrds_data.groupby(['hour'])[['speed']].mean()
    npmrds_hourly_speed = npmrds_hourly_speed.reset_index()
    npmrds_hourly_speed['scenario'] = 'NPMRDS'

    return pd.concat([beam_hourly_speed, npmrds_hourly_speed], axis=0)
