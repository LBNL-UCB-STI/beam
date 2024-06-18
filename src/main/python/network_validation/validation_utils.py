import time

import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import pyarrow.csv as pv
import os

plt.style.use('ggplot')
meter_to_mile = 0.000621371
mps_to_mph = 2.23694
second_to_hours = 1 / 3600.0
fsystem_to_roadclass_lookup = {1.0: 'Interstate',
                               2.0: 'Freeways and Expressways',
                               3.0: 'Principal Arterial',
                               4.0: 'Minor Arterial',
                               5.0: 'Major Collector',
                               6.0: 'Minor Collector',
                               7.0: 'Local'}
roadclass_to_fsystem_lookup = {value: key for key, value in fsystem_to_roadclass_lookup.items()}
beam_to_roadclass_lookup = {'motorway': fsystem_to_roadclass_lookup[1.0],
                            'motorway_link': fsystem_to_roadclass_lookup[2.0],
                            'trunk': fsystem_to_roadclass_lookup[2.0],
                            'trunk_link': fsystem_to_roadclass_lookup[2.0],
                            'primary': fsystem_to_roadclass_lookup[3.0],
                            'primary_link': fsystem_to_roadclass_lookup[4.0],
                            'secondary': fsystem_to_roadclass_lookup[4.0],
                            'secondary_link': fsystem_to_roadclass_lookup[5.0],
                            'tertiary': fsystem_to_roadclass_lookup[5.0],
                            'tertiary_link': fsystem_to_roadclass_lookup[6.0],
                            'unclassified': fsystem_to_roadclass_lookup[6.0],
                            'residential': fsystem_to_roadclass_lookup[7.0],
                            'track': fsystem_to_roadclass_lookup[7.0],
                            'footway': fsystem_to_roadclass_lookup[7.0],
                            'path': fsystem_to_roadclass_lookup[7.0],
                            'pedestrian': fsystem_to_roadclass_lookup[7.0],
                            'cycleway': fsystem_to_roadclass_lookup[7.0],
                            'steps': fsystem_to_roadclass_lookup[7.0],
                            'living_street': fsystem_to_roadclass_lookup[7.0],
                            'bus_stop': fsystem_to_roadclass_lookup[7.0],
                            'corridor': fsystem_to_roadclass_lookup[7.0],
                            'road': fsystem_to_roadclass_lookup[7.0],
                            'bridleway': fsystem_to_roadclass_lookup[7.0],
                            np.nan: fsystem_to_roadclass_lookup[7.0]}
state_fips_to_code = {
    '01': 'AL', '02': 'AK', '04': 'AZ', '05': 'AR', '06': 'CA',
    '08': 'CO', '09': 'CT', '10': 'DE', '11': 'DC', '12': 'FL',
    '13': 'GA', '15': 'HI', '16': 'ID', '17': 'IL', '18': 'IN',
    '19': 'IA', '20': 'KS', '21': 'KY', '22': 'LA', '23': 'ME',
    '24': 'MD', '25': 'MA', '26': 'MI', '27': 'MN', '28': 'MS',
    '29': 'MO', '30': 'MT', '31': 'NE', '32': 'NV', '33': 'NH',
    '34': 'NJ', '35': 'NM', '36': 'NY', '37': 'NC', '38': 'ND',
    '39': 'OH', '40': 'OK', '41': 'OR', '42': 'PA', '44': 'RI',
    '45': 'SC', '46': 'SD', '47': 'TN', '48': 'TX', '49': 'UT',
    '50': 'VT', '51': 'VA', '53': 'WA', '54': 'WV', '55': 'WI',
    '56': 'WY', '60': 'AS', '66': 'GU', '69': 'MP', '72': 'PR',
    '78': 'VI'
}


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


