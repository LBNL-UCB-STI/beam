import os
import time
import xml.etree.ElementTree as ET
from statistics import median
from typing import Any

import contextily as ctx
import geopandas as gpd
import matplotlib.pyplot as plt
import networkx as nx
import numpy as np
import osmnx as ox
import pandas as pd
import pyarrow.csv as pv
import seaborn as sns
from urllib.request import urlretrieve
from cenpy import products
import requests
import zipfile
import json
import hashlib

from networkx import DiGraph
from osmnx import settings
from osmnx import truncate
import shapely.geometry
from shapely.ops import unary_union
import pyproj


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


def collect_geographic_boundaries(state_fips_code, county_fips_codes, year, study_area_boundary_geo_path, geo_level):
    if os.path.exists(study_area_boundary_geo_path):
        return gpd.read_file(study_area_boundary_geo_path)
    else:
        from pygris import counties, block_groups

        if geo_level == 'county':
            # Define fips code for selected counties
            geo_data = counties(state=state_fips_code, year=year, cb=True, cache=True)
        elif geo_level == 'cbg':
            # Define fips code for selected counties
            geo_data = block_groups(state=state_fips_code, year=year, cb=True, cache=True)
        elif geo_level == 'taz':
            geo_data = collect_taz_boundaries(state_fips_code, year, os.path.dirname(study_area_boundary_geo_path))
        elif geo_level == "tract":
            geo_data = collect_tract_boundaries(state_fips_code, county_fips_codes, year)
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
        # study_area_geo_projected_path = base_name + "_epsg" + str(projected_coordinate_system) + extension
        # selected_geo.to_crs(epsg=projected_coordinate_system).to_file(study_area_geo_projected_path, driver="GeoJSON")

        selected_geo_wgs84 = selected_geo.to_crs(epsg=4326)
        selected_geo_wgs84.to_file(study_area_boundary_geo_path, driver="GeoJSON")
        return selected_geo_wgs84


def collect_census_data(state_fips_code, county_fips_codes, year, census_data_file, geo_level='county'):
    """
    Collect census data at specified geographic level (county, tract, or CBG).

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list or str
        List of county FIPS codes or comma-separated string
    year : int
        Census year
    census_data_file : str
        Path to save the CSV output
    geo_level : str
        Geographic level for data collection: 'county', 'tract', or 'cbg'
        Default is 'county'

    Returns
    -------
    pandas.DataFrame
        DataFrame containing population data for the specified geographic level
    """
    # Validate geo_level parameter
    valid_levels = ['county', 'tract', 'cbg']
    if geo_level.lower() not in valid_levels:
        raise ValueError(f"Invalid geo_level '{geo_level}'. Must be one of: {', '.join(valid_levels)}")

    geo_level = geo_level.lower()

    # Check if the output file already exists
    if os.path.exists(census_data_file):
        print(f"Loading existing {geo_level} data from {census_data_file}")
        return pd.read_csv(census_data_file, dtype={'GEOID': str})

    # Get Census API key from file
    api_key_path = os.path.expanduser("~/.census_api_key")
    try:
        with open(api_key_path, 'r') as f:
            census_api_key = f.read().strip()
            print(f"Your Census API key is [{census_api_key}]")
    except FileNotFoundError:
        raise FileNotFoundError(
            f"Census API key file not found at {api_key_path}. Please create this file with your API key.")

    if not census_api_key:
        raise ValueError("Census API key is empty. Please check your API key file.")

    print(f"Collecting {geo_level.upper()} data for year {year}...")

    # Initialize the Census API
    from census import Census
    c = Census(census_api_key, year=year)

    # Convert list of county FIPS to comma-separated string if it's a list
    if isinstance(county_fips_codes, list):
        county_fips_string = ','.join(county_fips_codes)
    else:
        county_fips_string = county_fips_codes

    print(f"Downloading population data for {geo_level}s...")

    try:
        # Different API calls based on geographic level
        if geo_level == 'county':
            census_data = c.acs5.state_county(
                fields=('NAME', 'B01003_001E'),  # B01003_001E is total population
                state_fips=state_fips_code,
                county_fips=county_fips_string
            )
        elif geo_level == 'tract':
            census_data = c.acs5.state_county_tract(
                fields=('NAME', 'B01003_001E'),
                state_fips=state_fips_code,
                county_fips=county_fips_string,
                tract='*'  # Request all tracts
            )
        elif geo_level == 'cbg':
            census_data = c.acs5.state_county_blockgroup(
                fields=('NAME', 'B01003_001E'),
                state_fips=state_fips_code,
                county_fips=county_fips_string,
                blockgroup='*'  # Request all block groups
            )

        # Create a DataFrame from the census data
        df = pd.DataFrame(census_data)

        # Rename columns for clarity
        df = df.rename(columns={'B01003_001E': 'population', 'NAME': 'name'})

        # Create GEOID based on geographic level
        if geo_level == 'county':
            df['GEOID'] = df['state'] + df['county']
        elif geo_level == 'tract':
            df['GEOID'] = df['state'] + df['county'] + df['tract']
        elif geo_level == 'cbg':
            df['GEOID'] = df['state'] + df['county'] + df['tract'] + df['block group']

        # Convert population to numeric
        df['population'] = pd.to_numeric(df['population'], errors='coerce')

        # Save the raw census data
        if census_data_file:
            df.to_csv(census_data_file, index=False)
            print(f"{geo_level.capitalize()} population data saved to {census_data_file}")

        return df

    except Exception as e:
        print(f"Error downloading Census data: {e}")
        raise


