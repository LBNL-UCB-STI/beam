import multiprocessing as mp
import os
import sys
import random
import warnings
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Tuple, List
import geopandas as gpd
import numpy as np
import pandas as pd
from pandas import DataFrame
from pyrosm import OSM
from scipy.spatial import cKDTree
from shapely.geometry import Point

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name
from python.utils.study_area_config import constants

warnings.filterwarnings('ignore')

# ************************************************************************************************

AREA = "sfbay" # sfbay
BATCH_NAME = "2024-01-23"
SCENARIO_NAME = "Baseline"
SCENARIO_SUFFIX = ""
FRISM_VERSION = 1.0
# Coordinate snapping constants
BUFFER_DISTANCE_METERS = 100  # 100 meters
MAX_DISTANCE_METERS = 200000  # 200km
STUDY_AREA_CONFIG = get_area_config(AREA)
STUDY_AREA_CONFIG["network"]["graph_layers"]["residential"]["min_density_per_km2"] = 5500
SNAP_COORDINATES = True

# ************************************************************************************************




# System and general constants
CHUNK_SIZE = 10000  # this affects speed and parallelization of the script
CONFIG_NAME = generate_network_name(STUDY_AREA_CONFIG)
NETWORK_DIR = f'{STUDY_AREA_CONFIG["work_dir"]}/network/{CONFIG_NAME}'
NETWORK_OSM_PBF = f'{NETWORK_DIR}/{CONFIG_NAME}.osm.pbf'
UTM_CRS = STUDY_AREA_CONFIG["geo"]["utm_epsg"]
YEAR = STUDY_AREA_CONFIG["census_year"]
SCENARIO_LABEL = SCENARIO_NAME.replace("_", "")
PRIMARY_ENERGY_PROFILE = STUDY_AREA_CONFIG["fastsim_routee_files"]["primary_powertrain"]
SECONDARY_ENERGY_PROFILE = STUDY_AREA_CONFIG["fastsim_routee_files"]["secondary_powertrain"]

# File paths and directories
DIRECTORY_INPUT = f'{STUDY_AREA_CONFIG["work_dir"]}/frism/{BATCH_NAME}/{SCENARIO_NAME}'
DIRECTORY_BATCH = f'{STUDY_AREA_CONFIG["work_dir"]}/beam-ft/{BATCH_NAME}'
DIRECTORY_OUTPUT = f'{DIRECTORY_BATCH}/{YEAR}_{SCENARIO_LABEL}{SCENARIO_SUFFIX}'
DIRECTORY_VEHICLE_TECH = f'{STUDY_AREA_CONFIG["work_dir"]}/vehicle-tech'
DIRECTORY_SCENARIO = f'{DIRECTORY_OUTPUT}'
# if SNAP_COORDINATES:
#     # Define the snapped directory path
#     DIRECTORY_SCENARIO = f'{DIRECTORY_OUTPUT}--snapped-to-{CONFIG_NAME}'
# else:
#     DIRECTORY_SCENARIO = f'{DIRECTORY_OUTPUT}'

# Create necessary directories if they don't exist
Path(DIRECTORY_SCENARIO).mkdir(parents=True, exist_ok=True)
Path(DIRECTORY_VEHICLE_TECH).mkdir(parents=True, exist_ok=True)

# Variables
_carriers = None
_payload_plans = None
_ondemand_plans = None
_tours = None
_vehicle_types = None
_tourId_with_prefix = {}

# ******************************

def load_osm_network(pbf_path, min_distance_from_edge):
    """
    Load OSM network and create/load buffered network with proper metric distances

    Args:
        pbf_path (str): Path to original OSM PBF file
        min_distance_from_edge (float): Buffer distance in meters

    Returns:
        gpd.GeoDataFrame: Network edges with original and buffered geometries

    Raises:
        ValueError: If the PBF file doesn't exist or if network extraction fails
    """
    # Input validation
    if not os.path.exists(pbf_path):
        raise ValueError(f"PBF file not found: {pbf_path}")

    print(f"Loading OSM network from {pbf_path}...")
    try:
        osm = OSM(pbf_path)
        edges = osm.get_network(network_type="driving")
    except Exception as e:
        raise ValueError(f"Failed to load OSM network: {str(e)}")

    # Ensure we have a GeoDataFrame
    if not isinstance(edges, gpd.GeoDataFrame):
        edges = gpd.GeoDataFrame(edges)

    if edges.empty:
        raise ValueError("No network edges found in the PBF file")

    print(f"Creating {str(int(BUFFER_DISTANCE_METERS / 1000))}km road buffer...")
    # Convert to UTM for proper metric distances
    try:
        edges_utm = edges.to_crs(epsg=UTM_CRS)
    except Exception as e:
        raise ValueError(f"Failed to convert to UTM (EPSG:{UTM_CRS}): {str(e)}")

    # Create buffer in UTM coordinates (where distances are in meters)
    buffered_edges = edges_utm.copy()
    buffered_edges['geometry'] = edges_utm['geometry'].buffer(
        min_distance_from_edge,
        cap_style=2,  # flat ends
        join_style=2  # mitered joins
    )

    # Add buffered geometry as a new column
    edges_utm['buffered_geometry'] = buffered_edges.geometry

    # Create buffered pbf if it doesn't exist
    path_without_ext, ext = os.path.splitext(pbf_path)
    buffer_path = f"{path_without_ext}_{str(int(BUFFER_DISTANCE_METERS / 1000))}km_road_buffer.geojson"

    # Check if file exists and handle overwriting
    if os.path.exists(buffer_path):
        try:
            os.remove(buffer_path)
            print(f"Removed existing file: {buffer_path}")
        except Exception as e:
            print(f"Warning: Failed to remove existing file: {str(e)}")

    # Save buffered network
    try:
        # Save the buffered edges as GeoJSON
        # Convert to geographic coordinates (EPSG:4326) for better compatibility
        save_gdf = buffered_edges.to_crs(epsg=4326)

        # Make sure all columns are serializable
        for col in save_gdf.columns:
            if save_gdf[col].dtype == 'object':
                save_gdf[col] = save_gdf[col].astype(str)

        # Save to GeoJSON
        save_gdf.to_file(buffer_path, driver='GeoJSON')
        print(f"Saved buffered network to: {buffer_path}")
    except Exception as e:
        print(f"Warning: Failed to save buffered network: {str(e)}")
        raise e

    return edges_utm