def agg_npmrds_to_hourly_speed(npmrds_data, observed_speed_weight):
    npmrds_data = npmrds_data.copy()
    npmrds_data.loc[:, 'formatted_time'] = pd.to_datetime(npmrds_data.loc[:, 'measurement_tstamp'],
                                                          format="%Y-%m-%d %H:%M:%S")
    npmrds_data.loc[:, 'weekday'] = npmrds_data.loc[:, 'formatted_time'].dt.weekday
    npmrds_data.loc[:, 'hour'] = npmrds_data.loc[:, 'formatted_time'].dt.hour
    npmrds_data = npmrds_data[(npmrds_data['hour'] >= 0) & (npmrds_data['hour'] < 24)]

    # 0: Monday => 6: Sunday
    npmrds_data = npmrds_data.loc[npmrds_data['weekday'] < 5]
    alpha = observed_speed_weight
    beta = 1 - observed_speed_weight
    npmrds_data['w_speed'] = (npmrds_data['speed'] * alpha + npmrds_data['average_speed'] * beta) / (alpha + beta)

    npmrds_data_hourly = npmrds_data.groupby(['tmc_code', 'hour', 'scenario'])[['w_speed']].mean()
    npmrds_data_hourly = npmrds_data_hourly.reset_index()
    npmrds_data_hourly.columns = ['tmc', 'hour', 'scenario', 'speed']
    return npmrds_data_hourly


def process_and_extend_link_stats(model_network, link_stats, assume_daylight_savings):

    dfs = []
    for link_stat in link_stats:
        df = pv.read_csv(link_stat.file_path).to_pandas()
        demand_scaling = 1 / link_stat.demand_fraction

        if assume_daylight_savings:
            df.loc[:, 'hour'] = df.loc[:, 'hour'] - 1

        df['scenario'] = link_stat.scenario
        link_stats_24h = df[(df['hour'] >= 0) & (df['hour'] < 24)]
        link_stats_tmc = pd.merge(link_stats_24h, model_network[['tmc', 'link', 'road_class', 'npmrds_road_class']],
                                  on=['link'], how='inner')

        # TODO Volume does not contain Trucks, but in the future Trucks will be included in Volume.
        link_stats_tmc.loc[:, 'volume'] = link_stats_tmc.loc[:, 'volume'] * demand_scaling
        if 'TruckVolume' in link_stats_tmc.columns:
            link_stats_tmc.loc[:, 'volume'] = link_stats_tmc.loc[:, 'volume'] + (
                    link_stats_tmc.loc[:, 'TruckVolume'] * demand_scaling)
        link_stats_tmc_filtered = link_stats_tmc[
            ['link', 'hour', 'length', 'freespeed', 'capacity', 'volume', 'traveltime', 'road_class', 'tmc',
             'scenario', 'npmrds_road_class']]
        dfs.append(link_stats_tmc_filtered)
    return dfs


