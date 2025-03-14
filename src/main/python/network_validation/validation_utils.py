import json
import os
import time
import zipfile
from urllib.request import urlretrieve

import contextily as ctx
import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import osmnx as ox
import pandas as pd
import pyarrow.csv as pv
import requests
import seaborn as sns

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
                            'living_street': fsystem_to_roadclass_lookup[7.0],
                            'road': fsystem_to_roadclass_lookup[7.0],
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
    df1 = df1[['linkId', 'linkFreeSpeed', 'road_class', 'geometry']]
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
    regional_npmrds_station_out.loc[:, 'road_class'] = regional_npmrds_station_out.loc[:, 'F_System'].map(
        fsystem_to_roadclass_lookup)
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


def download_nhts_data(nhts_output_file, area_name, state_fips_code=None,
                       cbsa_codes=None, year=2017, download=True, extract=True, process=True):
    """
    Download, extract, and process NHTS data with filtering by state FIPS code
    and/or CBSA codes.
    Stores filtered data under directory with area name: data_nhts_dir/area_name/

    Parameters:
    - nhts_output_file: Path to save the downloaded NHTS zip file
    - area_name: Name of the area for organizing filtered data
    - state_fips_code: String representing the state FIPS code (e.g., '06' for California)
    - cbsa_codes: List of CBSA codes (e.g., [41860] for San Francisco-Oakland-Hayward, CA)
    - year: NHTS survey year (default: 2017)
    - download: Boolean to control if download should occur
    - extract: Boolean to control if extraction should occur
    - process: Boolean to control if processing should occur

    Returns:
    - Dictionary of filtered DataFrames
    """
    # Set URL based on year
    if year >= 2016:
        url = "https://nhts.ornl.gov/assets/2016/download/csv.zip"
    else:
        print(f"Error: NHTS data for year {year} is not supported.")
        return None

    data_nhts_dir = os.path.dirname(nhts_output_file)

    # Create area-specific directory
    area_dir = os.path.join(data_nhts_dir, area_name)
    os.makedirs(area_dir, exist_ok=True)
    print(f"Created directory for area: {area_dir}")

    # Create a filter description for file naming
    filter_desc = ""
    if state_fips_code:
        filter_desc += f"fips_{state_fips_code}"
    if cbsa_codes:
        filter_desc += f"_cbsa_{'_'.join(map(str, cbsa_codes))}"

    # Save filter information to a JSON file for reference
    filter_info = {
        "area_name": area_name,
        "state_fips_code": state_fips_code,
        "cbsa_codes": cbsa_codes,
        "year": year,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
    }

    with open(os.path.join(area_dir, "filter_info.json"), "w") as f:
        json.dump(filter_info, f, indent=2)

    # Check if the file already exists
    if os.path.exists(nhts_output_file):
        file_size = os.path.getsize(nhts_output_file) / (1024 * 1024)  # Size in MB
        print(f"File {nhts_output_file} already exists ({file_size:.1f} MB). Skipping download.")
    else:
        print(f"Downloading NHTS {year} data...")
        # Download the file with progress reporting
        response = requests.get(url, stream=True)
        if response.status_code == 200:
            total_size = int(response.headers.get('content-length', 0))
            downloaded = 0
            start_time = time.time()

            with open(nhts_output_file, "wb") as file:
                for chunk in response.iter_content(chunk_size=1024 * 1024):  # 1MB chunks
                    if chunk:
                        file.write(chunk)
                        downloaded += len(chunk)

                        # Calculate and display progress
                        percent = int(100 * downloaded / total_size) if total_size > 0 else 0
                        elapsed = time.time() - start_time
                        rate = downloaded / (1024 * 1024 * elapsed) if elapsed > 0 else 0

                        print(
                            f"\rDownloading: {percent}% ({downloaded / (1024 * 1024):.1f}MB of {total_size / (1024 * 1024):.1f}MB) at {rate:.1f} MB/s",
                            end="")

            print(f"\nDownloaded {nhts_output_file}")
        else:
            print(f"Failed to download. Status code: {response.status_code}")
            print(f"Response: {response.text[:500]}...")
            return None

    # Create a temporary directory for extraction
    temp_extract_dir = os.path.join(data_nhts_dir, "temp_extract")
    os.makedirs(temp_extract_dir, exist_ok=True)

    # Check if data has already been extracted to temp directory
    extracted_files_exist = os.path.exists(f"{temp_extract_dir}/hhpub.csv") or os.path.exists(
        f"{temp_extract_dir}/trippub.csv")

    if not extracted_files_exist and extract:
        # Extract the downloaded ZIP file to temp directory
        print("\nExtracting files to temporary directory...")
        try:
            with zipfile.ZipFile(nhts_output_file, "r") as zip_ref:
                zip_ref.extractall(temp_extract_dir)
            print("Files extracted successfully")
        except zipfile.BadZipFile:
            print("Error: The downloaded file is not a valid ZIP file.")
            print("The file may be corrupted. Please try downloading again.")
            return None
        except Exception as e:
            print(f"Error extracting files: {str(e)}")
            return None
    elif extract:
        extract_again = input("Data files already exist in temp directory. Extract again? (y/n): ").lower() == 'y'
        if extract_again:
            print("\nExtracting files to temporary directory...")
            try:
                with zipfile.ZipFile(nhts_output_file, "r") as zip_ref:
                    zip_ref.extractall(temp_extract_dir)
                print("Files extracted successfully")
            except Exception as e:
                print(f"Error extracting files: {str(e)}")
                return None
        else:
            print("Skipping extraction.")
    else:
        print("Skipping extraction.")

    # List the extracted files
    files = os.listdir(temp_extract_dir)
    print(f"\nFiles in temporary extraction directory: {len(files)} files")

    # Process key datasets with focus on filtered areas
    datasets = {
        "Households": "hhpub.csv",
        "Persons": "perpub.csv",
        "Trips": "trippub.csv",
        "Vehicles": "vehpub.csv"
    }

    filtered_dfs = {}

    if not process:
        print("Skipping data processing as requested.")
        return None

    for dataset_name, filename in datasets.items():
        # Define output path in the area-specific directory
        area_output_file = os.path.join(area_dir, filename)

        # Check if filtered file already exists in area directory
        if os.path.exists(area_output_file):
            process_this = input(
                f"Filtered {dataset_name} data already exists in {area_name} directory. Process again? (y/n): ").lower() == 'y'
            if not process_this:
                filtered_dfs[dataset_name] = pd.read_csv(area_output_file)
                print(f"Loaded existing filtered {dataset_name} data from {area_name} directory.")
                continue

        if filename in files:
            print(f"\nProcessing {dataset_name} dataset...")
            file_path = os.path.join(temp_extract_dir, filename)

            # Load the CSV file
            df = pd.read_csv(file_path)
            print(f"Total records: {len(df)}")

            # Apply filters
            filtered_df = df.copy()

            # Find columns for filtering
            # 1. Find any column containing the word "FIPS" for state FIPS
            state_fips_column = None
            state_fips_columns = [col for col in df.columns if 'STFIPS' in col or ('FIPS' in col and 'ST' in col)]

            if state_fips_columns:
                state_fips_column = state_fips_columns[0]
                print(f"Found state FIPS column: {state_fips_column}")

            # 2. Find any column containing CBSA
            cbsa_column = None
            cbsa_columns = [col for col in df.columns if 'CBSA' in col]

            if cbsa_columns:
                cbsa_column = cbsa_columns[0]
                print(f"Found CBSA column: {cbsa_column}")

            # Apply filtering based on available columns and parameters
            filter_applied = False

            # 1. Filter by CBSA if provided and column exists
            if cbsa_codes and cbsa_column and cbsa_column in df.columns:
                filtered_df = filtered_df[filtered_df[cbsa_column].isin(cbsa_codes)]
                print(f"Records after CBSA filter: {len(filtered_df)}")
                filter_applied = True

            # 2. Filter by state FIPS if provided and column exists
            if state_fips_code and state_fips_column and state_fips_column in df.columns:
                # Convert to integer for comparison if the column is numeric
                if pd.api.types.is_numeric_dtype(filtered_df[state_fips_column]):
                    filtered_df = filtered_df[filtered_df[state_fips_column] == int(state_fips_code)]
                else:
                    # Otherwise treat as string
                    filtered_df[state_fips_column] = filtered_df[state_fips_column].astype(str)
                    filtered_df = filtered_df[filtered_df[state_fips_column] == state_fips_code]
                print(f"Records after state FIPS filter: {len(filtered_df)}")
                filter_applied = True

            if not filter_applied:
                print("Warning: No filters applied. No matching columns found for the provided filter criteria.")
                print(f"Available columns: {', '.join(df.columns[:10])}...")

            # Save filtered data to area-specific directory
            filtered_df.to_csv(area_output_file, index=False)
            print(f"Filtered data saved to {area_output_file}")

            # Store in dictionary
            filtered_dfs[dataset_name] = filtered_df

            # Display sample data
            print("\nSample data (first 3 rows):")
            print(filtered_df.head(3))

            # Display column information
            print(f"\nNumber of columns: {len(filtered_df.columns)}")
            print(f"Sample columns: {filtered_df.columns[:5].tolist()}")
        else:
            print(f"\nWarning: {filename} not found in extracted files")

    # Optionally clean up temporary extraction directory
    print("Cleaning up temporary extraction directory")
    import shutil
    shutil.rmtree(temp_extract_dir)
    print(f"Removed temporary directory: {temp_extract_dir}")
    return filtered_dfs

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
        regional_npmrds_data = process_regional_npmrds_data(npmrds_raw_data_csv, npmrds_label,
                                                            regional_npmrds_station['tmc'].unique())
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

    def __repr__(self):
        return f"LinkStats(scenario='{self.scenario}', demand_fraction='{self.demand_fraction}', file_path='{self.file_path}')"