def download_tract_census_data(state_fips_code, county_fips_codes, year, census_data_file):
    """
    Download census tract population data from the Census Bureau's ACS 5-year estimates.

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list
        List of county FIPS codes
    year : int
        Reference year for population estimates (July 1st reference date)
    census_data_file: str
        Path to the CSV file where population data will be saved

    Returns
    -------
    pandas.DataFrame
        DataFrame containing population data for census tracts
    """
    if not os.path.exists(census_data_file):
        # Connect to Census API
        try:
            conn = products.APIConnection(f"ACSDT5Y{year}")
            # Get population data for tracts
            pop_data = None
            for county_fips in county_fips_codes:
                tract_data = conn.query(
                    ['B01003_001E'],  # Total population estimate
                    geo_unit='tract',
                    geo_filter={
                        "state": state_fips_code,
                        "county": county_fips
                    }
                )
                pop_data = pd.concat([pop_data, tract_data]) if pop_data is not None else tract_data

            # Rename columns
            pop_data = pop_data.rename(columns={'B01003_001E': 'population'})

            # Create GEOID by combining state, county, and tract
            pop_data['GEOID'] = (pop_data['state'] + pop_data['county'] + pop_data['tract']).astype(str)

            # Convert population to numeric
            pop_data['population'] = pd.to_numeric(pop_data['population'], errors='coerce')
            pop_data.to_csv(census_data_file, index=False)

        except Exception as e:
            print(f"Failed to retrieve population data: {e}")
            raise
    else:
        pop_data = pd.read_csv(census_data_file, dtype={'GEOID': str})

    return pop_data


def collect_tract_boundaries(state_fips_code, county_fips_codes, year):
    """
    Download census tract boundaries from TIGER/Line shapefiles.

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list
        List of county FIPS codes
    year : int
        Reference year for boundaries

    Returns
    -------
    geopandas.GeoDataFrame
        GeoDataFrame containing tract boundaries
    """
    try:
        # Download geographic boundaries
        geo_url = f"https://www2.census.gov/geo/tiger/TIGER{year}/TRACT/tl_{year}_{state_fips_code}_tract.zip"
        geo_data = gpd.read_file(geo_url)

        # Filter for counties of interest
        geo_data = geo_data[geo_data['COUNTYFP'].isin(county_fips_codes)]
    except Exception as e:
        print(f"Failed to retrieve geographic boundaries: {e}")
        raise
    return geo_data


def collect_boundaries_person_per_km2(
        state_fips_code,
        county_fips_codes,
        year,
        projected_coordinate_system,
        census_data_file,
        boundaries_geo_file,
        geo_level
):
    """
    Collect census tract boundaries for tracts with population density above specified threshold
    and analyze population distribution.

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list
        List of county FIPS codes
    year : int
        Reference year for population estimates (July 1st reference date)
    projected_coordinate_system: str
       Proj4 string for the projected coordinate system
    census_data_file: str
        Path to the CSV file containing population density data
    boundaries_geo_file: str
        Path to the GeoJSON file containing boundaries in WGS84 projection
    geo_level: str
        tract or cbg

    Returns
    -------
    geopandas.GeoDataFrame
        Selected tract boundaries in WGS84 projection with population density information

    Notes
    -----
    Population estimates are from the Census Bureau's ACS 5-year estimates.
    """
    pop_data = collect_census_data(state_fips_code, county_fips_codes, year, census_data_file, geo_level=geo_level)

    # Load boundaries in WGS84 projection
    geo_data = collect_geographic_boundaries(state_fips_code, county_fips_codes, year, boundaries_geo_file, geo_level)

    # Process the data (this was previously in process_tract_boundaries_ppsk)
    # Calculate area and density (with proper projection)
    # Merge boundaries with population data
    geo_data['GEOID'] = geo_data['GEOID'].astype(str)

    # Now merge with consistent string types
    tracts_with_pop = geo_data.merge(pop_data, on='GEOID')

    # Project to Web Mercator for accurate area calculation
    tracts_with_pop['area_sqkm'] = (
            tracts_with_pop.to_crs(epsg=projected_coordinate_system)  # Project to Web Mercator
            .geometry.area / 1000000  # Convert m² to km²
    )
    tracts_with_pop['density_per_km2'] = tracts_with_pop['population'] / tracts_with_pop['area_sqkm']

    # Calculate percentile ranks for context
    tracts_with_pop['density_percentile'] = (
            tracts_with_pop['density_per_km2'].rank(pct=True) * 100
    ).round(1)

    # Print detailed density analysis
    print("\nPopulation Density Analysis:")
    print("==========================")

    # Tract-level density summary
    print("\nTract Density Summary (people/km²):")
    print("--------------------------------")
    stats = tracts_with_pop['density_per_km2'].describe()
    print(f"Mean density:     {stats['mean']:,.1f}")
    print(f"Median density:   {stats['50%']:,.1f}")
    print(f"Standard deviation:  {stats['std']:,.1f}")
    print(f"Minimum density:  {stats['min']:,.1f}")
    print(f"Maximum density:  {stats['max']:,.1f}")

    # Density distribution
    print("\nDensity Distribution Quartiles:")
    print("----------------------------")
    for q in [0.25, 0.5, 0.75]:
        print(f"{int(q * 100)}th percentile: {tracts_with_pop['density_per_km2'].quantile(q):,.1f}")

    return tracts_with_pop


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


