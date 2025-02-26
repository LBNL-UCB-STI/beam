import os
import time
import xml.etree.ElementTree as ET
from statistics import median

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


def collect_geographic_boundaries(state_fips_code, county_fips_codes, year, study_area_geo_path,
                                  projected_coordinate_system, geo_level):
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

    study_area_geo_projected_path = base_name + "_epsg" + str(projected_coordinate_system) + extension
    selected_geo.to_crs(epsg=projected_coordinate_system).to_file(study_area_geo_projected_path, driver="GeoJSON")

    selected_geo_wgs84 = selected_geo.to_crs(epsg=4326)
    selected_geo_wgs84.to_file(base_name + "_wgs84" + extension, driver="GeoJSON")
    return selected_geo_wgs84


def collect_tract_boundaries_ppsk(
        state_fips_code,
        county_fips_codes,
        year,
        projected_coordinate_system,
        census_data_file,
        tract_boundaries_geo_file
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
     tract_boundaries_geo_file: GeoJSON
         Path to the GeoJSON file containing tract boundaries in WGS84 projection
     census_data_file: CSV
         Path to the CSV file containing population density data

     Returns
     -------
     geopandas.GeoDataFrame
         Selected tract boundaries in WGS84 projection

     Notes
     -----
     Population estimates are from the Census Bureau's ACS 5-year estimates.
     """

    if not os.path.exists(census_data_file):
        from cenpy import products
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

    if not os.path.exists(tract_boundaries_geo_file):
        # Get tract boundaries using TIGER/Line shapefiles
        try:
            # Download geographic boundaries
            geo_url = f"https://www2.census.gov/geo/tiger/TIGER{year}/TRACT/tl_{year}_{state_fips_code}_tract.zip"
            geo_data = gpd.read_file(geo_url)

            # Filter for counties of interest
            geo_data = geo_data[geo_data['COUNTYFP'].isin(county_fips_codes)]

            geo_data.to_file(tract_boundaries_geo_file, driver='GeoJSON')
        except Exception as e:
            print(f"Failed to retrieve geographic boundaries: {e}")
            raise
    else:
        geo_data = gpd.read_file(tract_boundaries_geo_file)

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


def process_ferry_into_car_edges(car_graph, region_polygon) -> nx.MultiDiGraph:
    g_ferry = ox.graph_from_polygon(region_polygon, network_type="all", simplify=True,
                                    custom_filter='["route"="ferry"]["motor_vehicle"="yes"]', retain_all=True)
    g_all_ferry = ox.graph_from_polygon(region_polygon, network_type="all", simplify=True,
                                        custom_filter='["route"="ferry"]["motorcar"="yes"]', retain_all=True)
    g_ferry = nx.compose_all([g_ferry, g_all_ferry])
    ferry_nodes, ferry_edges = ox.graph_to_gdfs(g_ferry)
    ferry_edges['reversed'] = False
    ferry_edges['maxspeed'] = "10 mph"
    ferry_edges['highway'] = "unclassified"
    ferry_edges['oneway'] = "no"
    ferry_edges['lanes'] = "2"
    ferry_edges["hgv"] = False
    ferry_edges["mdv"] = True
    nodes, edges = ox.graph_to_gdfs(car_graph)
    for col in edges.columns:
        if col not in ferry_edges.columns:
            ferry_edges[col] = "nan"
    g_ferry_reconstructed = ox.graph_from_gdfs(ferry_nodes, ferry_edges)
    return nx.compose_all([car_graph, g_ferry_reconstructed])


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
    """Return 'yes' only for 'yes'/'true'/'1', otherwise 'no'"""
    valid_yes = {'yes', 'true', '1'}
    if isinstance(value, list):
        return 'yes' if value and all(str(v).lower().strip() in valid_yes for v in value) else 'no'
    return 'yes' if value and str(value).lower().strip() in valid_yes else 'no'


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
    # Convert back to MultiDiGraph
    g_updated = ox.graph_from_gdfs(nodes, edges)

    return g_updated


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