class SpeedValidationSetup:
    def __init__(self, npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv,
                 beam_network_mapped_to_npmrds_geo):
        st = time.time()
        print("Loading data ...")
        self.npmrds_hourly_speed = pv.read_csv(npmrds_hourly_speed_csv).to_pandas()
        self.beam_npmrds_network_map = gpd.read_file(beam_network_mapped_to_npmrds_geo)
        self.npmrds_hourly_speed_by_road_class = pv.read_csv(npmrds_hourly_speed_by_road_class_csv).to_pandas()
        base_name, extension = os.path.splitext(beam_network_mapped_to_npmrds_geo)
        self.generate_link_speed_params(base_name)
        print(f"Execution time of prepare_npmrds_and_beam_data: {(time.time() - st) / 60.0:.2f} minutes")

    def process_these_link_stats(self, link_stats, assume_daylight_saving):
        return process_and_extend_link_stats(self.beam_npmrds_network_map, link_stats, assume_daylight_saving)

    def get_hourly_average_speed(self, link_stats_tmc_dfs):
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = []

        # Process each link_stats DataFrame
        for link_stats in link_stats_tmc_dfs:
            hourly_speed = run_hourly_speed_mapping(self.npmrds_hourly_speed, link_stats)
            data_frames.append(hourly_speed.reset_index(drop=True))

        combined_data = pd.concat(data_frames, ignore_index=True).sort_values(
            by='scenario') if data_frames else pd.DataFrame()

        print(f"Execution time of get_hourly_average_speed: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data

    def get_hourly_average_speed_by_road_class(self, link_stats_tmc_dfs):
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = [self.npmrds_hourly_speed_by_road_class]

        # Loop through Link stats DataFrames to calculate metrics and collect them
        for link_stats_tmc in link_stats_tmc_dfs:
            hourly_link_speed_by_road_class = link_stats_tmc.groupby(
                ['hour', 'road_class', 'scenario']).apply(calculate_metrics).reset_index()
            data_frames.append(hourly_link_speed_by_road_class)

        combined_data_by_road_class = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_average_speed_by_road_class: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data_by_road_class

    def get_hourly_link_speed(self, link_stats_tmc_dfs):
        # Start timing
        st = time.time()

        # Initialize a list to collect DataFrames, starting with the existing hourly speed DataFrame
        data_frames = [self.npmrds_hourly_speed]

        # Loop through each TMC DataFrame to calculate metrics and collect them
        for link_stats_tmc in link_stats_tmc_dfs:
            hourly_link_speed = link_stats_tmc.groupby(
                ['tmc', 'hour', 'scenario'], as_index=False).apply(calculate_metrics)
            data_frames.append(hourly_link_speed)

        combined_data = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_link_speed: {(time.time() - st) / 60.0:.2f} minutes")
        return combined_data

    def get_hourly_link_speed_by_road_class(self, link_stats_tmc_dfs):
        # Start timing
        st = time.time()

        # Initialize a list to collect DataFrames
        data_frames = [self.npmrds_hourly_speed_by_road_class]

        # Loop through TMC DataFrames to calculate metrics and collect them
        for link_stats_tmc in link_stats_tmc_dfs:
            hourly_link_speed_by_road_class = link_stats_tmc.groupby(
                ['tmc', 'hour', 'road_class', 'scenario']).apply(calculate_metrics).reset_index()
            data_frames.append(hourly_link_speed_by_road_class)

        combined_data_by_road_class = pd.concat(data_frames, ignore_index=True).sort_values(by='scenario')

        print(f"Execution time of get_hourly_link_speed_by_road_class: {(time.time() - st) / 60.0}min")
        return combined_data_by_road_class

    # def get_average_link_speed(self):
    #     def calculate_average(group):
    #         return pd.Series({
    #             'volume_beam': np.mean(group['volume']),
    #             'vmt_beam': np.mean(group['vmt']),
    #             'speed_beam': np.mean(group['speed_beam']),
    #             'speed_npmrds': np.mean(group['speed_npmrds'])
    #         })
    #     # Start timing
    #     st = time.time()
    #
    #     # Initialize a list to collect DataFrames
    #     data_frames = []
    #
    #     # Loop through TMC DataFrames to calculate metrics and collect them
    #     for link_stats_tmc in self.link_stats_tmc_dfs:
    #         hourly_link_speed_by_road_class = link_stats_tmc.groupby(['tmc', 'link', 'hour', 'road_class', 'scenario']). \
    #             apply(calculate_metrics, include_groups=False).reset_index().merge(
    #             self.npmrds_hourly_speed_by_road_class[['tmc', 'speed']],
    #             on='tmc', how='inner', suffixes=('_beam', '_npmrds')
    #         ).groupby(['link', 'road_class', 'scenario']).apply(calculate_average, include_groups=False).reset_index()
    #
    #         data_frames.append(hourly_link_speed_by_road_class)
    #
    #     combined_average_link_speed_by_link = pd.concat(data_frames, ignore_index=True)
    #
    #     print(f"Execution time of get_average_link_speed_by_link: {(time.time() - st) / 60.0}min")
    #     return combined_average_link_speed_by_link

    def generate_link_speed_params(self, base_path_name):
        beam_npmrds_network_map = self.beam_npmrds_network_map[['link', 'linkFreeSpeed', 'tmc', 'road_class']]
        mean_speed_by_tmc = self.npmrds_hourly_speed_by_road_class.groupby('tmc').agg(
            npmrds_speed=('speed', lambda x: x.mean(skipna=True) / mps_to_mph)).reset_index()
        merged_df = pd.merge(beam_npmrds_network_map, mean_speed_by_tmc, on='tmc', how='left').dropna(
            subset=['npmrds_speed'])
        merged_df['free_speed'] = round(merged_df[['linkFreeSpeed', 'npmrds_speed']].min(axis=1), 2)

        # Create a DataFrame with the required columns
        speed_param = merged_df[['link', 'road_class', 'free_speed']].dropna(subset=['free_speed'])
        speed_param = speed_param.rename(columns={'link': 'link_id'})

        # Add new empty columns (fill with NaN or appropriate default values)
        speed_param['osm_id'] = pd.NA
        speed_param['capacity'] = pd.NA
        speed_param['length'] = pd.NA
        speed_param['lanes'] = pd.NA

        # Define the columns to output
        output_columns = ['link_id', 'osm_id', 'capacity', 'free_speed', 'length', 'lanes']

        # Save to CSV with all desired columns
        def save_filtered_data(df, filename):
            filtered_data = df[~df['free_speed'].isna() & (df['free_speed'] != '')]
            filtered_data[output_columns].to_csv(f"{base_path_name}_link_param_for_{filename}.csv", index=False)

        # Save all links
        save_filtered_data(speed_param, "all_roads")

        # Filtering and selecting not local roads
        save_filtered_data(speed_param[speed_param['road_class'] != "Local"], "non_local_roads")

        # Filtering and selecting major roads
        major_roads_classes = ["Freeways and Expressways", "Interstate", "Principal Arterial", "Minor Arterial"]
        save_filtered_data(speed_param[speed_param['road_class'].isin(major_roads_classes)], "major_roads")

        # Filtering and selecting freeway only
        freeway_classes = ["Freeways and Expressways", "Interstate"]
        save_filtered_data(speed_param[speed_param['road_class'].isin(freeway_classes)], "freeway_roads")

        # Find the minimum free_speed
        min_free_speed = speed_param['free_speed'].min()
        speed_param['free_speed'] = min_free_speed
        save_filtered_data(speed_param, "min_speed_all_roads")


def plot(G, name):
    fig, ax = ox.plot.plot_graph(
        G,
        bgcolor="#FFFFFF",  # Light background
        #         node_color="#00FFAA",      # Bright teal nodes
        node_color="#333333",  # Bright teal nodes
        node_size=0.02,
        node_edgecolor='none',  # Node size  2.5
        #         node_alpha=0.8,            # Node transparency
        #         node_edgecolor="#333333",  # Dark edges around nodes
        node_zorder=3,  # Nodes above edges
        edge_color="#FF5A5F",  # Bright coral edges
        edge_linewidth=0.2,  # Edge thickness 0.5
        edge_alpha=0.8,  # Edge transparency
        show=False,  # Do not display immediately
        close=False  # Keep the plot open for saving
    )

    ctx.add_basemap(ax, source=ctx.providers.CartoDB.Positron, zoom=20)

    # 3. Calculate statistics
    num_nodes = len(G.nodes)
    num_edges = len(G.edges)
    # Total length in meters
    total_length = sum(data.get('length', 0) for u, v, key, data in G.edges(keys=True, data=True))

    # 4. Add title with statistics
    title = (
        f"Nodes: {num_nodes} | Edges: {num_edges} | Total Length: {total_length / 1000:.2f} km"
    )
    ax.set_title(title, fontsize=15, fontweight='bold', color='black', pad=20)

    # 5. Save the figure with 600 DPI
    fig.savefig(f'{name}', dpi=600, bbox_inches='tight')


def download_h5_data(url: str, output_path: str) -> str:
    """
    Download H5 data file if it doesn't exist locally and explore its structure.

    Parameters:
    -----------
    url : str
        URL to download the H5 file from
    output_path : str
        Local path to save the downloaded file

    Returns:
    --------
    str
        Path to the H5 file
    """
    import h5py
    # Check if file exists locally first
    if not os.path.exists(output_path):
        print(f"\nDownloading H5 data from {url}...")
        urlretrieve(url, output_path)
        print("✓ H5 data downloaded")
    else:
        print("\nUsing existing H5 data file")

    # Explore H5 file structure
    print("\nExploring H5 file structure...")

    def print_structure(name, obj):
        """Helper function to print H5 structure"""
        if isinstance(obj, h5py.Dataset):
            try:
                shape = obj.shape
                dtype = obj.dtype
                print(f"Dataset: {name}")
                print(f"  Shape: {shape}")
                print(f"  Type: {dtype}")

                # Print first few items for small datasets or sample for large ones
                if len(obj.shape) > 0:
                    if obj.shape[0] > 0:
                        sample_size = min(3, obj.shape[0])
                        print("  Sample data:")
                        print(obj[:sample_size])
            except Exception as e:
                print(f"  Error reading dataset: {e}")
        else:
            print(f"Group: {name}")

    with h5py.File(output_path, 'r') as f:
        print("\nFile structure:")
        print("==============")
        f.visititems(print_structure)

        # List all root level groups/datasets
        print("\nRoot level items:")
        for key in f.keys():
            print(f"- {key}")

    return output_path

####################################################################################################
####################################################################################################
########################################## VMT Validation ##########################################
####################################################################################################
####################################################################################################

def read_events(event_file, veh_types_file, batch, scenario):
    events = pd.read_csv(event_file)
    events['batch'] = batch
    events['scenario'] = scenario
    # Merge with vehicle types
    veh_types = pd.read_csv(veh_types_file)
    events_veh_types = events.merge(
        veh_types[['vehicleTypeId', 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']],
        left_on='vehicleType',
        right_on='vehicleTypeId'
    )
    return events_veh_types


def get_ft_path_traversals(_events):
    columns = ['time', 'type', 'vehicleType', 'vehicle', 'secondaryFuelLevel',
               'primaryFuelLevel', 'driver', 'mode', 'seatingCapacity', 'startX',
               'startY', 'endX', 'endY', 'capacity', 'arrivalTime', 'departureTime',
               'secondaryFuel', 'secondaryFuelType', 'primaryFuelType',
               'numPassengers', 'length', 'primaryFuel', 'runName', 'runLabel']

    # Filter path traversals
    pt = _events[_events['type'] == 'PathTraversal'].copy()
    pt = pt[pt['vehicle'].str.startswith('freight', na=False)]
    pt = pt[columns]

    if pt[pt['vehicle'].str.contains('-emergency-', na=False)].shape[0] > 0:
        print("This is a bug")

    # Set energy type and codes
    pt.loc[pt['vehicleType'].str.contains('E-PHEV', case=False, na=False), 'energyType'] = 'Electric'
    pt.loc[pt['vehicleType'].str.contains('E-PHEV', case=False, na=False), 'energyTypeCode'] = 'PHEV'
    pt.loc[pt['vehicleType'].str.contains('H2FC', case=False, na=False), 'energyType'] = 'Hydrogen'
    pt.loc[pt['vehicleType'].str.contains('H2FC', case=False, na=False), 'energyTypeCode'] = 'H2FC'

    # Set vehicle categories
    pt['vehicleCategory'] = 'Class 4-6 Vocational'
    pt.loc[pt['vehicleType'].str.contains('-hdt-', na=False), 'vehicleCategory'] = 'Class 7&8 Tractor'
    pt.loc[pt['vehicleType'].str.contains('-hdv-', na=False), 'vehicleCategory'] = 'Class 7&8 Vocational'

    # Set business type
    pt['business'] = 'B2B'
    pt.loc[pt['vehicle'].str.startswith('freightVehicle-b2c-', na=False), 'business'] = 'B2C'

    print("PT formatted")
    return pt


def average_speed_vector(distances, speeds):
    """Calculate average speed for vectors of distances and speeds"""
    if any(speed == 0 for speed in speeds):
        raise ValueError("Speeds must be non-zero.")

    total_distance = sum(distances)
    total_time = sum(d / s for d, s in zip(distances, speeds))

    return total_distance / total_time


def process_ft_path_traversals(_runs, _batch, _output_dir, _expansion_factor):
    # Calculate summary statistics
    runs_summary = _runs[_runs["batch"] == _batch].groupby(
        ['energyTypeCode', 'vehicleClass', 'business', 'batch', 'scenario']
    ).agg({
        'length': lambda x: _expansion_factor * sum(x / 1609.344) / 1e6,  # MVMT
        'primaryFuel': lambda x: _expansion_factor * sum(x / 3.6e12)  # GWH
    }).reset_index()

    runs_summary.columns = ['energyTypeCode', 'vehicleClass', 'business', 'runLabel', 'MVMT', 'GWH']

    # Create energy and vehicles types column
    runs_summary['energyAndVehiclesTypes'] = runs_summary['energyTypeCode'] + ' ' + runs_summary['vehicleClass']

    # Convert to categorical with specified order
    runs_summary['energyAndVehiclesTypes'] = pd.Categorical(
        runs_summary['energyAndVehiclesTypes'],
        categories=[
            "Diesel Class 4-6 Vocational",
            "Diesel Class 7&8 Vocational",
            "Diesel Class 7&8 Tractor",
            "BEV Class 7&8 Vocational"
        ]
    )

    # Save summary to CSV
    runs_summary.to_csv(
        os.path.join(_output_dir, f"{_batch}_VMT-and-GWH-by-powertrain-class.csv"),
        index=False
    )

    plot_results(runs_summary,
                 validation,
                 ["azure3", "darkgray", "azure4", "deepskyblue2"],
                 _output_dir,
                 _batch)

    return runs_summary


def read_vmt_frm_hpms(hpms_geo_file, study_area_geoid):
    # Read and process HPMS data
    link_aadt = gpd.read_file(hpms_geo_file)
    link_aadt = link_aadt[link_aadt['GEOID'].str.startswith(study_area_geoid)]
    """Calculate HPMS AADT statistics"""
    link_aadt = link_aadt.copy()
    link_aadt['Volume_hpms'] = link_aadt['AADT_Combi'] + link_aadt['AADT_Singl']
    link_aadt['VMT_hpms'] = link_aadt['Volume_hpms'] * link_aadt.geometry.length / 1609.0

    vmt_hpms = link_aadt['VMT_hpms'].sum()

    # Calculate HPMS components
    vmt_hpms_international = (vmt_hpms * 0.22) / 1e6
    vmt_hpms_through_traffic = (vmt_hpms * 0.1) / 1e6
    vmt_hpms_national = (vmt_hpms * 0.68) / 1e6

    # Create validation DataFrame
    validation = pd.DataFrame({
        'label': ['HPMS'] * 3,
        'source': ['National', 'International', 'Through Traffic'],
        'MVMT': [vmt_hpms_national, vmt_hpms_international, vmt_hpms_through_traffic]
    })

    validation['source'] = pd.Categorical(
        validation['source'],
        categories=['Through Traffic', 'International', 'National']
    )

    return validation


def validate_vmt(baseline_summary, work_dir):
    # Read and process HPMS data
    link_aadt = gpd.read_file(os.path.join(work_dir, "validation_data/HPMS/WA_HPMS_with_GEOID_LANEMILE.geojson"))
    link_aadt = link_aadt[link_aadt['GEOID'].str.startswith(('53061', '53033', '53035', '53053'))]
    link_aadt_dt = get_hpms_aadt(link_aadt)

    vmt_hpms = link_aadt_dt['VMT_hpms'].sum()
    beam_baseline = baseline_summary[baseline_summary['runLabel'] == "Baseline"]['MVMT'].sum()

    # Calculate HPMS components
    vmt_hpms_international = (vmt_hpms * 0.22) / 1e6
    vmt_hpms_through_traffic = (vmt_hpms * 0.1) / 1e6
    vmt_hpms_national = (vmt_hpms * 0.68) / 1e6

    # Create validation DataFrame
    validation = pd.DataFrame({
        'label': ['FAMOS'] * 3 + ['HPMS'] * 3,
        'source': ['National', 'International', 'Through Traffic'] * 2,
        'MVMT': [beam_baseline, 0.0, 0.0, vmt_hpms_national, vmt_hpms_international, vmt_hpms_through_traffic]
    })

    validation['source'] = pd.Categorical(
        validation['source'],
        categories=['Through Traffic', 'International', 'National']
    )

    return validation


def plot_results(baseline_summary, validation, baseline_summary_colors, baseline_output_dir, baseline_runs_name):
    # Plot VMT validation
    plt.figure(figsize=(7, 4))
    sns.barplot(data=validation, x='label', y='MVMT', hue='source')
    plt.title('Total VMT')
    plt.xlabel('Source')
    plt.ylabel('Million VMT')
    plt.savefig(os.path.join(baseline_output_dir, f"{baseline_runs_name}_vmt_validation.png"))
    plt.close()

    # Plot VMT by powertrain class
    plt.figure(figsize=(7, 4))
    g = sns.barplot(
        data=baseline_summary,
        x='runLabel',
        y='MVMT',
        hue='energyAndVehiclesTypes',
        palette=baseline_summary_colors
    )
    plt.title('Total Truck Travel - Baseline')
    plt.xlabel('Scenario')
    plt.ylabel('VMT')
    plt.xticks(rotation=0)
    plt.savefig(os.path.join(baseline_output_dir, f"{baseline_runs_name}_VMT-by-powertrain-class.png"))
    plt.close()

    # Plot Energy consumption
    plt.figure(figsize=(7, 4))
    g = sns.barplot(
        data=baseline_summary,
        x='runLabel',
        y='GWH',
        hue='energyAndVehiclesTypes',
        palette=baseline_summary_colors
    )
    plt.title('Energy Consumption - Baseline')
    plt.xlabel('Scenario')
    plt.ylabel('GWh')
    plt.xticks(rotation=0)
    plt.savefig(os.path.join(baseline_output_dir, f"{baseline_runs_name}_GWH-by-powertrain-class.png"))
    plt.close()