def str_median(values):
    """Calculate median after converting string values to numbers."""
    # Convert strings to integers, filtering out non-numeric values
    numeric_values = []
    for v in values:
        try:
            if isinstance(v, str):
                numeric_values.append(int(v))
            elif isinstance(v, (int, float)):
                numeric_values.append(int(v))
        except (ValueError, TypeError):
            continue

    if not numeric_values:
        return None
    return int(median(numeric_values))


def process_ferry_edges(ferry_graph, utm_epsg) -> nx.MultiDiGraph:
    """Process ferry edges to make them compatible with car network"""
    if ferry_graph.number_of_edges() == 0:
        print("No ferry edges found in the graph.")
        return nx.MultiDiGraph()

    # Extract nodes and edges
    ferry_nodes, ferry_edges = ox.graph_to_gdfs(ferry_graph)
    print(f"Total ferry edges: {len(ferry_edges)}")

    # Print available columns to debug
    print(f"Available columns: {ferry_edges.columns.tolist()}")

    # Create default masks - assume access is allowed unless explicitly denied
    # This is more lenient and works better with OSM data which often lacks explicit tags
    passenger_car_mask = pd.Series(True, index=ferry_edges.index)
    truck_mask = pd.Series(True, index=ferry_edges.index)

    # Check for explicit denials first
    if 'motorcar' in ferry_edges.columns:
        passenger_car_mask &= ~(ferry_edges['motorcar'] == 'no')
        print(f"After motorcar check: {passenger_car_mask.sum()} car-accessible edges")

    if 'motor_vehicle' in ferry_edges.columns:
        motor_vehicle_denied = ferry_edges['motor_vehicle'] == 'no'
        passenger_car_mask &= ~motor_vehicle_denied
        truck_mask &= ~motor_vehicle_denied
        print(
            f"After motor_vehicle check: {passenger_car_mask.sum()} car-accessible, {truck_mask.sum()} truck-accessible edges")

    # Check for explicit truck denials
    for truck_tag in ['hgv', 'goods', 'truck']:
        if truck_tag in ferry_edges.columns:
            truck_mask &= ~(ferry_edges[truck_tag] == 'no')
            print(f"After {truck_tag} check: {truck_mask.sum()} truck-accessible edges")

    # For ferries, if there's no explicit tag, assume it's accessible (common for OSM ferry data)
    # This is the key change - we're now assuming access by default
    selected_edges = ferry_edges[(passenger_car_mask | truck_mask)].copy()

    if selected_edges.empty:
        print("No ferry routes found that allow passenger cars")
        return nx.MultiDiGraph()

    print(f"Found {len(selected_edges)} suitable ferry edges")

    # Set ferry attributes
    selected_edges['reversed'] = False
    selected_edges['maxspeed'] = "10 mph"
    selected_edges['highway'] = "unclassified"
    selected_edges['oneway'] = "no"
    selected_edges['lanes'] = "2"
    selected_edges["hgv"] = False  # Mark as not accessible to heavy-duty
    selected_edges["mdv"] = True  # Mark as accessible to medium-duty

    # Keep only nodes that are used by the filtered edges
    used_nodes = set(selected_edges.index.get_level_values(0)).union(
        set(selected_edges.index.get_level_values(1))
    )
    selected_nodes = ferry_nodes.loc[list(used_nodes)]

    # Reconstruct graph and project
    g_ferry_reconstructed = ox.graph_from_gdfs(selected_nodes, selected_edges)
    g_ferry_projected = ox.project_graph(g_ferry_reconstructed, to_crs=utm_epsg)

    return g_ferry_projected


def convert_weight(value: float, from_unit: str, to_unit: str) -> float:
    """Convert weight between different units."""
    # Conversion factors
    conversions = {
        "lbs_to_kg": 0.453592,
        "kg_to_lbs": 2.20462,
        "tons_to_kg": 1000,
        "kg_to_tons": 0.001
    }

    if from_unit == to_unit:
        return value

    conversion_key = f"{from_unit}_to_{to_unit}"
    if conversion_key in conversions:
        return value * conversions[conversion_key]

    # Handle two-step conversions if needed
    if from_unit == "lbs" and to_unit == "tons":
        return value * conversions["lbs_to_kg"] * conversions["kg_to_tons"]
    if from_unit == "tons" and to_unit == "lbs":
        return value * conversions["tons_to_kg"] * conversions["kg_to_lbs"]

    raise ValueError(f"Unsupported conversion from {from_unit} to {to_unit}")


def get_weight_in_standard_unit(weight_str: str, target_unit: str) -> float:
    """Convert weight string to numeric value in target unit."""
    if pd.isna(weight_str):
        return None

    # Handle numeric-only strings (assume they're in target unit)
    if str(weight_str).replace('.', '').isdigit():
        return float(weight_str)

    # Extract number and unit from string
    import re
    match = re.match(r'(\d+\.?\d*)\s*(tons?|t|kg|lbs?)', str(weight_str).lower())
    if not match:
        return None

    value, unit = match.groups()
    value = float(value)

    # Standardize unit names
    unit_mapping = {
        't': 'tons',
        'ton': 'tons',
        'lb': 'lbs',
        'kg': 'kg'
    }
    unit = unit_mapping.get(unit, unit)

    # Convert to target unit
    return convert_weight(value, unit, target_unit)