def generate_random_point_near_line(
        nearest_edge: gpd.GeoSeries,
        point_geom: Point,
        max_dist_meters: float
) -> Tuple[float, float]:
    """
    Generate a random point within max_dist_meters of the nearest point on the road

    Args:
        nearest_edge: GeoSeries row containing the road geometry
        point_geom: Original point (Shapely Point)
        max_dist_meters: Maximum distance from road (e.g., 200)

    Returns:
        Tuple of (x, y) coordinates for the new random point
    """
    # Find nearest point on the road
    proj_distance = nearest_edge.geometry.project(point_geom)
    nearest_point = nearest_edge.geometry.interpolate(proj_distance)

    # Generate random angle and distance
    angle = random.uniform(0, 2 * np.pi)  # Random angle between 0 and 2π
    distance = random.uniform(0, max_dist_meters)  # Random distance up to max

    # Convert to x,y offset
    dx = distance * np.cos(angle)
    dy = distance * np.sin(angle)

    # Create new point
    new_x = nearest_point.x + dx
    new_y = nearest_point.y + dy

    return new_x, new_y


def read_csv_file(filename_):
    compression = None
    if filename_.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(filename_, sep=",", index_col=None, header=0, compression=compression)


def add_prefix(prefix, column, row, to_num=True, store_dict=None, veh_type=False, suffix=""):
    str_value = str(row[column])
    if to_num and str_value.isnumeric():
        old = str(int(row[column]))
    else:
        old = str(row[column])
    if veh_type:
        old_updated = old.replace('_', '-').replace('b2b-', '').replace('b2c-', ''). \
            replace('Battery Electric', 'BE').replace('H2 Fuel Cell', 'H2FC')
    else:
        old_updated = old.lower().replace('_', '-').replace('b2b-', '').replace('b2c-', '')
    second_prefix = ''
    # if veh_type:
    #     if old == '1':
    #         second_prefix = '-MD-'
    #     else:
    #         second_prefix = '-HD-'
    first_prefix = prefix
    if 'county' in prefix:
        first_prefix = first_prefix.replace('county', 'cty')

    new = f"{first_prefix}{second_prefix}{old_updated}{suffix}"
    if store_dict is not None:
        store_dict[old] = new
    return new


def format_payload(_payload_plans: pd.DataFrame) -> pd.DataFrame:
    """
    Format payload and adjust coordinates where needed using road buffer for efficiency

    Args:
        _payload_plans (DataFrame): Input payload data

    Returns:
        DataFrame: Formatted payload data
    """
    # Rename columns and convert data types
    payload_plans_renames = {
        'arrivalTimeWindowInSec_lower': 'arrivalTimeWindowInSecLower',
        'arrivalTimeWindowInSec_upper': 'arrivalTimeWindowInSecUpper',
        'locationZone_x': 'locationX',
        'locationZone_y': 'locationY',
        'true_locationZone': 'mesoZone',
        'BuyerNAICS': "buyerNAICS",
        "SellerNAICS": "sellerNAICS"
    }
    _payload_plans.rename(columns=payload_plans_renames, inplace=True)

    int_columns = [
        'sequenceRank', 'payloadType', 'requestType', 'estimatedTimeOfArrivalInSec',
        'arrivalTimeWindowInSecLower', 'arrivalTimeWindowInSecUpper',
        'operationDurationInSec', 'locationZone'
    ]
    _payload_plans[int_columns] = _payload_plans[int_columns].astype(int)

    # Map payload types and process weights
    payload_type_map = {
        1: 'bulk',
        2: 'fuel_fert',
        3: 'interm_food',
        4: 'mfr_goods',
        5: 'others'
    }
    _payload_plans['payloadType'] = _payload_plans['payloadType'].map(payload_type_map)
    _payload_plans['weightInKg'] = _payload_plans['weightInlb'].astype(float) * 0.45359237

    # Handle different FRISM versions
    if FRISM_VERSION > 1.0:
        _payload_plans['deliveryType'] = _payload_plans['requestType'].map({
            1: 'delivery-only',
            3: 'pickup-delivery'
        })
        _payload_plans['requestType'] = _payload_plans['requestType'].astype('object')
        _payload_plans.loc[_payload_plans['weightInKg'] < 0, 'requestType'] = 'unloading'
        _payload_plans.loc[_payload_plans['weightInKg'] >= 0, 'requestType'] = 'loading'
        _payload_plans['weightInKg'] = np.abs(_payload_plans['weightInKg'])

        _payload_plans['fleetType'] = _payload_plans['truck_mode'].map({
            'Private Truck': 'private',
            'For-hire Truck': 'for-hire'
        }, na_action='ignore')
    else:
        # _payload_plans['requestType'] = _payload_plans['requestType'].map({1: 'unloading', 0: 'loading'})
        _payload_plans.loc[_payload_plans['weightInKg'] < 0, 'requestType'] = 'unloading'
        _payload_plans.loc[_payload_plans['weightInKg'] >= 0, 'requestType'] = 'loading'
        _payload_plans['weightInKg'] = np.abs(_payload_plans['weightInKg'])

    # Clean up unnecessary columns
    payload_plans_drop = ['truck_mode', 'weightInlb', 'cummulativeWeightInlb', 'index']
    _payload_plans.drop(payload_plans_drop, axis=1, inplace=True, errors='ignore')

    return _payload_plans


## ################################
## Snapping coordinates section

def create_spatial_index_kdtree(edges_gdf_utm: gpd.GeoDataFrame) -> Tuple[np.ndarray, cKDTree]:
    """Create KD-tree spatial index from UTM coordinates for faster nearest neighbor queries"""
    # Extract centroids of line segments in UTM coordinates
    centroids = np.array([[geom.centroid.x, geom.centroid.y] for geom in edges_gdf_utm.geometry])
    return centroids, cKDTree(centroids)