def map_nearest_links(df1, df2, projected_crs_epsg, distance_buffer):
    # Ensure both GeoDataFrames are in an appropriate planar projection
    df1 = df1.to_crs(epsg=projected_crs_epsg)
    df2 = df2.to_crs(epsg=projected_crs_epsg)

    results = []

    df1.loc[:, 'road_class'] = df1.loc[:, 'attributeOrigType'].map(beam_to_roadclass_lookup)
    df1.loc[:, 'F_System'] = df1.loc[:, 'road_class'].map(roadclass_to_fsystem_lookup)
    # Prepare a spatial index on the second DataFrame for efficient querying
    sindex_df2 = df2.sindex
    matched_df2_indices = set()
    for index1, row1 in df1.iterrows():
        # Find the indices of the geometries in df2 that are within max_distance meters of the current geometry in df1
        possible_matches_index = list(
            sindex_df2.query(row1.geometry.buffer(distance_buffer), predicate="intersects"))
        possible_matches_index_filtered = [idx for idx in possible_matches_index if idx not in matched_df2_indices]
        if len(possible_matches_index_filtered) == 0:
            continue
        possible_matches = df2.iloc[possible_matches_index_filtered]
        possible_matches_filtered = possible_matches[possible_matches['F_System'] == row1['F_System']]
        if len(possible_matches_filtered) == 0:
            possible_matches_filtered = possible_matches[
                (possible_matches['F_System'] == (row1['F_System'] + 1)) |
                (possible_matches['F_System'] == (row1['F_System'] - 1))
                ]
        if len(possible_matches_filtered) == 0:
            possible_matches_filtered = possible_matches[
                (possible_matches['F_System'] == (row1['F_System'] + 2)) |
                (possible_matches['F_System'] == (row1['F_System'] - 2))
                ]
        if len(possible_matches_filtered) == 0:
            possible_matches_filtered = possible_matches

        # Calculate and filter distances
        distances = possible_matches_filtered.distance(row1.geometry)
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
    df1 = df1[['linkId', 'road_class', 'geometry']]
    df1.rename(columns={'linkId': 'link'}, inplace=True)
    results_gdf = results_gdf.set_index('df1_index').join(df1, rsuffix='_df1')

    df2 = df2[["tmc", "NAME", "AADT", "AADT_Singl", "AADT_Combi", "Zip", "Miles", "GEOID", "COUNTYNS", "road_class",
               "scenario"]]  # dropping geometry
    df2.rename(columns={'df1_index': 'index', 'Zip': 'npmrds_zip', 'Miles': 'npmrds_length_mile',
                        'road_class': 'npmrds_road_class', "AADT_Singl": 'npmrds_aadt_class_4_6',
                        "AADT_Combi": 'npmrds_aadt_class_7_8', "AADT": 'npmrds_aadt', "GEOID": "npmrds_fips",
                        "COUNTYNS": "npmrds_ansi", "NAME": "npmrds_name"}, inplace=True)
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
    regional_npmrds_station_out.loc[:, 'road_class'] = regional_npmrds_station_out.loc[:, 'F_System'].map(fsystem_to_roadclass_lookup)
    regional_npmrds_station_out.rename(columns={'Tmc': 'tmc'}, inplace=True)
    return regional_npmrds_station_out


def process_regional_npmrds_data(npmrds_data_csv_file, npmrds_scenario_label, regional_npmrds_station_tmcs):
    print(">> Read NPMRDS data file")
    npmrds_data = pv.read_csv(npmrds_data_csv_file).to_pandas()
    npmrds_data['scenario'] = npmrds_scenario_label

    # Select NPMRDS data in SF
    print(">> Select NPMRDS data within regional boundaries")
    regional_npmrds_data = npmrds_data[npmrds_data['tmc_code'].isin(regional_npmrds_station_tmcs)]
    return regional_npmrds_data


def process_beam_cars_network_into_geojson(region_boundary, beam_network, projected_crs_epsg):
    from shapely.geometry import Point
    from shapely.geometry import LineString
    crs_epsg_str = "EPSG:" + str(projected_crs_epsg)
    # roadway_type = ['motorway_link', 'trunk', 'trunk_link', 'primary_link', 'motorway', 'primary', 'secondary',
    # 'secondary_link']
    # beam_network_filtered = beam_network[beam_network['attributeOrigType'].isin(roadway_type)]
    beam_network_filtered = beam_network[beam_network['linkModes'].isin(['car', 'car;bike', 'car;walk;bike'])]
    beam_network_geo_planar = gpd.GeoDataFrame(
        beam_network_filtered,
        geometry=beam_network_filtered.apply(
            lambda x: LineString([Point(x.fromLocationX, x.fromLocationY), Point(x.toLocationX, x.toLocationY)]), axis=1
        ),
        crs=crs_epsg_str
    ).drop(columns=['fromLocationX', 'fromLocationY'])
    beam_network_geo = beam_network_geo_planar.to_crs(epsg=4326)
    beam_network_geo_cut = gpd.overlay(beam_network_geo, region_boundary, how='intersection')
    return beam_network_geo_cut