def standardize_oneway(value):
    """Return 'yes' only if all values are 'yes'/'true'/'1', otherwise 'no'"""
    valid_yes = {'yes', 'true', '1'}

    # Handle list case
    if isinstance(value, list):
        # Empty list or any value not in valid_yes should return 'no'
        return 'no' if not value or any(not v or str(v).lower().strip() not in valid_yes for v in value) else 'yes'

    # Handle single value case
    return 'yes' if value and str(value).lower().strip() in valid_yes else 'no'


def standardize_maxspeed(value):
    """Parse maxspeed values that might contain multiple values, returning the lowest speed"""
    if not value:
        return None

    # Convert to a consistent string format regardless of input type
    value_str = ';'.join(str(v) for v in value) if isinstance(value, list) else str(value)

    # Extract all numeric values using a single pass
    speeds = []
    for part in value_str.split(';'):
        # Extract digits and decimal points
        numeric_part = ''.join(c for c in part if c.isdigit() or c == '.')
        if numeric_part:
            try:
                speeds.append(float(numeric_part))
            except (ValueError, TypeError):
                pass

    # Return the lowest speed or None
    return min(speeds) if speeds else None

def process_tags(_g: nx.MultiDiGraph, config: dict) -> nx.MultiDiGraph:
    """Process vehicle classifications based on FHWA weight classes."""
    print("Processing vehicle classifications...")

    # Get weight limits and unit from config
    weight_config = config["weight_limits"]
    target_unit = weight_config["unit"]
    mdv_max = weight_config["mdv_max"]
    hdv_max = weight_config["hdv_max"]

    # Get graph data while preserving MultiIndex
    nodes, edges = ox.graph_to_gdfs(_g)

    # Copy HGV weight restrictions if present
    if "maxweight:hgv" in edges.columns:
        hgv_mask = ~edges["maxweight:hgv"].isna()
        if hgv_mask.any():
            edges.loc[hgv_mask, "maxweight"] = edges.loc[hgv_mask, "maxweight:hgv"].copy()

    if "maxweight" in edges.columns:
        # Convert weights to standard unit specified in config
        edges["weight_numeric"] = edges["maxweight"].apply(
            lambda x: get_weight_in_standard_unit(x, target_unit)
        )

        # Classify roads based on weight limits
        edges["vehicle_class"] = None

        # Create weight classification masks
        mdv_mask = edges["weight_numeric"].notna() & (edges["weight_numeric"] <= mdv_max)
        hdv_mask = edges["weight_numeric"].notna() & (edges["weight_numeric"] <= hdv_max)

        # Apply classifications
        edges.loc[mdv_mask, "vehicle_class"] = "MDV"
        edges.loc[hdv_mask, "vehicle_class"] = "HDV"

        # Roads with no weight restrictions are assumed to be accessible to all vehicles
        no_restriction_mask = edges["weight_numeric"].isna()
        edges.loc[no_restriction_mask, "vehicle_class"] = "ALL"

    edges['oneway'] = edges['oneway'].apply(standardize_oneway)
    edges["maxspeed"] = edges['maxspeed'].apply(standardize_maxspeed)
    # Convert back to MultiDiGraph
    g_updated = ox.graph_from_gdfs(nodes, edges)

    return g_updated


def shorten_osmid(osmid):
    # Convert osmid to string if it isn't already
    osmid_str = str(osmid)
    # Create a hash of the osmid
    hash_object = hashlib.md5(osmid_str.encode())
    # Get first 8 characters of the hash
    short_id = hash_object.hexdigest()[:8]
    return short_id


def find_long_tags_in_gdf(gdf, element_type="elements"):
    """
    Find columns and combinations of attributes that exceed 250 characters in a GeoDataFrame.

    Parameters:
    -----------
    gdf : GeoDataFrame
        The input GeoDataFrame (can be either nodes or edges)
    element_type : str, optional
        The type of elements being analyzed ("nodes" or "edges") for output messages

    Returns:
    --------
    tuple
        (long_tags, long_comb_tags) where:
        - long_tags: dict of individual columns with values >= 250 characters
        - long_comb_tags: dict of rows with combined attribute length >= 250 characters
    """
    print(f"\nAnalyzing {element_type}...")

    # Find individual columns with values longer than 250 characters
    long_tags = {}
    for column in gdf.columns:
        # Convert all values to strings and check their lengths
        max_length = gdf[column].astype(str).str.len().max()
        if max_length >= 250:
            long_tags[column] = max_length

    # Print results for individual columns
    if long_tags:
        print(f"\nIndividual {element_type} columns with values >= 250 characters:")
        for column, length in long_tags.items():
            print(f"Column '{column}': max length = {length} characters")
            # Print an example of a long value
            long_value_idx = gdf[column].astype(str).str.len().idxmax()
            print(f"Example long value: {gdf[column].iloc[long_value_idx]}\n")
    else:
        print(f"No individual {element_type} columns found with values >= 250 characters")

    # Find combinations of attributes that exceed 250 characters
    print(f"\nChecking {element_type} attribute combinations...")
    # Get all rows where any combination of attributes might be long
    long_comb_tags = {}
    for idx, row in gdf.iterrows():
        comb_length = 0
        contributing_cols = []

        for col in gdf.columns:
            value = str(row[col])
            if len(value) > 0 and value.lower() != 'nan':  # Skip empty or NaN values
                value_length = len(value)
                comb_length += value_length
                if value_length > 0:  # Only add if the value has length
                    contributing_cols.append({
                        'column': col,
                        'length': value_length,
                        'value': value
                    })

        if comb_length >= 250:
            long_comb_tags[idx] = {
                'total_length': comb_length,
                'contributing_columns': contributing_cols
            }

    # Print results for combinations
    if long_comb_tags:
        print(f"\n{element_type.capitalize()} rows with combined attribute length >= 250 characters:")
        for idx, info in long_comb_tags.items():
            print(f"\nRow {idx}:")
            print(f"Total combined length: {info['total_length']} characters")
            print("Contributing columns:")
            for col_info in info['contributing_columns']:
                print(f"- {col_info['column']}: length={col_info['length']} chars")
                if col_info['length'] > 50:  # Show value only if it's significantly long
                    print(f"  Value: {col_info['value'][:50]}...")  # Show first 50 chars
    else:
        print(f"No combinations of {element_type} attributes found exceeding 250 characters")

    return long_tags, long_comb_tags


