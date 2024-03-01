import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pyarrow.csv as pv
import os
import pandas as pd
from pathlib import Path
import time

plt.style.use('ggplot')
meter_to_mile = 0.000621371
mps_to_mph = 2.23694
second_to_hours = 1 / 3600.0
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


def calculate_metrics(group):
    volume = group['volume']
    vmt = group.loc[:, 'length'] * volume * meter_to_mile
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


def agg_npmrds_to_hourly_speed(npmrds_data):
    npmrds_data = npmrds_data.copy()
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


def process_and_extend_link_stats(model_network, link_stats_paths_and_labels_list, demand_sample_fraction,
                                  assume_daylight_savings):
    demand_scaling = 1 / demand_sample_fraction
    dfs = []
    for scenario, file_path in link_stats_paths_and_labels_list:
        df = pd.read_csv(file_path)

        if assume_daylight_savings:
            df.loc[:, 'hour'] = df.loc[:, 'hour'] - 1

        df['scenario'] = scenario
        model_24h = df[(df['hour'] >= 0) & (df['hour'] < 24)]

        model_24h = pd.merge(model_24h, model_network, left_on=['link'], right_on=['linkId'], how='inner')

        model_24h.loc[:, 'road_class'] = model_24h.loc[:, 'attributeOrigType'].map(modeled_road_type_lookup)

        # NOW Volume does not contain Trucks, but in the future Trucks will be included in Volume.
        model_24h.loc[:, 'volume'] = model_24h.loc[:, 'volume'] * demand_scaling
        if 'TruckVolume' in model_24h.columns:
            model_24h.loc[:, 'volume'] = model_24h.loc[:, 'volume'] + (model_24h.loc[:, 'TruckVolume'] * demand_scaling)

        model_24h_filtered = model_24h[
            ['link', 'hour', 'length', 'freespeed', 'capacity', 'volume', 'traveltime', 'numberOfLanes', 'linkModes',
             'road_class', 'scenario']]

        dfs.append(model_24h_filtered)

    return dfs


def map_nearest_links(df1, df2, projected_crs_epsg, distance_buffer):
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
    df1.loc[:, 'road_class'] = df1.loc[:, 'attributeOrigType'].map(modeled_road_type_lookup)
    df1 = df1[['linkId', 'linkLength', 'linkFreeSpeed', 'linkCapacity', 'numberOfLanes', 'linkModes', 'road_class',
               'geometry']]
    df1.rename(columns={'linkId': 'link'}, inplace=True)
    results_gdf = results_gdf.set_index('df1_index').join(df1, rsuffix='_df1')
    df2 = df2[["Tmc", "NAME", "AADT", "AADT_Singl", "AADT_Combi", "STATEFP", "COUNTYFP", "COUNTYNS", "RoadName", "Zip",
               "scenario"]]  # dropping geometry
    df2.rename(columns={'df1_index': 'index', 'Tmc': 'tmc'}, inplace=True)
    results_gdf = results_gdf.reset_index().set_index('df2_index').join(df2, rsuffix='_df2')

    # Reset index to make sure we don't lose track of it
    results_gdf = results_gdf.reset_index()
    # Assuming 'geometry' was retained from df1 during the initial creation of results_gdf
    results_gdf = gpd.GeoDataFrame(results_gdf, geometry='geometry', crs=df1.crs)
    results_gdf = results_gdf.set_crs('EPSG:' + str(projected_crs_epsg), allow_override=True)
    results_gdf = results_gdf.to_crs(epsg=4326)
    return results_gdf


def process_regional_npmrds_station(region_boundary, npmrds_geo_file, npmrds_scenario_label):
    print(">> Read NPMRDS station file")
    npmrds_station = gpd.read_file(npmrds_geo_file)
    npmrds_station_proj = npmrds_station.to_crs(epsg=4326)

    # Select TMC within region boundaries
    print(">> Select TMC within region boundaries")
    regional_npmrds_station_out = gpd.overlay(npmrds_station_proj, region_boundary, how='intersection')
    regional_npmrds_station_out['scenario'] = npmrds_scenario_label
    return regional_npmrds_station_out


