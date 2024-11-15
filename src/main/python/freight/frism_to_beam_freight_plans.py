import pandas as pd
import os
from pathlib import Path
import numpy as np
import geopandas as gpd
from shapely.geometry import Point, LineString, Polygon
from shapely.ops import unary_union
from pyrosm import OSM
import multiprocessing as mp
from tqdm import tqdm
import random
import osmium
from datetime import datetime

import warnings

warnings.filterwarnings('ignore')

frism_version = 1.0
frism_crs = 4326
city = "seattle"
city_crs = 32048
batch_name = "2024-04-20"
year, scenario_name, suffix = "2018", "Baseline", "_RPSFerry"
# year, run_name = "2050", "Ref_highp6"
scenario_label = scenario_name.replace("_", "")

# work_dir = os.path.expanduser(f'/Volumes/HG40/Workspace')
work_dir = os.path.expanduser(f'~/Workspace')
directory_input = f'{work_dir}/Simulation/{city}/frism/{batch_name}/{scenario_name}'
directory_output = f'{work_dir}/Simulation/{city}/beam-freight/{batch_name}/{year}_{scenario_label}' + f"{suffix}"
network_osm_pbf = f"{work_dir}/Simulation/{city}/validation/seattle-residential-partiallysimplified-ferry-buffer.osm.pbf"

Path(directory_output).mkdir(parents=True, exist_ok=True)
directory_vehicle_tech = f'{directory_output}/vehicle-tech'
Path(directory_vehicle_tech).mkdir(parents=True, exist_ok=True)
carriers = None
payload_plans = None
ondemand_plans = None
tours = None
vehicle_types = None
tourId_with_prefix = {}

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
    "freight-md-E-PHEV-Baseline": ("Diesel", 9595.796035186175, 12000000000000000,
                                   "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
    # "freight-hdt-D-Diesel-Baseline": np.nan,
    # "freight-hdt-E-BE-Baseline": np.nan,
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": ("Diesel", 13817.086117829229, 12000000000000000,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
    # "freight-hdv-D-Diesel-Baseline": np.nan,
    # "freight-hdv-E-BE-Baseline": np.nan,
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": ("Diesel", 14026.761465378302, 12000000000000000,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
}