def filtering_network_layer(_boundaries_person_per_km2, _min_density_per_km2, _geo_file_prefix):
    # Create density-specific paths
    if _min_density_per_km2 == 0:
        densely_populated_tracts_geo_file = f"{_geo_file_prefix}_wgs84.geojson"
    else:
        densely_populated_tracts_geo_file = f"{_geo_file_prefix}_{_min_density_per_km2}ppsk_wgs84.geojson"

    # Get boundaries for this density level
    print("Loading tract boundaries...")
    if os.path.exists(densely_populated_tracts_geo_file):
        densely_populated_geo = gpd.read_file(densely_populated_tracts_geo_file)
        print("✓ Loaded existing tract boundaries")
    else:
        print("Extracting dense tract boundaries...")

        # Filter by density
        densely_populated = _boundaries_person_per_km2[
            _boundaries_person_per_km2["density_per_km2"] >= _min_density_per_km2
        ]

        print(f"\nSelection Results:")
        print("----------------")
        print(f"Selected {len(densely_populated)} out of {len(_boundaries_person_per_km2)} tracts")
        print(f"Density threshold: >= {_min_density_per_km2:,.1f} people/km²")
        print(f"Total population in selected tracts: {densely_populated['population'].sum():,}")

        # Get total population
        total_population = _boundaries_person_per_km2['population'].sum()

        # Calculate percentage with error handling
        if total_population > 0:
            population_percentage = (densely_populated['population'].sum() / total_population * 100)
            print(f"Percentage of total population: {population_percentage:.1f}%\n")
        else:
            print("Warning: Total population is zero, cannot calculate percentage\n")  # Save in projected crs

        # Save WGS84 version
        densely_populated_geo = densely_populated.to_crs(epsg=4326)
        densely_populated_geo.to_file(f"{densely_populated_tracts_geo_file}", driver="GeoJSON")

    return densely_populated_geo


def meters_to_degrees(lon, lat, utm_epsg, buffer_meters):
    """
    Calculate the equivalent buffer distance in degrees for a given buffer in meters,
    using a specified UTM projection for better precision.

    Parameters:
    -----------
    lon : float
        Longitude coordinate (x) in WGS84
    lat : float
        Latitude coordinate (y) in WGS84
    utm_epsg : int
        The EPSG code for the UTM coordinate reference system (e.g., 26910 for UTM Zone 10N)
    buffer_meters : float
        Buffer distance in meters

    Returns:
    --------
    float
        Equivalent buffer distance in degrees
    """
    # Create UTM CRS from EPSG code
    utm_crs = f"EPSG:{utm_epsg}"

    # Create transformers
    wgs84_to_utm = pyproj.Transformer.from_crs("EPSG:4326", utm_crs, always_xy=True)
    utm_to_wgs84 = pyproj.Transformer.from_crs(utm_crs, "EPSG:4326", always_xy=True)

    # Convert coordinates to UTM
    x_utm, y_utm = wgs84_to_utm.transform(lon, lat)

    # Calculate points at buffer distance in cardinal directions
    east_utm = (x_utm + buffer_meters, y_utm)
    north_utm = (x_utm, y_utm + buffer_meters)

    # Convert buffered points back to WGS84
    east_lon, east_lat = utm_to_wgs84.transform(*east_utm)
    north_lon, north_lat = utm_to_wgs84.transform(*north_utm)

    # Calculate degree differences
    lon_diff = abs(east_lon - lon)  # East-West difference (longitude)
    lat_diff = abs(north_lat - lat)  # North-South difference (latitude)

    # Return the average as an approximation
    # You could also return both separately if you need different buffers for lat/lon
    return (lon_diff + lat_diff) / 2