def run_hourly_speed_mapping(npmrds_hourly_link_speed, link_stats):
    beam_hourly_speed = link_stats.groupby(['hour', 'scenario']).apply(calculate_metrics)
    beam_hourly_speed = beam_hourly_speed.reset_index()
    beam_hourly_speed = beam_hourly_speed[['hour', 'scenario', 'speed']]

    npmrds_hourly_speed = npmrds_hourly_link_speed.groupby(['hour', 'scenario'])[['speed']].mean()
    npmrds_hourly_speed = npmrds_hourly_speed.reset_index()
    npmrds_hourly_speed.columns = ['hour', 'scenario', 'speed']

    return pd.concat([beam_hourly_speed, npmrds_hourly_speed], axis=0)


def run_hourly_speed_mapping_by_road_class(npmrds_hourly_link_speed, link_stats):
    beam_hourly_speed = link_stats.groupby(['hour', 'scenario', 'road_class']).apply(calculate_metrics)
    beam_hourly_speed = beam_hourly_speed.reset_index()
    beam_hourly_speed = beam_hourly_speed[['hour', 'scenario', 'road_class', 'speed']]

    npmrds_hourly_link_speed = npmrds_hourly_link_speed.copy()
    npmrds_hourly_link_speed['road_class'] = "Freeway, arterial, major collector"
    npmrds_hourly_speed = npmrds_hourly_link_speed.groupby(['hour', 'scenario', 'road_class'])[['speed']].mean()
    npmrds_hourly_speed = npmrds_hourly_speed.reset_index()
    npmrds_hourly_speed.columns = ['hour', 'scenario', 'road_class', 'speed']

    return pd.concat([beam_hourly_speed, npmrds_hourly_speed], axis=0)


def download_taz_shapefile(state_fips_code, year, output_dir):
    import requests
    """
    Download TAZ shapefiles for a given state-level FIPS code.

    Parameters:
    - fips_code: String or integer representing the state-level FIPS code.
    - output_dir: Directory to save the downloaded ZIP file.
    """
    # Ensure the FIPS code is a string, padded to 2 characters
    fips_code_str = str(state_fips_code).zfill(2)

    # Construct the download URL
    base_url = f"https://www2.census.gov/geo/tiger/TIGER2010/TAZ/2010/"
    filename = f"tl_{year}_{fips_code_str}_taz10.zip"
    download_url = base_url + filename

    # Make the output directory if it doesn't exist
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # Full path for saving the file
    output_path = os.path.join(output_dir, filename)

    # Start the download
    print(f"Downloading TAZ shapefile for FIPS code {state_fips_code} from {download_url}")
    try:
        response = requests.get(download_url)
        response.raise_for_status()  # This will check for errors

        # Write the content of the response to a ZIP file
        with open(output_path, 'wb') as file:
            file.write(response.content)

        print(f"File saved to {output_path}")

    except requests.RequestException as e:
        print(f"Error downloading the file: {e}")

    return output_path


def collect_taz_boundaries(state_fips_code, year, output_dir):
    from zipfile import ZipFile
    state_geo_zip = output_dir + f"/tl_{year}_{state_fips_code}_taz10.zip"
    if not os.path.exists(state_geo_zip):
        state_geo_zip = download_taz_shapefile(state_fips_code, year, output_dir)
    """
    Read a shapefile from a ZIP archive, filter geometries by county FIPS codes,
    and write the result to a GeoJSON file.

    Parameters:
    - zip_file_path: Path to the ZIP file containing the shapefile.
    - county_fips_codes: List of county FIPS codes to filter by.
    - output_geojson_path: Path to save the filtered data as a GeoJSON file.
    """
    # Extract the shapefile from the ZIP archive
    with ZipFile(state_geo_zip, 'r') as zip_ref:
        # Extract all files to a temporary directory
        temp_dir = "temp_shp"
        zip_ref.extractall(temp_dir)

        # Find the .shp file in the extracted files
        shapefile_name = [f for f in os.listdir(temp_dir) if f.endswith('.shp')][0]
        shapefile_path = os.path.join(temp_dir, shapefile_name)

        # Read the shapefile into a GeoDataFrame
        gdf = gpd.read_file(shapefile_path)

        # Clean up the temporary directory
        for filename in os.listdir(temp_dir):
            os.remove(os.path.join(temp_dir, filename))
        os.rmdir(temp_dir)

        return gdf