def find_nearest_edge_kdtree(
        point_utm: Point,
        edges_gdf_utm: gpd.GeoDataFrame,
        kdtree: cKDTree,
        k: int = 5
) -> Tuple[float, gpd.GeoSeries]:
    """
    Find nearest edge using KD-tree with vectorized distance calculations in UTM coordinates

    Args:
        point_utm: Point geometry in UTM coordinates
        edges_gdf_utm: GeoDataFrame containing network edges in UTM
        kdtree: cKDTree spatial index
        k: Number of nearest neighbors to check

    Returns:
        Tuple of (minimum distance in meters, nearest edge)
    """
    # Find k nearest neighbors using KD-tree
    distances, indices = kdtree.query([point_utm.x, point_utm.y], k=k)

    # Calculate actual distances to the k nearest edges in meters (UTM)
    candidate_edges = edges_gdf_utm.iloc[indices]
    actual_distances = candidate_edges.geometry.distance(point_utm)

    min_idx = actual_distances.idxmin()
    return actual_distances.min(), edges_gdf_utm.loc[min_idx]


def generate_random_point_near_line_utm(
        nearest_edge_utm: gpd.GeoSeries,
        point_utm: Point,
        max_dist_meters: float
) -> Tuple[float, float]:
    """
    Generate a random point within max_dist_meters of the nearest point on the road in UTM coordinates
    """
    # Find nearest point on the road
    proj_distance = nearest_edge_utm.geometry.project(point_utm)
    nearest_point = nearest_edge_utm.geometry.interpolate(proj_distance)

    # Generate random angle and distance
    angle = np.random.uniform(0, 2 * np.pi)
    distance = np.random.uniform(0, max_dist_meters)

    # Convert to x,y offset (in meters since we're in UTM)
    dx = distance * np.cos(angle)
    dy = distance * np.sin(angle)

    # Create new UTM coordinates
    new_x_utm = nearest_point.x + dx
    new_y_utm = nearest_point.y + dy

    return new_x_utm, new_y_utm


def process_points_chunk_vectorized(
        points_chunk: np.ndarray,
        edges_gdf_utm: gpd.GeoDataFrame,
        kdtree: cKDTree,
        min_distance: float,
        max_distance: float,
        chunk_start_idx: int,
        coordinate_lookup: dict
) -> List[Tuple[int, float, float, bool, bool]]:
    """
    Process a chunk of points using vectorized operations with proper CRS handling

    Args:
        points_chunk: Array of coordinate pairs to process
        edges_gdf_utm: GeoDataFrame containing network edges in UTM
        kdtree: Spatial index for quick nearest neighbor lookups
        min_distance: Minimum allowed distance from road
        max_distance: Maximum allowed distance from road
        chunk_start_idx: Starting index of current chunk
        coordinate_lookup: Dictionary storing previously processed coordinates
    """
    results = []
    cache_hits = 0

    # Convert input points to UTM for distance calculations
    points_gdf = gpd.GeoDataFrame(
        geometry=[Point(x, y) for x, y in points_chunk],
        crs=4326
    ).to_crs(UTM_CRS)

    for idx, (point_utm, orig_point) in enumerate(zip(points_gdf.geometry, points_chunk)):
        try:
            # Check lookup table first
            coord_key = (orig_point[0], orig_point[1])
            if coord_key in coordinate_lookup:
                cached_result = coordinate_lookup[coord_key]
                results.append((
                    chunk_start_idx + idx,
                    cached_result[0],
                    cached_result[1],
                    cached_result[2],
                    cached_result[3]
                ))
                cache_hits += 1
                continue

            # Find nearest edge using UTM coordinates
            min_dist, nearest_edge_utm = find_nearest_edge_kdtree(point_utm, edges_gdf_utm, kdtree)

            is_far = min_dist > max_distance
            needs_adjustment = min_dist > min_distance and not is_far

            if needs_adjustment:
                # Generate new point in UTM coordinates
                new_x_utm, new_y_utm = generate_random_point_near_line_utm(
                    nearest_edge_utm,
                    point_utm,
                    min_distance
                )

                # Convert back to original CRS (WGS84)
                point_updated = gpd.GeoDataFrame(
                    geometry=[Point(new_x_utm, new_y_utm)],
                    crs=UTM_CRS
                ).to_crs(4326).geometry[0]

                result = (
                    chunk_start_idx + idx,
                    point_updated.x,
                    point_updated.y,
                    is_far,
                    True
                )
            else:
                result = (
                    chunk_start_idx + idx,
                    orig_point[0],
                    orig_point[1],
                    is_far,
                    False
                )

            # Store in lookup table
            coordinate_lookup[coord_key] = result[1:]
            results.append(result)

        except Exception as e:
            print(f"Warning: Error processing point {chunk_start_idx + idx}: {str(e)}")
            results.append((
                chunk_start_idx + idx,
                orig_point[0],
                orig_point[1],
                False,
                False
            ))

    if cache_hits > 0:
        print(f"Cache hits in chunk: {cache_hits}/{len(points_chunk)}")
    return results