def to_convex_hull(input_data, utm_epsg, buffer_in_meters):
    """
    Create a buffered convex hull from input data.

    Parameters:
    -----------
    input_data : GeoDataFrame, GeoSeries, or Shapely geometry
        The input geographic data
    utm_epsg : int
        EPSG code for the UTM projection to use for accurate distance calculations
    buffer_in_meters : float
        Buffer distance in meters

    Returns:
    --------
    Shapely geometry
        The buffered convex hull
    """
    # Handle different input types
    if isinstance(input_data, gpd.GeoDataFrame):
        # GeoDataFrame: get the convex hull of all geometries
        convex_hull = input_data.geometry.unary_union.convex_hull
    elif isinstance(input_data, gpd.GeoSeries):
        # GeoSeries: get the convex hull of all geometries
        convex_hull = input_data.unary_union.convex_hull
    elif hasattr(input_data, 'geom_type'):
        # Shapely geometry: get its convex hull
        convex_hull = input_data.convex_hull
    else:
        raise TypeError("Input must be a GeoDataFrame, GeoSeries, or Shapely geometry")

    # Get centroid
    lon = convex_hull.centroid.x
    lat = convex_hull.centroid.y

    # Convert buffer distance
    buffer_in_degrees = meters_to_degrees(lon, lat, utm_epsg, buffer_in_meters)

    # Buffer in degrees
    buffered_convex_hull = convex_hull.buffer(buffer_in_degrees)

    # Create a GeoDataFrame from the geometry
    hull_gdf = gpd.GeoDataFrame(
        {'geometry': [buffered_convex_hull]},
        crs="EPSG:4326"  # Assuming WGS84
    )

    # Save as GeoJSON
    hull_gdf.to_file(f"convex_hull_{str(buffer_in_meters)}", driver='GeoJSON')

    return buffered_convex_hull


def download_and_prepare_osm_network(_study_area_config: dict) -> nx.MultiDiGraph:
    print("\n=== Starting OSM Network Download and Preparation ===")

    # Apply OSMNX settings
    for setting, value in _study_area_config["osmnx_settings"].items():
        setattr(ox.settings, setting, value)
    print("✓ OSMNX settings applied")

    # List to store the graphs
    graphs = []

    study_area = _study_area_config['study_area']
    print(f"Collecting {study_area} boundaries!")
    # Create density-specific paths
    base_name = f"{_study_area_config['work_dir']}/geo/{study_area}"
    census_year = _study_area_config["census_year"]
    utm_epsg = _study_area_config["utm_epsg"]
    state_fips_code = _study_area_config["state_fips"]
    county_fips_codes = _study_area_config["county_fips"]
    tolerance = _study_area_config["tolerance"]

    for layer_name, layer_config in _study_area_config["graph_layers"].items():
        # Get the geographic level for this layer
        geo_level = layer_config["geo_level"]

        # Get the minimum density if specified (for residential layers)
        min_density = layer_config.get("min_density_per_km2", 0)

        # Get the custom filter for this layer
        custom_filter = layer_config["custom_filter"]

        # Get the buffer zone size in meters if specified (for residential layers)
        buffer_in_meters = layer_config["buffer_zone_in_meters"]

        # Create the region boundary GeoDataFrame
        region_counties_geo_file = f"{base_name}_{geo_level}_{census_year}_wgs84.geojson"

        # Census data file
        census_data_file = f"{base_name}_acs_census_{geo_level}_{census_year}.csv"

        if layer_name == "main":
            print(f"\nProcessing {layer_name} layer")
            # This returns a GeoDataFrame
            region_boundary_gdf = collect_geographic_boundaries(
                state_fips_code=state_fips_code,
                county_fips_codes=county_fips_codes,
                year=census_year,
                study_area_boundary_geo_path=region_counties_geo_file,
                geo_level=geo_level
            )
            graph_layer = to_convex_hull(region_boundary_gdf, utm_epsg, buffer_in_meters)
            network_type = "drive"
            simplify = False
            retain_all = True
            truncate_by_edge = True

        elif layer_name == "residential":
            print(f"\nProcessing {layer_name} layer with minimum density: {min_density} pop/km²")
            boundaries_person_per_km2 = collect_boundaries_person_per_km2(
                state_fips_code,
                county_fips_codes,
                census_year,
                utm_epsg,
                census_data_file,
                region_counties_geo_file,
                geo_level
            )
            filtered_boundaries = filtering_network_layer(
                boundaries_person_per_km2,
                min_density,
                f"{base_name}_{geo_level}_{census_year}"
            )
            graph_layer = shapely.ops.unary_union([
                to_convex_hull(geom, utm_epsg, buffer_in_meters) for geom in filtered_boundaries.geometry
            ])
            network_type = "drive"
            simplify = False
            retain_all = True
            truncate_by_edge = True

        elif layer_name == "ferry":
            print(f"\nProcessing {layer_name} layer to connect island through motor ferries...")
            region_boundary_wgs84 = collect_geographic_boundaries(
                state_fips_code=state_fips_code,
                county_fips_codes=county_fips_codes,
                year=census_year,
                study_area_boundary_geo_path=region_counties_geo_file,
                geo_level=geo_level
            )
            graph_layer = to_convex_hull(region_boundary_wgs84, utm_epsg, buffer_in_meters)
            network_type = "all"
            simplify = True
            retain_all = True
            truncate_by_edge = False

        else:
            raise ValueError(f"Invalid layer name: {layer_name}")

        print("✓ Boundaries collected and unified")

        # Download OSM Network for this density level
        print(f"Downloading OSM network with filter: {custom_filter}")
        g = ox.graph_from_polygon(
            graph_layer,
            network_type=network_type,
            simplify=simplify,
            retain_all=retain_all,
            truncate_by_edge=truncate_by_edge,
            custom_filter=custom_filter
        )
        print(f"✓ Downloaded network with {g.number_of_nodes()} nodes and {g.number_of_edges()} edges")

        # Special processing for ferry network
        if layer_name == "ferry":
            g = process_ferry_edges(g, utm_epsg)
            if g.number_of_edges() > 0:
                print(f"✓ Processed {g.number_of_edges()} ferry connections")
            else:
                print("✗ No suitable ferry connections found")
                # Skip adding this empty graph
                continue

        # Ensure column compatibility with existing graphs
        if graphs and g.number_of_edges() > 0:
            # Get nodes and edges of current graph
            current_nodes, current_edges = ox.graph_to_gdfs(g)

            # Collect all unique columns from existing graphs
            existing_columns = set()
            for existing_graph in graphs:
                _, existing_edges = ox.graph_to_gdfs(existing_graph)
                existing_columns.update(existing_edges.columns)

            # Add missing columns to current graph's edges
            for col in existing_columns:
                if col not in current_edges.columns:
                    current_edges[col] = None

            # Also ensure existing graphs have columns from current graph
            current_columns = set(current_edges.columns)
            for i, existing_graph in enumerate(graphs):
                existing_nodes, existing_edges = ox.graph_to_gdfs(existing_graph)

                columns_added = False
                for col in current_columns:
                    if col not in existing_edges.columns:
                        existing_edges[col] = None
                        columns_added = True

                # Only rebuild the graph if columns were added
                if columns_added:
                    graphs[i] = ox.graph_from_gdfs(existing_nodes, existing_edges)

            # Rebuild current graph with updated columns
            g = ox.graph_from_gdfs(current_nodes, current_edges)

        # Add the graph to the list if it has edges
        if g.number_of_edges() > 0:
            graphs.append(g)

    print("\n=== Processing Combined Network ===")
    g_combined = nx.compose_all(graphs)

    # Rest of the function remains the same...
    print(f"✓ Combined network has {g_combined.number_of_nodes()} nodes and {g_combined.number_of_edges()} edges")

    g_projected = ox.project_graph(g_combined, to_crs=utm_epsg).copy()
    print("✓ Network projected")

    g_with_speeds = ox.add_edge_speeds(g_projected)
    print("✓ Edge speeds added")

    g_processed_tags = process_tags(g_with_speeds, _study_area_config)
    print("✓ Freight restrictions processed")

    g_consolidated = ox.consolidate_intersections(
        g_processed_tags,
        tolerance=tolerance,
        rebuild_graph=True,
        dead_ends=True,
        reconnect_edges=True
    )
    print("✓ Intersections consolidated")

    nodes, edges = ox.graph_to_gdfs(g_consolidated)
    edges['length'] = edges['geometry'].length
    g_length_updated = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_consolidated.graph)
    print("✓ Edge lengths updated")

    g_simplified = ox.simplification.simplify_graph(
        g_length_updated,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True
    )
    print("✓ Network simplified")

    nodes, edges = ox.graph_to_gdfs(g_simplified)
    edges['osmid_hash'] = edges['osmid'].apply(lambda x: shorten_osmid(x))
    nodes['osmid_hash'] = nodes['osmid_original'].apply(lambda x: shorten_osmid(x))
    g_hashed = ox.graph_from_gdfs(nodes, edges)
    print("✓ OSM IDs shortened")

    g_wgs84 = ox.project_graph(g_hashed, to_crs="epsg:4326")
    print("✓ Projected to WGS84")

    g_connected = ox.truncate.largest_component(g_wgs84.copy())
    print(f"✓ Final network has {g_connected.number_of_nodes()} nodes and {g_connected.number_of_edges()} edges")

    print("\n=== Network Download and Preparation Complete ===\n")
    return g_connected