def collect_geographic_boundaries(state_fips_code, county_fips_codes, year, study_area_geo_path, projected_coordinate_system, geo_level):
    from pygris import counties, block_groups

    if geo_level == 'county':
        # Define fips code for selected counties
        geo_data = counties(state=state_fips_code, year=year, cb=True, cache=True)
    elif geo_level == 'cbg':
        # Define fips code for selected counties
        geo_data = block_groups(state=state_fips_code, year=year, cb=True, cache=True)
    elif geo_level == 'taz':
        geo_data = collect_taz_boundaries(state_fips_code, year, os.path.dirname(study_area_geo_path))
    else:
        raise ValueError("Unsupported geographic level. Choose 'counties' or 'cbgs'.")

    countyfp_columns = [col for col in geo_data.columns if col.startswith('COUNTYFP')]
    mask = geo_data[countyfp_columns].apply(lambda x: x.isin(county_fips_codes)).any(axis=1)
    selected_geo = geo_data[mask]

    # def string_to_double(s):
    #     return float(s if s != "" else "0")
    #
    # # Prepare columns and mask
    # aland_columns = [col for col in selected_geo.columns if col.startswith('ALAND')]
    # awater_columns = [col for col in selected_geo.columns if col.startswith('AWATER')]
    # for col in aland_columns + awater_columns:
    #     selected_geo.loc[:, col] = selected_geo[col].apply(string_to_double)
    # mask = pd.Series([False] * len(selected_geo), index=selected_geo.index)
    #
    # for aland_col, awater_col in zip(aland_columns, awater_columns):
    #     # AWATER should not be more than three times ALAND
    #     mask |= (selected_geo[aland_col] > 0) & (selected_geo[awater_col] < 3 * selected_geo[aland_col])
    #
    # # Apply the mask to filter selected_geo
    # selected_geo = selected_geo[mask]

    base_name, extension = os.path.splitext(study_area_geo_path)

    study_area_geo_projected_path = base_name+"_epsg"+str(projected_coordinate_system)+extension
    selected_geo.to_crs(epsg=projected_coordinate_system).to_file(study_area_geo_projected_path, driver="GeoJSON")

    selected_geo_wgs84 = selected_geo.to_crs(epsg=4326)
    selected_geo_wgs84.to_file(base_name+"_wgs84"+extension, driver="GeoJSON")
    return selected_geo_wgs84


def map_cbg_to_taz(cbg_gdf, cbg_id_col, taz_gdf, taz_id_col, projected_coordinate_system, cbg_taz_map_csv):
    print(f"Mapping CBG to TAZ geometries")
    # Ensure that both GeoDataFrames are using the same coordinate reference system
    cbg_gdf = cbg_gdf.to_crs(projected_coordinate_system)[[cbg_id_col, 'geometry']]
    taz_gdf = taz_gdf.to_crs(projected_coordinate_system)[[taz_id_col, 'geometry']]

    # Perform spatial join
    # This step associates each CBG with one or more TAZs based on their geometries
    joined_gdf = gpd.sjoin(cbg_gdf, taz_gdf, how="left", predicate="intersects").reset_index(drop=True)

    # Now, we will determine which TAZ contains the majority of each CBG area
    # This requires calculating the area of intersection and comparing it with CBG total area
    # Note: This simplistic example assumes the joined_gdf contains necessary geometry intersections directly
    # In practice, you may need additional steps to calculate intersection areas precisely

    # Iterate through joined GeoDataFrame to calculate area of CBG within each TAZ
    # Then, identify the TAZ that contains the majority of the CBG
    # Placeholder for results
    mapping = []

    for cbg_id, group in joined_gdf.groupby(cbg_id_col):
        # Calculate the percentage of CBG area contained in each TAZ
        group['area_pct'] = group.apply(
            lambda row: (row.geometry.area / cbg_gdf[cbg_gdf[cbg_id_col] == cbg_id].geometry.area.iloc[0]) * 100, axis=1
        )
        # Find the TAZ with the maximum coverage area percentage
        max_coverage_taz_id = group.loc[group['area_pct'].idxmax(), taz_id_col]
        if not pd.isna(max_coverage_taz_id) and not isinstance(max_coverage_taz_id, str):
            max_coverage_taz_id = str(int(max_coverage_taz_id))
        mapping.append({cbg_id_col: cbg_id, taz_id_col: max_coverage_taz_id})

    # Convert the mapping to a DataFrame
    mapping_df = pd.DataFrame(mapping)

    # Output to CSV
    mapping_df.to_csv(cbg_taz_map_csv, index=False)
    print(f"Mapping output to {cbg_taz_map_csv}")