def process_regional_npmrds_data(npmrds_data_csv_file, npmrds_scenario_label, regional_npmrds_station_tmcs):
    print(">> Read NPMRDS data file")
    table = pv.read_csv(npmrds_data_csv_file)
    npmrds_data = table.to_pandas()
    npmrds_data['scenario'] = npmrds_scenario_label

    # Select NPMRDS data in SF
    print(">> Select NPMRDS data within regional boundaries")
    regional_npmrds_data = npmrds_data[npmrds_data['tmc_code'].isin(regional_npmrds_station_tmcs)]
    return regional_npmrds_data


def process_beam_cars_network_into_geojson(region_boundary, beam_network, projected_crs_epsg):
    from shapely.geometry import Point
    from shapely.geometry import LineString
    crs_epsg_str = "EPSG:" + str(projected_crs_epsg)

    roadway_type = ['motorway_link', 'trunk', 'trunk_link', 'primary_link', 'motorway', 'primary', 'secondary',
                    'secondary_link']
    link_modes = ['car', 'car;bike', 'car;walk;bike']

    # Filter BEAM network to only include highway and major roads
    print("Filter BEAM network to only include highway and major roads")
    beam_network_filtered = beam_network[beam_network['attributeOrigType'].isin(roadway_type)]
    beam_network_filtered = beam_network_filtered[beam_network_filtered['linkModes'].isin(link_modes)]

    beam_network_geo_planar = gpd.GeoDataFrame(
        beam_network_filtered,
        geometry=beam_network_filtered.apply(
            lambda x: LineString([Point(x.fromLocationX, x.fromLocationY), Point(x.toLocationX, x.toLocationY)]), axis=1
        ),
        crs=crs_epsg_str
    ).drop(columns=['fromLocationX', 'fromLocationY'])

    beam_network_geo = beam_network_geo_planar.to_crs(epsg=4326)

    # Perform intersection
    beam_network_geo_cut = gpd.overlay(beam_network_geo, region_boundary, how='intersection')

    return beam_network_geo_cut


def run_beam_npmrds_hourly_speed_mapping(npmrds_hourly_link_speed, link_stats, road_classes):
    network_stats_filtered = link_stats[link_stats['road_class'].isin(road_classes)]
    beam_hourly_speed = network_stats_filtered.groupby(['hour', 'scenario']).apply(calculate_metrics)
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