def save_graph_to_osm(G, filename="output.osm"):
    # Bounding box
    xs = [d['x'] for _, d in G.nodes(data=True) if 'x' in d]
    ys = [d['y'] for _, d in G.nodes(data=True) if 'y' in d]
    minlon, maxlon = min(xs), max(xs)
    minlat, maxlat = min(ys), max(ys)

    root = ET.Element("osm", version="0.6", generator="OSMnx2OSM")
    ET.SubElement(root, "bounds",
                  minlat=str(minlat), minlon=str(minlon),
                  maxlat=str(maxlat), maxlon=str(maxlon))

    node_map = {}
    node_id = 1

    # Write nodes + attributes as tags
    for n, d in G.nodes(data=True):
        lat, lon = d.get('y'), d.get('x')
        if lat is None or lon is None: continue
        node = ET.SubElement(root, "node",
                             id=str(node_id), lat=str(lat), lon=str(lon),
                             version="1", changeset="1", user="osmnx", uid="1",
                             timestamp="2020-01-01T00:00:00Z"
                             )
        node_map[n] = node_id
        for k, v in d.items():
            if k not in ("x", "y") and v is not None:
                ET.SubElement(node, "tag", k=str(k), v=str(v))
        node_id += 1

    # Write ways (edges) + attributes as tags
    way_id = -1
    for u, v, edata in G.edges(data=True):
        if u not in node_map or v not in node_map:
            continue
        way = ET.SubElement(root, "way",
                            id=str(way_id), version="1", changeset="1",
                            user="osmnx", uid="1", timestamp="2020-01-01T00:00:00Z")
        ET.SubElement(way, "nd", ref=str(node_map[u]))
        ET.SubElement(way, "nd", ref=str(node_map[v]))
        # At least one standard OSM tag
        ET.SubElement(way, "tag", k="highway", v="road")
        # Dump all other attributes
        for k, v_ in edata.items():
            if v_ is not None:
                ET.SubElement(way, "tag", k=str(k), v=str(v_))
        way_id -= 1

    ET.ElementTree(root).write(filename, encoding="utf-8", xml_declaration=True)