def snap_coordinates_when_too_far(_df: pd.DataFrame,
                                  osm_edges_utm: gpd.GeoDataFrame,
                                  x_column: str,
                                  y_column: str,
                                  coordinate_lookup: dict = None) -> tuple[pd.DataFrame, dict]:
    """
    Optimized version of coordinate snapping using KD-tree spatial indexing and lookup table

    Args:
        _df: DataFrame with coordinate columns in WGS84
        osm_edges_utm: GeoDataFrame with network in UTM
        x_column: Name of the column containing X coordinates (longitude)
        y_column: Name of the column containing Y coordinates (latitude)
        coordinate_lookup: Optional existing lookup table to use

    Returns:
        Tuple of (DataFrame with snapped coordinates in WGS84, coordinate lookup dictionary)
    """
    min_distance_from_edge = BUFFER_DISTANCE_METERS
    max_distance_from_edge = MAX_DISTANCE_METERS

    if coordinate_lookup is None:
        coordinate_lookup = {}
        print("Creating new coordinate lookup table...")
    else:
        print(f"Using existing lookup table with {len(coordinate_lookup)} entries...")

    print("Creating KD-tree spatial index...")
    centroids, kdtree = create_spatial_index_kdtree(osm_edges_utm)

    # Extract coordinates in original CRS (WGS84)
    coords = np.column_stack((
        _df[x_column].values,
        _df[y_column].values
    ))

    # Calculate optimal chunk size based on available CPU cores
    num_cores = max(1, mp.cpu_count() - 1)
    chunk_size = min(CHUNK_SIZE, max(1000, len(coords) // (num_cores * 2)))
    n_chunks = (len(coords) + chunk_size - 1) // chunk_size

    print(f"Processing {len(coords)} points in {n_chunks} chunks using {num_cores} cores...")

    all_results = []
    far_points = 0
    total_adjusted = 0

    # Process chunks in parallel using ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=num_cores) as executor:
        futures = []

        for chunk_idx in range(n_chunks):
            start_idx = chunk_idx * chunk_size
            end_idx = min((chunk_idx + 1) * chunk_size, len(coords))
            chunk_coords = coords[start_idx:end_idx]

            future = executor.submit(
                process_points_chunk_vectorized,
                chunk_coords,
                osm_edges_utm,
                kdtree,
                min_distance_from_edge,
                max_distance_from_edge,
                start_idx,
                coordinate_lookup
            )
            futures.append(future)

        # Collect results as they complete
        for future in as_completed(futures):
            try:
                results = future.result()
                for _, _, _, is_far, is_adjusted in results:
                    if is_far:
                        far_points += 1
                    if is_adjusted:
                        total_adjusted += 1
                all_results.extend(results)
            except Exception as e:
                print(f"Error processing chunk: {str(e)}")

    if far_points > 0:
        print(f"Warning: {far_points} points are farther than {int(max_distance_from_edge / 1000)} km from any road")
    if total_adjusted > 0:
        print(f"Adjusted {total_adjusted} points to be within {int(min_distance_from_edge / 1000)} km of nearest road")

    # Sort results and update DataFrame efficiently
    all_results.sort(key=lambda r: r[0])
    result_indices = [r[0] for r in all_results]
    x_coords = [r[1] for r in all_results]
    y_coords = [r[2] for r in all_results]

    result_df = _df.copy()
    result_df[x_column] = pd.Series(x_coords, index=result_indices)
    result_df[y_column] = pd.Series(y_coords, index=result_indices)

    return result_df, coordinate_lookup


def get_base_duration(df):
    # Create bins for cargo weights (PU and DO)
    df['cargoWeightPU_bin'] = pd.cut(
        df['cargoWeightPU'],
        bins=[0, 100, 500, 1000, 2000, 5000, 10000, float('inf')],
        labels=['0-100kg', '100-500kg', '500-1000kg', '1-2tons', '2-5tons', '5-10tons', '10+tons']
    )

    df['cargoWeightDO_bin'] = pd.cut(
        df['cargoWeightDO'],
        bins=[0, 100, 500, 1000, 2000, 5000, 10000, float('inf')],
        labels=['0-100kg', '100-500kg', '500-1000kg', '1-2tons', '2-5tons', '5-10tons', '10+tons']
    )

    # Group by vehicle class, activity type, and weight bins
    groupby_columns = ['vehicleClass', 'activityType', 'cargoWeightPU_bin', 'cargoWeightDO_bin']

    # Group and calculate average operation duration
    grouped_data = df.groupby(groupby_columns)['operationDurationInMin'].agg(['mean', 'count']).reset_index()

    print("\nGrouped data summary:")
    print(f"Total groups: {len(grouped_data)}")

    # Find the minimum average duration for each vehicle class across all combinations
    vehicle_classes = grouped_data['vehicleClass'].unique()
    min_durations = {}
    for vehicle_class in vehicle_classes:
        class_data = grouped_data[grouped_data['vehicleClass'] == vehicle_class]
        min_duration = class_data['mean'].min()
        min_durations[vehicle_class] = min_duration
        print(f"{vehicle_class}: Minimum average duration = {min_duration:.2f} minutes")

        # Show the specific combination that resulted in the minimum
        min_idx = class_data['mean'].idxmin()
        min_row = class_data.iloc[min_idx]
        print(f"  Combination: {min_row[groupby_columns].to_dict()}")
        print(f"  Count: {min_row['count']} records")

    # Convert minutes to seconds for the base_duration dictionary
    min_durations_in_sec = {}
    for vehicle_class, min_duration in min_durations.items():
        # Convert to seconds and round to nearest minute
        min_durations_in_sec[vehicle_class] = int(round(min_duration * 60 / 60) * 60)

    return min_durations_in_sec


def get_weight_factor(df, base_duration):
    """
    Calculate weight factor for each vehicle class based on the relationship between
    cargo weight and operation duration in the survey data.
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Group by vehicle class
    vehicle_classes = df_copy['vehicleClass'].unique()
    weight_factors = {}

    for vehicle_class in vehicle_classes:
        class_data = df_copy[df_copy['vehicleClass'] == vehicle_class]
        loading_df = class_data[class_data['activityType'] == 'Pick up Cargo']
        unloading_df = class_data[class_data['activityType'] == 'Delivery of Cargo']

        # Set effective weight for each activity type
        loading_df['effectiveWeight'] = loading_df['cargoWeightPU']
        unloading_df['effectiveWeight'] = unloading_df['cargoWeightDO']

        # Combine datasets for weight factor calculation
        combined_df = pd.concat([loading_df, unloading_df])
        combined_df = combined_df[combined_df['effectiveWeight'] > 0]

        # Get base duration (convert seconds to minutes)
        base = base_duration[vehicle_class] / 60

        # Calculate individual factors for each record
        combined_df['individual_factor'] = (combined_df['operationDurationInMin'] - base) / combined_df['effectiveWeight']

        # Use median to avoid remaining outliers
        median_factor = combined_df['individual_factor'].median()

        # Convert from minutes/kg to seconds/kg
        weight_factors[vehicle_class] = max(0, median_factor * 60)


    print("\nWeight factors:")
    for vehicle_class, factor in weight_factors.items():
        print(f"{vehicle_class}: {factor:.6f} seconds per kg")

    return weight_factors


def get_operation_factor(df):
    """
    Calculate operation factor (loading vs unloading) for each vehicle class.
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Group and calculate average operation duration normalized by weight
    grouped_data = df_copy.groupby(['vehicleClass', 'activityType'])['operationDurationInMin'].mean().reset_index()

    # Calculate operation factors relative to unloading
    operation_factors = {}
    vehicle_classes = grouped_data['vehicleClass'].unique()

    for vehicle_class in vehicle_classes:
        class_data = grouped_data[grouped_data['vehicleClass'] == vehicle_class]
        loading_df = class_data[class_data['activityType'] == 'Pick up Cargo']
        unloading_df = class_data[class_data['activityType'] == 'Delivery of Cargo']

        loading_duration = loading_df['operationDurationInMin'].values[0]
        unloading_duration = unloading_df['operationDurationInMin'].values[0]

        loading_ratio = loading_duration / (loading_duration+unloading_duration)
        unloading_ratio = 1 - loading_ratio

        operation_factors[vehicle_class] = {
            'loading': 2 * loading_ratio,
            'unloading': 2 * unloading_ratio
        }

    print("\nOperation factors:")
    for vehicle_class, factors in operation_factors.items():
        print(f"{vehicle_class}: loading = {factors['loading']:.2f}, unloading = {factors['unloading']:.2f}")

    return operation_factors


def extract_weight_bins(df, num_bins=7):
    """
    Extract weight bins from the data dynamically.

    Args:
        df: DataFrame with weight data
        num_bins: Number of bins to create

    Returns:
        Dictionary mapping (lower, upper) bounds to bin labels
    """
    # Combine pickup and delivery weights
    weights = []
    for _, row in df.iterrows():
        if row['activityType'] == 'Pick up Cargo' and row['cargoWeightPU'] > 0:
            weights.append(row['cargoWeightPU'])
        elif row['activityType'] == 'Delivery of Cargo' and row['cargoWeightDO'] > 0:
            weights.append(row['cargoWeightDO'])

    # Remove extreme outliers to prevent skewing the bin boundaries
    weights = np.array(weights)
    q1, q3 = np.percentile(weights, [25, 75])
    iqr = q3 - q1
    lower_bound = q1 - 1.5 * iqr
    upper_bound = q3 + 1.5 * iqr
    filtered_weights = weights[(weights >= max(0, lower_bound)) & (weights <= upper_bound)]

    # Use percentile-based bins to ensure even distribution of data
    percentiles = np.linspace(0, 100, num_bins + 1)
    bin_edges = np.percentile(filtered_weights, percentiles)

    # Round bin edges for better readability
    bin_edges = np.unique([round(edge, -1) for edge in bin_edges])

    # Ensure the bins are strictly increasing
    bin_edges = np.unique(bin_edges)
    if bin_edges[0] > 0:
        bin_edges = np.insert(bin_edges, 0, 0)
    if bin_edges[-1] != float('inf'):
        bin_edges = np.append(bin_edges, float('inf'))

    # Create weight_dict
    weight_dict = {}
    for i in range(len(bin_edges) - 1):
        lower = bin_edges[i]
        upper = bin_edges[i + 1]

        # Format the label based on weight magnitude
        if upper < 1000:
            label = f"{int(lower)}-{int(upper)}lb"
        elif upper < 10000:
            label = f"{int(lower / 1000)}k-{int(upper / 1000)}klb"
        elif upper == float('inf'):
            label = f"{int(lower / 1000)}k+lb"
        else:
            label = f"{int(lower / 1000)}k-{int(upper / 1000)}klb"

        # For the last bin, use infinity
        if i == len(bin_edges) - 2:
            label = f"{int(lower)}+lb"

        weight_dict[(lower, upper)] = label

    return weight_dict


def get_weight_bin(weight_value, weight_dict):
    """
    Determine the weight bin for a given weight value.

    Args:
        weight_value: The weight value
        weight_dict: Dictionary mapping (lower, upper) bounds to bin labels

    Returns:
        The bin label for the weight value
    """
    for (lower, upper), label in weight_dict.items():
        if lower <= weight_value < upper:
            return label

    # Fallback for any value not covered (should not happen with properly defined bins)
    return list(weight_dict.values())[-1]  # Return the highest bin


def build_operation_duration_model(df, weight_dict, operation_dict):
    """
    Build a decision tree model for operation durations:
    Vehicle Class -> Operation Type -> Weight Bin -> Duration Distribution
    Weight is in pounds (lbs)
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Apply the weight bin function to classify each record
    df_copy['weight_bin'] = df_copy.apply(
        lambda row: get_weight_bin(
            row['cargoWeightPU'] if row['activityType'] == 'Pick up Cargo' else row['cargoWeightDO'],
            weight_dict
        ),
        axis=1
    )

    # Build the nested model structure
    model = {}

    # First level: Vehicle Class
    vehicle_classes = df_copy['vehicleClass'].unique()

    for vehicle_class in vehicle_classes:
        model[vehicle_class] = {}
        class_data = df_copy[df_copy['vehicleClass'] == vehicle_class]

        # Second level: Operation Type
        # operation_types = {
        #     'Pick up Cargo': 'loading',
        #     'Delivery of Cargo': 'unloading'
        # }

        for original_op_type, standard_op_type in operation_dict.items():
            model[vehicle_class][standard_op_type] = {}
            op_data = class_data[class_data['activityType'] == original_op_type]

            if len(op_data) == 0:
                continue

            # Third level: Weight Bins
            for weight_bin in op_data['weight_bin'].unique():
                bin_data = op_data[op_data['weight_bin'] == weight_bin]

                if len(bin_data) > 0:
                    # Store the distribution of operation durations
                    durations = bin_data['operationDurationInMin'].values

                    # Store basic statistics and the full distribution
                    model[vehicle_class][standard_op_type][weight_bin] = {
                        'count': len(durations),
                        'mean': np.mean(durations),
                        'median': np.median(durations),
                        'std': np.std(durations),
                        'min': np.min(durations),
                        'max': np.max(durations),
                        'durations': durations.tolist()  # Store all values for sampling
                    }

    return model


def sample_operation_duration(model, weight_dict, vehicle_class, operation_type, weight_lbs, fallback_duration=30):
    """
    Sample an operation duration from the model based on vehicle class, operation type, and weight.

    Args:
        model: The nested model structure
        weight_dict: Dictionary mapping weight ranges to bin labels
        vehicle_class: The vehicle class (e.g., 'Class456Vocational')
        operation_type: Either 'loading' or 'unloading'
        weight_lbs: The weight in pounds (lbs)

    Returns:
        Duration in minutes
    """
    # Determine weight bin
    weight_bin = get_weight_bin(weight_lbs, weight_dict)

    # Handle missing weight bin
    if weight_bin not in model[vehicle_class][operation_type]:
        print(f"Warning: Weight bin '{weight_bin}' not found for {vehicle_class}/{operation_type}, finding closest.")

        # Find the closest weight bin that has data
        available_bins = list(model[vehicle_class][operation_type].keys())
        bin_midpoints = {}
        for bin_label in available_bins:
            # Extract approximate midpoint from bin names
            # This is an approximation that works with our naming convention
            if '-' in bin_label:
                parts = bin_label.replace('lb', '').replace('k', '000').split('-')
                try:
                    lower = float(parts[0])
                    upper = float(parts[1])
                    bin_midpoints[bin_label] = (lower + upper) / 2
                except:
                    bin_midpoints[bin_label] = 0
            elif '+' in bin_label:
                try:
                    lower = float(bin_label.replace('+lb', '').replace('k', '000'))
                    bin_midpoints[bin_label] = lower * 1.5  # Approximation for "+" bins
                except:
                    bin_midpoints[bin_label] = float('inf')

        # Find bin with closest midpoint to our weight
        closest_bin = min(available_bins, key=lambda x: abs(bin_midpoints.get(x, 0) - weight_lbs))
        weight_bin = closest_bin

    # Get the distribution for this combination
    distribution = model[vehicle_class][operation_type][weight_bin]

    # Sample a duration
    if distribution['count'] > 0:
        # If we have multiple values, randomly sample from the actual distribution
        if distribution['count'] > 1:
            duration = random.choice(distribution['durations'])
        else:
            # Just one value, use it directly
            duration = distribution['durations'][0]

        # Add some random variation (±10%)
        variation_factor = random.uniform(0.9, 1.1)
        duration = duration * variation_factor

        # Round to nearest minute
        duration = round(duration)

        return duration
    else:
        return fallback_duration


def update_operation_duration(austin_survey, payloads, tours, carriers, vehicle_types):
    """
    Update operation durations based on a decision tree model.
    Handles weights in pounds (lbs).
    """
    # Build the model from survey data
    duration_model = build_operation_duration_model(austin_survey)

    # Print model statistics
    print("Operation Duration Model Summary:")
    for vehicle_class in duration_model:
        print(f"\nVehicle Class: {vehicle_class}")
        for op_type in duration_model[vehicle_class]:
            print(f"  Operation Type: {op_type}")
            for weight_bin in duration_model[vehicle_class][op_type]:
                stats = duration_model[vehicle_class][op_type][weight_bin]
                print(
                    f"    {weight_bin}: {stats['count']} records, mean={stats['mean']:.1f}min, std={stats['std']:.1f}min")

    # Create a copy to avoid modifying the original DataFrame
    updated_payloads = payloads.copy()

    # Merge tours with carriers and vehicle_types to get vehicle information
    tours_with_vehicle = tours.merge(
        carriers,
        on='tourId',
        how='left'
    )

    # Now merge with vehicle_types
    tours_with_vehicle = tours_with_vehicle.merge(
        vehicle_types,
        on='vehicleTypeId',
        how='left'
    )

    tours_with_vehicle = tours_with_vehicle.drop_duplicates(subset=['tourId', 'vehicleTypeId'], keep='first')

    # Then, merge payloads with the combined tours/vehicle data to get vehicle info for each payload
    payload_with_vehicle = updated_payloads.merge(
        tours_with_vehicle[['tourId', 'vehicleCategory']],
        on='tourId',
        how='left'
    )

    # Map requestType to operation_type
    operation_type_map = {
        'loading': 'loading',
        'unloading': 'unloading',
        # Add more mappings if needed
    }

    # Calculate updated durations
    def calculate_duration(row):
        # The weight is in lbs, so we use it directly
        weight_lbs = abs(row['weightInKg'])  # Assuming weightInKg is actually in lbs despite the column name
        category = row['vehicleCategory']
        operation_type = operation_type_map.get(row['requestType'], 'loading')

        # Sample from the model
        duration_min = sample_operation_duration(duration_model, category, operation_type, weight_lbs)

        # Convert to seconds
        return duration_min * 60

    # Apply the calculation to each row
    payload_with_vehicle['operationDurationInSec'] = payload_with_vehicle.apply(calculate_duration, axis=1)

    updated_columns = payloads.columns.tolist()
    return payload_with_vehicle[updated_columns]


#############################
## MAIN

if __name__ == '__main__':
    # Add these at the beginning of your main code, after the variables section
    # Dictionary to store vehicle class and fuel rate mappings
    vehicle_class_fuel_rates = {}

    for filename in sorted(os.listdir(DIRECTORY_INPUT)):
        filepath = f'{DIRECTORY_INPUT}/{filename}'
        print(filepath)
        parts = filename.split('_', 2)
        if len(parts) < 3:
            print("Warning! could not read file: ", filename)
            continue
        business_type = parts[0].lower()
        county = parts[1].lower()
        filetype = parts[2].lower()

        if "carrier" in filetype:
            df = pd.read_csv(filepath)
            # df['carrierId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'carrierId', row), axis=1)
            # df['vehicleId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'vehicleId', row), axis=1)
            df['carrierId'] = df.apply(lambda row: add_prefix(f'', 'carrierId', row, False), axis=1).tolist()
            df['vehicleTypeId'] = df.apply(
                lambda row: add_prefix('', 'vehicleTypeId', row, to_num=True, store_dict=None, veh_type=True,
                                       suffix=f"-{YEAR}-{SCENARIO_LABEL}"),
                axis=1).tolist()
            df['vehicleId'] = df.apply(lambda row: add_prefix(row['carrierId'] + '-', 'vehicleId', row),
                                       axis=1).tolist()
            # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
            df['tourId'] = df.apply(
                lambda row: add_prefix(f'{business_type}-', 'tourId', row, True, _tourId_with_prefix),
                axis=1).tolist()
            if _carriers is None:
                _carriers = df
            else:
                _carriers = pd.concat([_carriers, df])
        elif "freight_tours" in filetype:
            df = pd.read_csv(filepath)
            # df['tour_id'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tour_id', row), axis=1)
            df['tour_id'] = df.apply(lambda row: _tourId_with_prefix[str(int(row['tour_id']))], axis=1).tolist()
            if _tours is None:
                _tours = df
            else:
                _tours = pd.concat([_tours, df])
        elif "payload" in filetype:
            df = pd.read_csv(filepath)
            if "ondemand" in county:
                df['tourId'] = df.apply(lambda row: add_prefix(f'ridehail-', 'tourId', row), axis=1)
                if _ondemand_plans is None:
                    _ondemand_plans = df
                else:
                    _ondemand_plans = pd.concat([_ondemand_plans, df])
            else:
                df['tourId'] = df.apply(lambda row: _tourId_with_prefix[str(int(row['tourId']))], axis=1).tolist()
                df['payloadId'] = df.apply(lambda row: add_prefix('', 'payloadId', row, False), axis=1).tolist()
                _tourId_with_prefix = {}
                if _payload_plans is None:
                    _payload_plans = df
                else:
                    _payload_plans = pd.concat([_payload_plans, df])
        elif "vehicle_types" in filename: # Modify the "vehicle_types" section in the main loop
            df = pd.read_csv(filepath)

            # First pass: collect vehicle class and fuel rate information for non-PHEV vehicles
            for _, row in df.iterrows():
                veh_class = row['veh_class']
                fuel_type = row['primary_fuel_type']
                fuel_rate = row['primary_fuel_rate']

                if 'PHEV' not in str(row['veh_type_id']):
                    vehicle_class_fuel_rates[f"{veh_class}-{fuel_type}"] = fuel_rate

            # Process all vehicles, handling PHEVs specially
            empty_vectors = list(np.repeat("", len(df.index)))
            vehicle_types_ids = []
            original_vehicle_types_ids = []
            primary_fuel_types = []
            primary_fuel_consumption = []
            primary_fuel_capacities = []
            secondary_fuel_types = []
            secondary_fuel_consumption = []
            secondary_fuel_capacities = []

            for _, row in df.iterrows():
                veh_type_id = add_prefix('', 'veh_type_id', row, to_num=True,
                                         store_dict=None, veh_type=True, suffix=f"-{YEAR}-{SCENARIO_LABEL}")
                vehicle_types_ids.append(veh_type_id)

                original_veh_type_id = add_prefix('', 'veh_type_id', row, to_num=True,
                                         store_dict=None, veh_type=True, suffix="")
                original_vehicle_types_ids.append(original_veh_type_id)

                veh_class = row['veh_class']
                fuel_type = row['primary_fuel_type']

                # Check if this is a PHEV vehicle
                if 'PHEV' in str(row['veh_type_id']):
                    if f"{veh_class}-Electricity" in vehicle_class_fuel_rates and f"{veh_class}-{fuel_type}" in vehicle_class_fuel_rates:
                        # Primary
                        primary_fuel_types.append('Electricity')
                        fuel_rate_1 = vehicle_class_fuel_rates[f"{veh_class}-Electricity"]
                        primary_fuel_consumption.append(constants["joule_per_meter_base_rate"] / (float(fuel_rate_1) * 1609.34))
                        primary_fuel_capacities.append(12000000000000000 * 0.25)  # 25% of standard capacity

                        # Secondary
                        secondary_fuel_types.append(fuel_type)
                        fuel_rate_2 = vehicle_class_fuel_rates[f"{veh_class}-{fuel_type}"]
                        secondary_fuel_consumption.append(constants["joule_per_meter_base_rate"] / (float(fuel_rate_2) * 1609.34))
                        secondary_fuel_capacities.append(12000000000000000 * 0.75)  # 75% of standard capacity
                else:
                    # For non-PHEV vehicles, use standard processing
                    primary_fuel_types.append(row["primary_fuel_type"])
                    primary_fuel_consumption.append(constants["joule_per_meter_base_rate"] /
                                                    (np.float64(row["primary_fuel_rate"]) * 1609.34))
                    primary_fuel_capacities.append(12000000000000000)
                    secondary_fuel_types.append(np.nan)
                    secondary_fuel_consumption.append(np.nan)
                    secondary_fuel_capacities.append(np.nan)

            # Create the vehicles techs dictionary with our processed values
            vehicles_techs = {
                "vehicleTypeId": vehicle_types_ids,
                "seatingCapacity": list(np.repeat(1, len(df.index))),
                "standingRoomCapacity": list(np.repeat(0, len(df.index))),
                "lengthInMeter": list(np.repeat(12, len(df.index))),
                "primaryFuelType": primary_fuel_types,
                "primaryFuelConsumptionInJoulePerMeter": primary_fuel_consumption,
                "primaryFuelCapacityInJoule": primary_fuel_capacities,
                "primaryVehicleEnergyFile": [
                    PRIMARY_ENERGY_PROFILE[index] if index in PRIMARY_ENERGY_PROFILE else np.nan
                    for index in original_vehicle_types_ids],
                "secondaryFuelType": secondary_fuel_types,
                "secondaryFuelConsumptionInJoulePerMeter": secondary_fuel_consumption,
                "secondaryFuelCapacityInJoule": secondary_fuel_capacities,
                "secondaryVehicleEnergyFile": [
                    SECONDARY_ENERGY_PROFILE[index][3] if index in SECONDARY_ENERGY_PROFILE else np.nan for
                    index in original_vehicle_types_ids],
                "automationLevel": list(np.repeat(1, len(df.index))),
                "maxVelocity": df["max_speed(mph)"],
                "passengerCarUnit": empty_vectors,
                "rechargeLevel2RateLimitInWatts": empty_vectors,
                "rechargeLevel3RateLimitInWatts": empty_vectors,
                "vehicleCategory": list(np.repeat("Class456Vocational", len(df.index))),
                "sampleProbabilityWithinCategory": empty_vectors,
                "sampleProbabilityString": empty_vectors,
                "payloadCapacityInKg": df["payload_capacity_weight"],
                "vehicleClass": df["veh_class"]
            }

            df2 = pd.DataFrame(vehicles_techs)
            df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('hdv'), 'Class78Vocational',
                                              df2.vehicleCategory)
            df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('hdt'), 'Class78Tractor',
                                              df2.vehicleCategory)
            df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('ld'), 'Class2b3Vocational',
                                              df2.vehicleCategory)

            if _vehicle_types is None:
                _vehicle_types = df2
            else:
                _vehicle_types = pd.concat([_vehicle_types, df2])
        else:
            print(f'SKIPPING {filename}')


    _vehicle_types.to_csv(
        f'{DIRECTORY_VEHICLE_TECH}/ft-vehicletypes--{BATCH_NAME.replace("-", "")}--{YEAR}-{SCENARIO_LABEL}.csv',
        index=False)

    # Load OSM network and create buffer
    _osm_edges_utm = load_osm_network(
        NETWORK_OSM_PBF,
        min_distance_from_edge=BUFFER_DISTANCE_METERS
    )

    _coordinate_lookup = {}

    # carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
    carriers_renames = {
        'depot_zone': 'warehouseZone',
        'depot_zone_x': 'warehouseX',
        'depot_zone_y': 'warehouseY',
        'true_depot_zone': 'mesoZone'
    }
    carriers_drop = ['x', 'y', 'index']
    _carriers.rename(columns=carriers_renames, inplace=True)
    _carriers.drop(carriers_drop, axis=1, inplace=True, errors='ignore')
    _carriers['warehouseZone'] = _carriers['warehouseZone'].astype(int)
    if SNAP_COORDINATES:
        _carriers, _coordinate_lookup = snap_coordinates_when_too_far(
            _carriers,
            _osm_edges_utm,
            "warehouseX",
            "warehouseY",
            _coordinate_lookup
        )
    # Write
    _carriers.to_csv(f'{DIRECTORY_SCENARIO}/carriers--{YEAR}-{SCENARIO_LABEL}.csv', index=False)

    # tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
    tours_renames = {
        'tour_id': 'tourId',
        'departureLocation_zone': 'departureLocationZone',
        'departureLocation_x': 'departureLocationX',
        'departureLocation_y': 'departureLocationY',
        'true_depot_zone': 'mesoZone'
    }
    _tours.rename(columns=tours_renames, inplace=True)
    _tours['departureTimeInSec'] = _tours['departureTimeInSec'].astype(int)
    _tours['maxTourDurationInSec'] = _tours['maxTourDurationInSec'].astype(int)
    _tours['departureLocationZone'] = _tours['departureLocationZone'].astype(int)
    _tours.drop(['index'], axis=1, inplace=True, errors='ignore')
    if SNAP_COORDINATES:
        _tours, _coordinate_lookup = snap_coordinates_when_too_far(
            _tours,
            _osm_edges_utm,
            "departureLocationX",
            "departureLocationY",
            _coordinate_lookup
        )
    # Write
    _tours.to_csv(f'{DIRECTORY_SCENARIO}/tours--{YEAR}-{SCENARIO_LABEL}.csv', index=False)

    # Process payloads
    print("Processing payload plans...")
    # Add random_state for reproducibility
    # sampled_df = _payload_plans.sample(n=1000, random_state=42).copy().reset_index(drop=True)
    # sampled_df.to_csv(f'{DIRECTORY_OUTPUT}/payloads-sampled--{YEAR}-{SCENARIO_LABEL}.csv', index=False)
    # Then format and save
    # Create shared coordinate lookup table
    _payload_plans = format_payload(_payload_plans)
    if SNAP_COORDINATES:
        # Snap coordinates and save
        _payload_plans, _coordinate_lookup = snap_coordinates_when_too_far(
            _payload_plans,
            _osm_edges_utm,
            "locationX",
            "locationY",
            _coordinate_lookup
        )
    _payload_plans["operationDurationInSecOG"] = _payload_plans["operationDurationInSec"]
    _payload_plans = update_operation_duration(_payload_plans, _tours, _carriers, _vehicle_types)
    _payload_plans.to_csv(f'{DIRECTORY_SCENARIO}/payloads--{YEAR}-{SCENARIO_LABEL}.csv', index=False)

    if _ondemand_plans is not None:
        print("Processing ondemand plans...")
        _ondemand_plans = format_payload(_ondemand_plans)
        if SNAP_COORDINATES:
            # Snap coordinates and save, reusing the lookup table
            _ondemand_plans, _coordinate_lookup = snap_coordinates_when_too_far(
                _ondemand_plans,
                _osm_edges_utm,
                "locationX",
                "locationY",
                _coordinate_lookup
            )
        _ondemand_plans.to_csv(f'{DIRECTORY_SCENARIO}/ondemand--{YEAR}-{SCENARIO_LABEL}.csv', index=False)

        # Create combined plans file with both regular plans and ondemand plans
        if _payload_plans is not None:
            print("Creating combined plans file of payloads and crowdshipments...")
            # Save the combined file
            pd.concat([_payload_plans, _ondemand_plans], ignore_index=True).to_csv(
                f'{DIRECTORY_SCENARIO}/payloads+crowdshipments--{YEAR}-{SCENARIO_LABEL}.csv', index=False
            )