def prepare_npmrds_data(
        # input
        npmrds_label, npmrds_raw_geo, npmrds_raw_data_csv, npmrds_observed_speed_weight,
        region_boundary, beam_network_csv_input, projected_crs_epsg, distance_buffer_m,
        # output
        npmrds_station_geo, npmrds_data_csv, npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv,
        beam_network_car_links_geo, beam_npmrds_network_map_geo):

    if os.path.exists(npmrds_station_geo):
        print(f"Reading {npmrds_station_geo}")
        regional_npmrds_station = gpd.read_file(npmrds_station_geo)
    else:
        print("Process NPMRDS station geographic data file")
        regional_npmrds_station = process_regional_npmrds_station(region_boundary, npmrds_raw_geo, npmrds_label)
        regional_npmrds_station.to_file(npmrds_station_geo, driver='GeoJSON')

    if os.path.exists(npmrds_data_csv):
        print(f"Reading {npmrds_data_csv}")
        regional_npmrds_data = pv.read_csv(npmrds_data_csv).to_pandas()
    else:
        print("Process NPMRDS data")
        regional_npmrds_data = process_regional_npmrds_data(npmrds_raw_data_csv, npmrds_label, regional_npmrds_station['tmc'].unique())
        regional_npmrds_data.to_csv(npmrds_data_csv, index=False)

    if os.path.exists(npmrds_hourly_speed_csv):
        print(f"Reading {npmrds_hourly_speed_csv}")
        npmrds_hourly_speed = pv.read_csv(npmrds_hourly_speed_csv).to_pandas()
    else:
        print("Aggregate NPMRDS to hourly speed")
        npmrds_hourly_speed = agg_npmrds_to_hourly_speed(regional_npmrds_data, npmrds_observed_speed_weight)
        npmrds_hourly_speed.to_csv(npmrds_hourly_speed_csv, index=False)

    if os.path.exists(npmrds_hourly_speed_by_road_class_csv):
        print(f"Reading {npmrds_hourly_speed_by_road_class_csv}")
        npmrds_hourly_speed_road_class = pv.read_csv(npmrds_hourly_speed_by_road_class_csv).to_pandas()
    else:
        print("NPMRDS hourly speed by road class")
        df_filtered = regional_npmrds_station[['tmc', 'road_class']]
        npmrds_hourly_speed_road_class = pd.merge(npmrds_hourly_speed, df_filtered, on=['tmc'], how='inner')
        npmrds_hourly_speed_road_class.to_csv(npmrds_hourly_speed_by_road_class_csv, index=False)

    if os.path.exists(beam_network_car_links_geo):
        print(f"Reading {beam_network_car_links_geo}")
        beam_network_filtered_car_links = gpd.read_file(beam_network_car_links_geo)
    else:
        print("Filter BEAM Network and turn it into GeoJSON")
        beam_network = pv.read_csv(beam_network_csv_input).to_pandas()
        beam_network_filtered_car_links = process_beam_cars_network_into_geojson(region_boundary, beam_network,
                                                                                 projected_crs_epsg)
        beam_network_filtered_car_links.to_file(beam_network_car_links_geo, driver="GeoJSON")

    if os.path.exists(beam_npmrds_network_map_geo):
        print(f"Reading {beam_npmrds_network_map_geo}")
        beam_npmrds_network_map = gpd.read_file(beam_npmrds_network_map_geo)
    else:
        print("Building BEAM NPMRDS Network map")
        beam_npmrds_network_map = map_nearest_links(beam_network_filtered_car_links, regional_npmrds_station,
                                                    projected_crs_epsg, distance_buffer_m)
        beam_npmrds_network_map.to_file(beam_npmrds_network_map_geo, driver='GeoJSON')

    return regional_npmrds_station, regional_npmrds_data, beam_npmrds_network_map, npmrds_hourly_speed_road_class