def load_graph_from_osm(filename: str) -> nx.MultiDiGraph:
    """
    Load a graph from an OSM file.

    Parameters:
    -----------
    filename : str
        The path to the OSM file.

    Returns:
    --------
    nx.MultiDiGraph
        The loaded graph.
    """
    G = nx.MultiDiGraph()

    tree = ET.parse(filename)
    root = tree.getroot()

    node_map = {}

    # Read nodes
    for node in root.findall('node'):
        node_id = int(node.get('id'))
        lat = float(node.get('lat'))
        lon = float(node.get('lon'))
        G.add_node(node_id, y=lat, x=lon)
        node_map[node_id] = (lat, lon)

        for tag in node.findall('tag'):
            G.nodes[node_id][tag.get('k')] = tag.get('v')

    # Read ways (edges)
    for way in root.findall('way'):
        nd_refs = [int(nd.get('ref')) for nd in way.findall('nd')]
        for u, v in zip(nd_refs[:-1], nd_refs[1:]):
            # Add edge and get the key for the new edge
            key = G.add_edge(u, v)
            for tag in way.findall('tag'):
                G.edges[u, v, key][tag.get('k')] = tag.get('v')

    return G


def scan_network_directories_for_ways(directory):
    import csv
    import subprocess
    import os

    def calculate_ways(osm_file):
        try:
            # Use osmium to get file info with summary
            result = subprocess.run(['osmium', 'fileinfo', '-e', osm_file],
                                    capture_output=True, text=True)
            # Initialize ways_count variable
            ways_count = 0

            # Extract the number of ways from the output
            for line in result.stdout.splitlines():
                if "Number of ways" in line:
                    ways_count = line.split(":")[1].strip()  # Get the number of ways
                    break  # Stop after finding the count

            return ways_count  # Return the number of ways
        except Exception as e:
            print(f"Error processing {osm_file}: {e}")
        return 0

    output_file = os.path.join(directory, 'ways_count.csv')
    scanned_files = set()

    # Check if output file exists and load already processed files
    if os.path.exists(output_file):
        try:
            with open(output_file, 'r', newline='') as f:
                reader = csv.reader(f)
                next(reader, None)  # Skip header, safely
                for row in reader:
                    if len(row) >= 3:  # Ensure the row has enough columns
                        scanned_files.add(row[2])  # Add scanned file path to the set
        except Exception as e:
            print(f"Error reading existing CSV: {e}")
    else:
        # Create the output file and write the header
        with open(output_file, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['name', 'ways', 'path'])
            print(f"Created output file: {output_file}")

    print(f"Scanning directory: {directory}")  # Log current directory being scanned
    for root, dirs, files in os.walk(directory):
        # Skip archive directories
        if 'archive' in root.lower():
            print(f"Ignoring archive directory: {root}")
            continue

        # Look for the first osm.pbf file using next() with a generator expression
        osm_file_path = next((os.path.join(root, file) for file in files if file.endswith('.osm.pbf')), None)

        if osm_file_path is not None:
            if osm_file_path in scanned_files:
                print(f"PBF file already processed: {osm_file_path}")  # Log already processed directory
                continue
            else:
                # Extract network name from the file name or directory name
                network_name = os.path.basename(root)  # Use the directory name as the network name
                number_of_ways = calculate_ways(osm_file_path)

                # Ensure file ends with newline before appending
                """Ensure the file ends with a newline character."""
                if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
                    with open(output_file, 'rb+') as f:
                        f.seek(-1, os.SEEK_END)  # Go to the last byte
                        last_char = f.read(1)
                        if last_char != b'\n':
                            f.seek(0, os.SEEK_END)  # Go to the end of the file
                            f.write(b'\n')  # Add a newline if it doesn't end with one

                # Append result to the output CSV file
                with open(output_file, 'a', newline='') as f:
                    writer = csv.writer(f)
                    writer.writerow([network_name, number_of_ways, osm_file_path])  # Write network name, number of ways, and path
                    print(f"Appended to CSV: {network_name}, {number_of_ways}, {osm_file_path}")  # Log appended data
        else:
            print(f"No OSM file found in this directory: {root}.")  # Log message if no file found
            continue  # Skip to the next directory if no file is found


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


def check_invalid_coordinates(graph):
    """
    Check for invalid coordinates in the graph nodes.

    Parameters:
    -----------
    graph : networkx.MultiDiGraph
        The graph to check

    Returns:
    --------
    tuple
        (has_invalid, invalid_nodes) where:
        - has_invalid: boolean indicating if any invalid coordinates were found
        - invalid_nodes: list of node IDs with invalid coordinates
    """
    nodes, _ = ox.graph_to_gdfs(graph)

    # Check for NaN, infinite, or out-of-range coordinates
    invalid_x = ~nodes['x'].between(-180, 180) | nodes['x'].isna() | nodes['x'].abs().eq(float('inf'))
    invalid_y = ~nodes['y'].between(-90, 90) | nodes['y'].isna() | nodes['y'].abs().eq(float('inf'))

    # Combine invalid x or y
    invalid_nodes = nodes[invalid_x | invalid_y]

    if len(invalid_nodes) > 0:
        print(f"\nWARNING: Found {len(invalid_nodes)} nodes with invalid coordinates:")
        for idx, node in invalid_nodes.iterrows():
            print(f"  Node ID: {idx}, x: {node['x']}, y: {node['y']}")
        return True, invalid_nodes.index.tolist()

    return False, []

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
