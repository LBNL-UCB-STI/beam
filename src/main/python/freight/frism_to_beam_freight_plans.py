import multiprocessing as mp
import os
import random
import warnings
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Tuple, List, Any

import geopandas as gpd
import numpy as np
import pandas as pd
from pandas import DataFrame
from pyrosm import OSM
from scipy.spatial import cKDTree
from shapely.geometry import Point

warnings.filterwarnings('ignore')

# System and general constants
JOULES_PER_METER_BASE = 121300000  # Base value for energy consumption calculation
MAX_FUEL_CAPACITY = 12000000000000000  # Maximum fuel capacity in Joules

# Coordinate snapping constants
METERS_PER_MILE = 1609.34
BUFFER_DISTANCE_METERS = 2000  # 2km
MAX_DISTANCE_METERS = 200000  # 200km
CHUNK_SIZE = 10000  # this affects speed and parallelization of the script

# City and scenario settings
FRISM_VERSION = 1.5
SOURCE_CRS = 4326  # WGS84
UTM_CRS = 26910  # Seattle UTM zone 32048 | SFBAY 26910
AREA = "sfbay"
BATCH_NAME = "2024-11-06"
YEAR = "2018"
SCENARIO_NAME = "Baseline"
SCENARIO_SUFFIX = ""
SCENARIO_LABEL = SCENARIO_NAME.replace("_", "")
NETWORK_OSM_PBF_NAME = "r5-simple-no-local/bay_area_simplified_tertiary_strongly_2_way_network.osm.pbf"

# File paths and directories
WORK_DIR = os.path.expanduser('~/Workspace')
DIRECTORY_INPUT = f'{WORK_DIR}/Simulation/{AREA}/frism/{BATCH_NAME}/{SCENARIO_NAME}'
DIRECTORY_OUTPUT = f'{WORK_DIR}/Simulation/{AREA}/beam-freight/{BATCH_NAME}/{YEAR}_{SCENARIO_LABEL}{SCENARIO_SUFFIX}'
DIRECTORY_VEHICLE_TECH = f'{DIRECTORY_OUTPUT}/vehicle-tech'
NETWORK_OSM_PBF = f"{WORK_DIR}/Simulation/{AREA}/network/{NETWORK_OSM_PBF_NAME}"
Path(DIRECTORY_OUTPUT).mkdir(parents=True, exist_ok=True)
Path(DIRECTORY_VEHICLE_TECH).mkdir(parents=True, exist_ok=True)

# Variables
_carriers = None
_payload_plans = None
_ondemand_plans = None
_tours = None
_vehicle_types = None
_tourId_with_prefix = {}

# ******************************

primary_energy_files = {
    "freight-md-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(Diesel,_2020,_no_program).csv",
    "freight-md-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
    # "freight-md-E-H2FC-Baseline": np.nan,
    "freight-md-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
    "freight-hdt-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(Diesel,_2020,_no_program).csv",
    "freight-hdt-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
    "freight-hdv-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(Diesel,_2020,_no_program).csv",
    "freight-hdv-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv",
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv"
}