def load_osm_network(pbf_path, min_distance_from_edge):
    """
    Load OSM network and create/load buffered network with proper metric distances

    Args:
        pbf_path (str): Path to original OSM PBF file
        min_distance_from_edge (float): Buffer distance in meters
        city_crs (int, optional): EPSG code for city-specific UTM zone.
            If None, will auto-detect based on data extent

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

    print("Creating road buffer...")
    # Convert to UTM for proper metric distances
    try:
        edges_utm = edges.to_crs(epsg=city_crs)
    except Exception as e:
        raise ValueError(f"Failed to convert to UTM (EPSG:{city_crs}): {str(e)}")

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
    buffer_path = f"{path_without_ext}_buffered.geojson"

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
        save_gdf = buffered_edges.to_crs(epsg=frism_crs)

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


def generate_random_point_near_line(nearest_edge, point_geom, max_dist_meters):
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


def process_single_point(args):
    """
    Process a single coordinate point that needs adjustment
    """
    point, edges_gdf, min_distance_from_edge, max_distance_from_edge = args
    try:
        x, y = point
        point_geom = Point(point)

        # Find nearest edge within search distance
        distances = edges_gdf.geometry.distance(point_geom)
        min_dist = distances.min()

        if min_dist > max_distance_from_edge:
            return x, y, True, False  # Original coords, is_far, is_adjusted

        # Need to adjust - find random point near nearest edge
        nearest_edge = edges_gdf.iloc[distances.idxmin()]
        new_x, new_y = generate_random_point_near_line(nearest_edge, point_geom, min_distance_from_edge)

        return new_x, new_y, False, True  # New coords, not far, is_adjusted

    except Exception as e:
        print(f"Warning: Error processing point {point}: {str(e)}")
        return x, y, False, False  # Return original coordinates in case of error


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


# ******************************

# payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationZone,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec,locationX,locationY
def format_payload(_payload, osm_edges_utm, min_distance_from_edge, max_distance_from_edge):
    """
    Format payload and adjust coordinates where needed using road buffer for efficiency
    """
    payload_plans_renames = {
        'arrivalTimeWindowInSec_lower': 'arrivalTimeWindowInSecLower',
        'arrivalTimeWindowInSec_upper': 'arrivalTimeWindowInSecUpper',
        'locationZone_x': 'locationX',
        'locationZone_y': 'locationY',
        'true_locationZone': 'mesoZone',
        'BuyerNAICS': "buyerNAICS",
        "SellerNAICS": "sellerNAICS"
    }

    # Regular payload processing
    _payload.rename(columns=payload_plans_renames, inplace=True)
    int_columns = ['sequenceRank', 'payloadType', 'requestType', 'estimatedTimeOfArrivalInSec',
                   'arrivalTimeWindowInSecLower', 'arrivalTimeWindowInSecUpper',
                   'operationDurationInSec', 'locationZone']

    for col in int_columns:
        _payload[col] = _payload[col].astype(int)

    # Check coordinates against road buffer
    print("Creating GeoDataFrame from points...")
    points_gdf = gpd.GeoDataFrame(
        _payload,
        geometry=[Point(xy) for xy in zip(_payload['locationX'], _payload['locationY'])],
        crs=4326
    )
    points_utm_gdf = points_gdf.to_crs(osm_edges_utm.crs)

    print("Checking points against road buffer...")
    # Use buffered_geometry for intersection check
    buffer_union = unary_union(osm_edges_utm['buffered_geometry'])
    points_in_buffer = points_utm_gdf.geometry.intersects(buffer_union)

    # Points that need processing are those outside the buffer
    points_to_process = points_utm_gdf[~points_in_buffer]
    print(f"Found {len(points_to_process)} points (out of {len(_payload)}) that need adjustment")

    if len(points_to_process) > 0:
        # Convert to UTM for accurate distance calculations
        coordinates = list(zip(points_to_process['locationX'], points_to_process['locationY']))
        points_to_process_indices = points_to_process.index

        # Process in batches
        batch_size = 1000
        n_batches = (len(coordinates) + batch_size - 1) // batch_size

        all_results = []
        far_points = 0
        total_adjusted = 0

        print(f"Processing {len(coordinates)} points in {n_batches} batches...")

        with mp.Pool(processes=max(1, mp.cpu_count() - 1)) as pool:
            for i in range(n_batches):
                start_idx = i * batch_size
                end_idx = min((i + 1) * batch_size, len(coordinates))
                batch_coords = coordinates[start_idx:end_idx]

                args = [(coord, osm_edges_utm, min_distance_from_edge, max_distance_from_edge) for coord in
                        batch_coords]

                batch_results = list(tqdm(
                    pool.imap(process_single_point, args),
                    total=len(batch_coords),
                    desc=f"Batch {i + 1}/{n_batches}"
                ))

                for x, y, is_far, is_adjusted in batch_results:
                    if is_far:
                        far_points += 1
                    if is_adjusted:
                        total_adjusted += 1
                    # Convert UTM coordinates back to original CRS
                    point_utm = gpd.GeoDataFrame(geometry=[Point(x, y)], crs=city_crs)
                    point_original = point_utm.to_crs(frism_crs)
                    all_results.append((point_original.geometry.x[0], point_original.geometry.y[0]))

        if far_points > 0:
            print(f"Warning: {far_points} stops are farther than 50 miles from any road")
        if total_adjusted > 0:
            print(f"Adjusted {total_adjusted} points to be within 200m of nearest road")

        # Update only the points that needed processing
        _payload.loc[points_to_process_indices, 'locationX'] = [p[0] for p in all_results]
        _payload.loc[points_to_process_indices, 'locationY'] = [p[1] for p in all_results]

    # Continue with the rest of payload processing
    payload_type_map = {
        1: 'bulk',
        2: 'fuel_fert',
        3: 'interm_food',
        4: 'mfr_goods',
        5: 'others'
    }

    _payload['payloadType'] = _payload['payloadType'].map(payload_type_map)
    _payload['weightInKg'] = _payload['weightInlb'].astype(float) * 0.45359237

    if frism_version > 1.0:
        _payload['deliveryType'] = _payload['requestType'].map({1: 'delivery-only', 3: 'pickup-delivery'})
        _payload['requestType'] = _payload['requestType'].astype('object')
        _payload.loc[_payload['weightInKg'] < 0, 'requestType'] = 'unloading'
        _payload.loc[_payload['weightInKg'] >= 0, 'requestType'] = 'loading'
        _payload['weightInKg'] = np.abs(_payload['weightInKg'])

        _payload['fleetType'] = _payload['truck_mode'].map({
            'Private Truck': 'private',
            'For-hire Truck': 'for-hire'
        }, na_action='ignore')
    else:
        _payload['requestType'] = _payload['requestType'].map({1: 'unloading', 0: 'loading'})
        _payload['weightInKg'] = np.abs(_payload['weightInKg'])

    payload_plans_drop = ['truck_mode', 'weightInlb', 'cummulativeWeightInlb', 'index']
    _payload.drop(payload_plans_drop, axis=1, inplace=True, errors='ignore')

    return _payload


if __name__ == '__main__':
    for filename in sorted(os.listdir(directory_input)):
        filepath = f'{directory_input}/{filename}'
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
                                       suffix=f"-{year}-{scenario_label}"),
                axis=1).tolist()
            df['vehicleId'] = df.apply(lambda row: add_prefix(row['carrierId'] + '-', 'vehicleId', row),
                                       axis=1).tolist()
            # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
            df['tourId'] = df.apply(
                lambda row: add_prefix(f'{business_type}-', 'tourId', row, True, tourId_with_prefix),
                axis=1).tolist()
            if carriers is None:
                carriers = df
            else:
                carriers = pd.concat([carriers, df])
        elif "freight_tours" in filetype:
            df = pd.read_csv(filepath)
            # df['tour_id'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tour_id', row), axis=1)
            df['tour_id'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tour_id']))], axis=1).tolist()
            if tours is None:
                tours = df
            else:
                tours = pd.concat([tours, df])
        elif "payload" in filetype:
            df = pd.read_csv(filepath)
            if "ondemand" in county:
                df['tourId'] = df.apply(lambda row: add_prefix(f'ridehail-', 'tourId', row), axis=1)
                if ondemand_plans is None:
                    ondemand_plans = df
                else:
                    ondemand_plans = pd.concat([ondemand_plans, df])
            else:
                df['tourId'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tourId']))], axis=1).tolist()
                df['payloadId'] = df.apply(lambda row: add_prefix('', 'payloadId', row, False), axis=1).tolist()
                tourId_with_prefix = {}
                if payload_plans is None:
                    payload_plans = df
                else:
                    payload_plans = pd.concat([payload_plans, df])
        elif "vehicle_types" in filename:
            df = pd.read_csv(filepath)
            empty_vectors = list(np.repeat("", len(df.index)))
            # JoulePerMeter = 121300000/(mpgge*1609.34)
            vehicle_types_ids = df.apply(
                lambda row: add_prefix('', 'veh_type_id', row, to_num=True, store_dict=None, veh_type=True,
                                       suffix=f"-{year}-{scenario_label}"), axis=1).tolist()
            vehicles_techs = {
                "vehicleTypeId": vehicle_types_ids,
                "seatingCapacity": list(np.repeat(1, len(df.index))),
                "standingRoomCapacity": list(np.repeat(0, len(df.index))),
                "lengthInMeter": list(np.repeat(12, len(df.index))),
                "primaryFuelType": df["primary_fuel_type"],
                "primaryFuelConsumptionInJoulePerMeter": np.divide(121300000,
                                                                   np.float64(df["primary_fuel_rate"]) * 1609.34),
                "primaryFuelCapacityInJoule": list(np.repeat(12000000000000000, len(df.index))),
                "primaryVehicleEnergyFile": [primary_energy_files[id] if id in primary_energy_files else np.nan for id
                                             in
                                             vehicle_types_ids],
                "secondaryFuelType": [
                    secondary_energy_profile_for_phev[id][0] if id in secondary_energy_profile_for_phev else np.nan for
                    id
                    in vehicle_types_ids],
                "secondaryFuelConsumptionInJoulePerMeter": [
                    secondary_energy_profile_for_phev[id][1] if id in secondary_energy_profile_for_phev else np.nan for
                    id
                    in vehicle_types_ids],
                "secondaryVehicleEnergyFile": [
                    secondary_energy_profile_for_phev[id][3] if id in secondary_energy_profile_for_phev else np.nan for
                    id
                    in vehicle_types_ids],
                "secondaryFuelCapacityInJoule": [
                    secondary_energy_profile_for_phev[id][2] if id in secondary_energy_profile_for_phev else np.nan for
                    id
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
            if vehicle_types is None:
                vehicle_types = df2
            else:
                vehicle_types = pd.concat([vehicle_types, df2])
        else:
            print(f'SKIPPING {filename}')

    vehicle_types.to_csv(
        f'{directory_vehicle_tech}/ft-vehicletypes--{batch_name.replace("-", "")}--{year}-{scenario_label}.csv',
        index=False)

    # In[9]:

    # carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
    # carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
    carriers_renames = {
        'depot_zone': 'warehouseZone',
        'depot_zone_x': 'warehouseX',
        'depot_zone_y': 'warehouseY',
        'true_depot_zone': 'mesoZone'
    }
    carriers_drop = ['x', 'y', 'index']
    carriers.rename(columns=carriers_renames, inplace=True)
    carriers.drop(carriers_drop, axis=1, inplace=True, errors='ignore')
    carriers['warehouseZone'] = carriers['warehouseZone'].astype(int)
    carriers.to_csv(f'{directory_output}/carriers--{year}-{scenario_label}.csv', index=False)

    # In[10]:

    # tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
    # tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
    tours_renames = {
        'tour_id': 'tourId',
        'departureLocation_zone': 'departureLocationZone',
        'departureLocation_x': 'departureLocationX',
        'departureLocation_y': 'departureLocationY',
        'true_depot_zone': 'mesoZone'
    }
    tours.rename(columns=tours_renames, inplace=True)
    tours['departureTimeInSec'] = tours['departureTimeInSec'].astype(int)
    tours['maxTourDurationInSec'] = tours['maxTourDurationInSec'].astype(int)
    tours['departureLocationZone'] = tours['departureLocationZone'].astype(int)
    tours.drop(['index'], axis=1, inplace=True, errors='ignore')
    tours.to_csv(f'{directory_output}/tours--{year}-{scenario_label}.csv', index=False)

    _min_distance_from_edge = 200  # 200 meters
    _max_distance_from_edge = 50 * 1609.34  # 50 miles

    # Load OSM network and create buffer
    print("Loading/creating network...")
    osm_edges_utm = load_osm_network(
        network_osm_pbf,
        min_distance_from_edge=_min_distance_from_edge
    )

    # Process payloads
    print("Processing payload plans...")
    format_payload(
        payload_plans,
        osm_edges_utm,
        min_distance_from_edge=_min_distance_from_edge,
        max_distance_from_edge=_max_distance_from_edge
    ).to_csv(f'{directory_output}/payloads--{year}-{scenario_label}.csv', index=False)

    if ondemand_plans is not None:
        print("Processing ondemand plans...")
        format_payload(
            ondemand_plans,
            osm_edges_utm,
            min_distance_from_edge=_min_distance_from_edge,
            max_distance_from_edge=_max_distance_from_edge
        ).to_csv(f'{directory_output}/ondemand--{year}-{scenario_label}.csv', index=False)