class SpeedValidationSetup:
    def __init__(self, project_dir_path, npmrds_label, npmrds_geo_path, npmrds_data_csv_path, region_boundary_geo_path,
                 projected_crs_epsg, beam_network_csv_path, link_stats_paths_and_labels_list, demand_sample_size,
                 assume_daylight_saving):
        self.output_dir = project_dir_path + '/output'
        self.plots_dir = self.output_dir + '/plots'
        self.__demand_sample_size = demand_sample_size
        self.__projected_crs_epsg = projected_crs_epsg
        self.__assume_daylight_saving = assume_daylight_saving

        # Input Files
        self.__npmrds_label = npmrds_label
        self.__npmrds_geo_input = npmrds_geo_path
        self.__region_boundary_geo_input = region_boundary_geo_path
        self.__npmrds_data_csv_input = npmrds_data_csv_path
        self.__link_stats_paths_and_labels_list = link_stats_paths_and_labels_list
        self.__beam_network_csv_path = beam_network_csv_path

        # Output Files
        self.__regional_npmrds_station_output = self.output_dir + '/regional_npmrds_station.geojson'
        self.__regional_npmrds_data_output = self.output_dir + '/regional_npmrds_data.csv'
        self.__npmrds_hourly_speed_output_file = self.output_dir + '/npmrds_hourly_speeds.csv'
        self.__beam_network_geo_output = self.output_dir + '/beam_network_by_county.geojson'
        self.__beam_network_filtered_geo_output = self.output_dir + '/beam_network_filtered_car_links.geojson'
        self.__beam_npmrds_network_map_geo_output = self.output_dir + '/beam_npmrds_network_map_{distance}m.geojson'

        # Data
        self.regional_npmrds_data = None
        self.npmrds_hourly_speed = None
        self.regional_npmrds_station = None
        self.region_boundary = None
        self.link_stats_dfs = None
        self.beam_network = None
        self.beam_npmrds_network_map = None
        self.beam_network_filtered_car_links = None

    def init_npmrds_and_beam_data(self):
        st = time.time()

        # Ensure output directories exist
        Path(self.output_dir).mkdir(parents=True, exist_ok=True)
        Path(self.plots_dir).mkdir(parents=True, exist_ok=True)

        # Load regional map boundaries
        print("Load regional map boundaries")
        self.region_boundary = gpd.read_file(self.__region_boundary_geo_input).to_crs(epsg=4326)

        # Load or process regional npmrds station
        if not os.path.isfile(self.__regional_npmrds_station_output):
            print("Process NPMRDS station geographic data file")
            # Load California NPMRDS station shapefile
            self.regional_npmrds_station = process_regional_npmrds_station(self.region_boundary,
                                                                           self.__npmrds_geo_input,
                                                                           self.__npmrds_label)
            self.regional_npmrds_station.to_file(self.__regional_npmrds_station_output, driver='GeoJSON')
        else:
            print("Load regional npmrds station")
            self.regional_npmrds_station = gpd.read_file(self.__regional_npmrds_station_output)

        # Drop geometry and get unique TMC
        print("Drop geometry and get unique TMC")
        regional_npmrds_station_df = self.regional_npmrds_station.drop(columns='geometry')
        regional_npmrds_tmcs = regional_npmrds_station_df['Tmc'].unique()

        # Load NPMRDS observations
        if not os.path.isfile(self.__regional_npmrds_data_output):
            print("Process NPMRDS data")
            self.regional_npmrds_data = process_regional_npmrds_data(self.__npmrds_data_csv_input, self.__npmrds_label,
                                                                     regional_npmrds_tmcs)
            # Write filtered NPMRDS data to CSV
            self.regional_npmrds_data.to_csv(self.__regional_npmrds_data_output, index=False)
        else:
            print("Load NPMRDS file")
            table = pv.read_csv(self.__regional_npmrds_data_output)
            self.regional_npmrds_data = table.to_pandas()

        if not os.path.isfile(self.__npmrds_hourly_speed_output_file):
            print("Aggregate NPMRDS to hourly speed")
            self.npmrds_hourly_speed = agg_npmrds_to_hourly_speed(self.regional_npmrds_data)
            self.npmrds_hourly_speed.to_csv(self.__npmrds_hourly_speed_output_file, index=False)
        else:
            print("Load NPMRDS hourly speed")
            self.npmrds_hourly_speed = pd.read_csv(self.__npmrds_hourly_speed_output_file, sep=',')

        print("Get unique TMC codes with data")
        regional_npmrds_data_tmcs = self.regional_npmrds_data['tmc_code'].unique()

        print("Filter sf_npmrds_station for those TMCs")
        self.regional_npmrds_station = self.regional_npmrds_station[
            self.regional_npmrds_station['Tmc'].isin(regional_npmrds_data_tmcs)]

        print("Read BEAM link stats and network")
        self.beam_network = pd.read_csv(self.__beam_network_csv_path, sep=',')
        self.link_stats_dfs = process_and_extend_link_stats(self.beam_network, self.__link_stats_paths_and_labels_list,
                                                            1 / self.__demand_sample_size,
                                                            self.__assume_daylight_saving)

        print(f"Total execution time of prepare_npmrds_and_beam_data: {(time.time() - st) / 60.0}min")

    def prepare_data_for_hourly_average_speed_validation(self, road_category):
        # Running beam npmrds hourly speed mapping
        st = time.time()

        if self.npmrds_hourly_speed is None:
            self.init_npmrds_and_beam_data()

        print("Running beam npmrds hourly speed mapping")
        combined_data = None
        for link_stats in self.link_stats_dfs:
            beam_npmrds_hourly_speed = run_beam_npmrds_hourly_speed_mapping(self.npmrds_hourly_speed, link_stats,
                                                                            road_category)
            combined_data = pd.concat(
                [combined_data, beam_npmrds_hourly_speed.sort_values(by='scenario').reset_index()])

        print(
            f"Total execution time of prepare_data_for_hourly_average_speed_validation: {(time.time() - st) / 60.0}min")
        return combined_data

    def prepare_data_for_hourly_link_speed_validation(self, distance_buffer_m, rerun_network_matching):
        # ## Find BEAM links close to NPMRDS TMCs ##
        st = time.time()

        if self.npmrds_hourly_speed is None:
            self.init_npmrds_and_beam_data()

        # ## Load or filter beam network roadway and car link modes
        if self.beam_network_filtered_car_links is None:
            if not os.path.isfile(self.__beam_network_filtered_geo_output):
                print("Load beam network to geojson")
                self.beam_network_filtered_car_links = process_beam_cars_network_into_geojson(self.region_boundary,
                                                                                              self.beam_network,
                                                                                              self.__projected_crs_epsg)
                # Save results
                self.beam_network_filtered_car_links.to_file(self.__beam_network_filtered_geo_output, driver="GeoJSON")
            else:
                self.beam_network_filtered_car_links = gpd.read_file(self.__beam_network_filtered_geo_output)

        if self.beam_npmrds_network_map is None or rerun_network_matching:
            output_file = self.__beam_npmrds_network_map_geo_output.format(distance=distance_buffer_m)
            if rerun_network_matching or not os.path.isfile(output_file):
                print("Mapping nearest links between two networks (within " + str(distance_buffer_m) + " meters)")
                self.beam_npmrds_network_map = map_nearest_links(self.beam_network_filtered_car_links,
                                                                 self.regional_npmrds_station,
                                                                 self.__projected_crs_epsg,
                                                                 distance_buffer_m)
                self.beam_npmrds_network_map.to_file(output_file, driver='GeoJSON')
            else:
                print("Loading beam network mapped with npmrds")
                self.beam_npmrds_network_map = gpd.read_file(output_file)

        # Running beam npmrds link level hourly speed mapping
        print("Running beam npmrds link level hourly speed mapping")
        npmrds_hourly_speed_road_class = pd.merge(self.npmrds_hourly_speed,
                                                  self.beam_npmrds_network_map[['tmc', 'road_class']], on=['tmc'],
                                                  how='inner')
        combined_data = self.npmrds_hourly_speed
        combined_data_by_road_class = npmrds_hourly_speed_road_class
        for link_stats in self.link_stats_dfs:
            link_stats_tmc = pd.merge(link_stats, self.beam_npmrds_network_map[['tmc', 'link']], on=['link'],
                                      how='inner')
            link_stats_tmc.rename(columns={'Tmc': 'tmc'}, inplace=True)
            beam_npmrds_hourly_link_speed = link_stats_tmc.groupby(['tmc', 'hour', 'scenario']).apply(
                calculate_metrics).reset_index()
            beam_npmrds_hourly_link_speed_by_road_class = link_stats_tmc.groupby(
                ['tmc', 'hour', 'road_class', 'scenario']).apply(
                calculate_metrics).reset_index()

            combined_data = pd.concat([combined_data, beam_npmrds_hourly_link_speed.sort_values(by='scenario')])
            combined_data_by_road_class = pd.concat(
                [combined_data_by_road_class, beam_npmrds_hourly_link_speed_by_road_class.sort_values(by='scenario')])
        all_data = (combined_data.reset_index(), combined_data_by_road_class.reset_index())

        print(
            f"Total execution time of prepare_data_for_hourly_link_speed_validation: {(time.time() - st) / 60.0}min")
        return all_data

    def plot_npmrds_and_boundaries(self):
        fig, ax = plt.subplots()
        self.region_boundary.boundary.plot(ax=ax, color='black')
        self.regional_npmrds_station.plot(ax=ax, color='blue')
        plt.title("Region Boundaries and " + self.__npmrds_label + " Stations")
        fig.savefig(self.plots_dir + '/regional_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
        plt.clf()

    def plot_beam_and_npmrds_networks(self):
        fig, ax = plt.subplots()
        self.beam_npmrds_network_map.plot(ax=ax, color='red')
        self.regional_npmrds_station.plot(ax=ax, color='blue')
        plt.title("BEAM Network and " + self.__npmrds_label + " Stations")
        fig.savefig(self.plots_dir + '/regional_beam_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
        plt.clf()