secondary_energy_profile_for_phev = {
    # "freight-md-D-Diesel-Baseline": np.nan,
    # "freight-md-E-BE-Baseline": np.nan,
    # "freight-md-E-H2FC-Baseline": np.nan,
    "freight-md-E-PHEV-Baseline": ("Diesel", 9595.796035186175, MAX_FUEL_CAPACITY,
                                   "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
    # "freight-hdt-D-Diesel-Baseline": np.nan,
    # "freight-hdt-E-BE-Baseline": np.nan,
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": ("Diesel", 13817.086117829229, MAX_FUEL_CAPACITY,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
    # "freight-hdv-D-Diesel-Baseline": np.nan,
    # "freight-hdv-E-BE-Baseline": np.nan,
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": ("Diesel", 14026.761465378302, MAX_FUEL_CAPACITY,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
}


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
        save_gdf = buffered_edges.to_crs(epsg=SOURCE_CRS)

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
        _payload_plans['requestType'] = _payload_plans['requestType'].map({1: 'unloading', 0: 'loading'})
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
        centroids: np.ndarray,
        kdtree: cKDTree,
        k: int = 5
) -> Tuple[float, gpd.GeoSeries]:
    """
    Find nearest edge using KD-tree with vectorized distance calculations in UTM coordinates

    Args:
        point_utm: Point geometry in UTM coordinates
        edges_gdf_utm: GeoDataFrame containing network edges in UTM
        centroids: NumPy array of edge centroids in UTM
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
        centroids: np.ndarray,
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
        centroids: NumPy array of edge centroids
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
        crs=SOURCE_CRS
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
            min_dist, nearest_edge_utm = find_nearest_edge_kdtree(
                point_utm,
                edges_gdf_utm,
                centroids,
                kdtree
            )

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
                ).to_crs(SOURCE_CRS).geometry[0]

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


def snap_coordinates_when_too_far(payload_plans: pd.DataFrame,
                                  osm_edges_utm: gpd.GeoDataFrame,
                                  coordinate_lookup: dict = None) -> tuple[DataFrame, dict[Any, Any] | dict]:
    """
    Optimized version of coordinate snapping using KD-tree spatial indexing and lookup table

    Args:
        payload_plans: DataFrame with locationZone_x/y in WGS84
        osm_edges_utm: GeoDataFrame with network in UTM
        coordinate_lookup: Optional existing lookup table to use

    Returns:
        DataFrame with snapped coordinates in WGS84
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
        payload_plans['locationX'].values,
        payload_plans['locationY'].values
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
                centroids,
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
        print(f"Warning: {far_points} stops are farther than {int(max_distance_from_edge / 1000)} km from any road")
    if total_adjusted > 0:
        print(f"Adjusted {total_adjusted} points to be within {int(min_distance_from_edge / 1000)} km of nearest road")

    # Sort results and update DataFrame efficiently
    all_results.sort(key=lambda r: r[0])
    result_indices = [r[0] for r in all_results]
    x_coords = [r[1] for r in all_results]
    y_coords = [r[2] for r in all_results]

    result_df = payload_plans.copy()
    result_df['locationX'] = pd.Series(x_coords, index=result_indices)
    result_df['locationY'] = pd.Series(y_coords, index=result_indices)

    return result_df, coordinate_lookup


#############################
## MAIN