class LinkStats:
    def __init__(self, scenario, demand_fraction, file_path):
        self.scenario = scenario
        self.demand_fraction = demand_fraction
        self.file_path = file_path


class SpeedValidationSetup:
    def __init__(self, link_stats, npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv,
                 beam_network_mapped_to_npmrds_geo, assume_daylight_saving):
        st = time.time()
        print("Loading data ...")
        self.npmrds_hourly_speed = pv.read_csv(npmrds_hourly_speed_csv).to_pandas()
        self.beam_npmrds_network_map = gpd.read_file(beam_network_mapped_to_npmrds_geo)
        self.npmrds_hourly_speed_by_road_class = pv.read_csv(npmrds_hourly_speed_by_road_class_csv).to_pandas()
        self.link_stats_tmc_dfs = process_and_extend_link_stats(self.beam_npmrds_network_map, link_stats,
                                                                assume_daylight_saving)
        print(f"Execution time of prepare_npmrds_and_beam_data: {(time.time() - st) / 60.0:.2f} minutes")

    def get_hourly_average_speed(self):
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = []

        # Process each link_stats DataFrame
        for link_stats in self.link_stats_tmc_dfs:
            hourly_speed = run_hourly_speed_mapping(self.npmrds_hourly_speed, link_stats)
            data_frames.append(hourly_speed.reset_index(drop=True))

        combined_data = pd.concat(data_frames, ignore_index=True).sort_values(
            by='scenario') if data_frames else pd.DataFrame()

        print(f"Execution time of get_hourly_average_speed: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data

    def get_hourly_average_speed_by_road_class(self):
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = [self.npmrds_hourly_speed_by_road_class]

        # Loop through Link stats DataFrames to calculate metrics and collect them
        for link_stats_tmc in self.link_stats_tmc_dfs:
            hourly_link_speed_by_road_class = link_stats_tmc.groupby(
                ['hour', 'road_class', 'scenario']).apply(calculate_metrics).reset_index()
            data_frames.append(hourly_link_speed_by_road_class)

        combined_data_by_road_class = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_average_speed_by_road_class: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data_by_road_class

    def get_hourly_link_speed(self):
        # Start timing
        st = time.time()

        # Initialize a list to collect DataFrames, starting with the existing hourly speed DataFrame
        data_frames = [self.npmrds_hourly_speed]

        # Loop through each TMC DataFrame to calculate metrics and collect them
        for link_stats_tmc in self.link_stats_tmc_dfs:
            hourly_link_speed = link_stats_tmc.groupby(
                ['tmc', 'hour', 'scenario'], as_index=False).apply(calculate_metrics)
            data_frames.append(hourly_link_speed)

        combined_data = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_link_speed: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data

    def get_hourly_link_speed_by_road_class(self):
        # Start timing
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = [self.npmrds_hourly_speed_by_road_class]

        # Loop through TMC DataFrames to calculate metrics and collect them
        for link_stats_tmc in self.link_stats_tmc_dfs:
            hourly_link_speed_by_road_class = link_stats_tmc.groupby(
                ['tmc', 'hour', 'road_class', 'scenario']).apply(calculate_metrics).reset_index()
            data_frames.append(hourly_link_speed_by_road_class)

        combined_data_by_road_class = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_link_speed_by_road_class: {(time.time() - st) / 60.0}min")
        return combined_data_by_road_class