if __name__ == '__main__':
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
        elif "vehicle_types" in filename:
            df = pd.read_csv(filepath)
            empty_vectors = list(np.repeat("", len(df.index)))
            # JoulePerMeter = JOULES_PER_METER_BASE/(mpgge*1609.34)
            vehicle_types_ids = df.apply(
                lambda row: add_prefix('', 'veh_type_id', row, to_num=True, store_dict=None, veh_type=True,
                                       suffix=f"-{YEAR}-{SCENARIO_LABEL}"), axis=1).tolist()
            vehicles_techs = {
                "vehicleTypeId": vehicle_types_ids,
                "seatingCapacity": list(np.repeat(1, len(df.index))),
                "standingRoomCapacity": list(np.repeat(0, len(df.index))),
                "lengthInMeter": list(np.repeat(12, len(df.index))),
                "primaryFuelType": df["primary_fuel_type"],
                "primaryFuelConsumptionInJoulePerMeter": np.divide(JOULES_PER_METER_BASE,
                                                                   np.float64(df["primary_fuel_rate"]) * 1609.34),
                "primaryFuelCapacityInJoule": list(np.repeat(12000000000000000, len(df.index))),
                "primaryVehicleEnergyFile": [primary_energy_files[index] if index in primary_energy_files else np.nan
                                             for index
                                             in
                                             vehicle_types_ids],
                "secondaryFuelType": [
                    secondary_energy_profile_for_phev[index][
                        0] if index in secondary_energy_profile_for_phev else np.nan for
                    index
                    in vehicle_types_ids],
                "secondaryFuelConsumptionInJoulePerMeter": [
                    secondary_energy_profile_for_phev[index][
                        1] if index in secondary_energy_profile_for_phev else np.nan for
                    index
                    in vehicle_types_ids],
                "secondaryVehicleEnergyFile": [
                    secondary_energy_profile_for_phev[index][
                        3] if index in secondary_energy_profile_for_phev else np.nan for
                    index
                    in vehicle_types_ids],
                "secondaryFuelCapacityInJoule": [
                    secondary_energy_profile_for_phev[index][
                        2] if index in secondary_energy_profile_for_phev else np.nan for
                    index
                    in vehicle_types_ids],
                "automationLevel": list(np.repeat(1, len(df.index))),
                "maxVelocity": df["max_speed(mph)"],  # convert to meter per second
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

    # Process payloads
    print("Processing payload plans...")
    _coordinate_lookup = {}
    # Add random_state for reproducibility
    # sampled_df = _payload_plans.sample(n=1000, random_state=42).copy().reset_index(drop=True)
    # sampled_df.to_csv(f'{DIRECTORY_OUTPUT}/payloads-sampled--{YEAR}-{SCENARIO_LABEL}.csv', index=False)
    # Then format and save
    # Create shared coordinate lookup table
    _payload_plans_file = f'{DIRECTORY_OUTPUT}/payloads--{YEAR}-{SCENARIO_LABEL}.csv'
    _payload_plans_no_snap_file = _payload_plans_file.replace("payloads", "payloads--no-snap")
    format_payload(_payload_plans).to_csv(_payload_plans_no_snap_file, index=False)
    # Snap coordinates and save
    _payload_plans_snapped, _coordinate_lookup = snap_coordinates_when_too_far(_payload_plans, _osm_edges_utm,
                                                                       _coordinate_lookup)
    _payload_plans_snapped.to_csv(_payload_plans_file, index=False)

    if _ondemand_plans is not None:
        print("Processing ondemand plans...")
        _ondemand_plans_file = f'{DIRECTORY_OUTPUT}/ondemand--{YEAR}-{SCENARIO_LABEL}.csv'
        _ondemand_plans_no_snap_file = _ondemand_plans_file.replace("ondemand", "ondemand--no-snap")
        format_payload(_ondemand_plans).to_csv(_ondemand_plans_no_snap_file, index=False)
        # Snap coordinates and save, reusing the lookup table
        _ondemand_plans_snapped, _coordinate_lookup = snap_coordinates_when_too_far(_ondemand_plans, _osm_edges_utm,
                                                                            _coordinate_lookup)
        _ondemand_plans_snapped.to_csv(_ondemand_plans_file, index=False)

        # Create combined plans file with both regular plans and ondemand plans
        if _payload_plans is not None:
            print("Creating combined plans file of payloads and crowdshipments...")
            combined_file_label = "payloads+crowdshipments"
            # Save the combined file
            combined_plans_file = f'{DIRECTORY_OUTPUT}/{combined_file_label}--{YEAR}-{SCENARIO_LABEL}.csv'
            pd.concat([_payload_plans_snapped, _ondemand_plans_snapped], ignore_index=True).to_csv(combined_plans_file, index=False)
            combined_plans_no_snap_file = combined_plans_file.replace(combined_file_label, f"{combined_file_label}--no-snap")
            pd.concat([_payload_plans, _ondemand_plans], ignore_index=True).to_csv(combined_plans_no_snap_file, index=False)
            print(f"Combined plans file saved to {combined_plans_file} and {combined_plans_no_snap_file}")

    # selecting initial locations
    first_payloads = _payload_plans[_payload_plans['sequenceRank'] == 0].copy()

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
    #
    coord_mapping = first_payloads.merge(_carriers[['tourId', 'carrierId']], on='tourId', how='inner')
    coord_mapping = coord_mapping.groupby('carrierId').agg({'locationX': 'first', 'locationY': 'first'})
    # Update carriers DataFrame with new coordinates
    _carriers.set_index('carrierId', inplace=True)
    # Update coordinates where matches exist
    _carriers.loc[coord_mapping.index, 'warehouseX'] = coord_mapping['locationX']
    _carriers.loc[coord_mapping.index, 'warehouseY'] = coord_mapping['locationY']
    # Reset index
    _carriers.reset_index(inplace=True)
    # Write
    _carriers.to_csv(f'{DIRECTORY_OUTPUT}/carriers--{YEAR}-{SCENARIO_LABEL}.csv', index=False)

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
    #
    # Create mapping of tourId to coordinates
    coord_mapping = first_payloads.set_index('tourId')[['locationX', 'locationY']]
    # Update tours DataFrame with new coordinates
    _tours.set_index('tourId', inplace=True)
    # Update coordinates where matches exist
    _tours.loc[coord_mapping.index, 'departureLocationX'] = coord_mapping['locationX']
    _tours.loc[coord_mapping.index, 'departureLocationY'] = coord_mapping['locationY']
    # Reset index
    _tours.reset_index(inplace=True)
    print(f"Updated departure coordinates for {len(coord_mapping)} tours")
    # Write
    _tours.to_csv(f'{DIRECTORY_OUTPUT}/tours--{YEAR}-{SCENARIO_LABEL}.csv', index=False)
