import sys
import os
import gc
import time
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.csv as pv
from pyproj import Transformer
from shapely.geometry import LineString
import concurrent.futures
from concurrent.futures import ProcessPoolExecutor
from rtree import index
from h3 import LatLngPoly

from _beam_emissions_plotting import *

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import emissions_config
from python.utils.study_area_config import get_fuel_key
from python.utils.study_area_config import generate_network_name

# Configure pandas display options
pd.set_option('display.max_columns', 20)

# ################
# ### Constants ##
# ################

# Define schema for skims data
SKIMS_SCHEMA = pa.schema([
    ('hour', pa.int64()),
    ('linkId', pa.int64()),
    ('tazId', pa.string()),
    ('vehicleTypeId', pa.string()),
    ('emissionsProcess', pa.string()),
    ('travelTimeInSecond', pa.float64()),
    ('energyInJoule', pa.float64()),
    ('observations', pa.int64()),
    ('iterations', pa.int64()),
    ('CH4', pa.float64()),
    ('CO', pa.float64()),
    ('CO2', pa.float64()),
    ('HC', pa.float64()),
    ('NH3', pa.float64()),
    ('NOx', pa.float64()),
    ('PM', pa.float64()),
    ('PM10', pa.float64()),
    ('PM2_5', pa.float64()),
    ('ROG', pa.float64()),
    ('SOx', pa.float64()),
    ('TOG', pa.float64()),
    ('BC', pa.float64()),
    ('BCm', pa.float64()),
    ('BCh', pa.float64())
])


# ################
# ### Functions ##
# ################

def read_skims_emissions_chunked(
        vehicle_types,
        network,
        emissions_skims_file,
        expansion_factor,
        scenario_name,
        chunk_size=1000000
):
    """
    Read and process emissions data from skims file in chunks (optimized)

    Args:
        vehicle_types: DataFrame with vehicle type information
        network: DataFrame with network information
        emissions_skims_file: Path to emissions skims file
        expansion_factor: Factor to scale observations
        scenario_name: Name of the scenario
        chunk_size: Size of chunks to process at once

    Returns:
        DataFrame with processed emissions data
    """
    start_time = time.time()
    print(f"Processing emissions data from {emissions_skims_file}")

    # Create optimized lookups
    unique_vehicle_types = vehicle_types['vehicleTypeId'].unique()
    vehicle_type_dict = vehicle_types.set_index('vehicleTypeId')[['mappedClass', 'mappedFuel']].to_dict('index')
    network_lengths = network.set_index('linkId')['linkLength'].to_dict()

    # Constants for calculations
    expansion_factor_scalar = pa.scalar(expansion_factor, type=pa.float64())
    million_scalar = pa.scalar(1e6, type=pa.float64())
    joule_to_kwh_scalar = pa.scalar(3.6e6, type=pa.float64())
    second_to_hour_scalar = pa.scalar(3.6e3, type=pa.float64())
    mile_conversion = 6.21371192e-4  # meters to miles

    # List of pollutants to process
    pollutant_cols = ['CH4', 'CO', 'CO2', 'HC', 'NH3', 'NOx', 'PM', 'PM10', 'PM2_5', 'ROG', 'SOx', 'TOG', 'BC', 'BCm',
                      'BCh']

    # Set up PyArrow CSV reader
    csv_reader = pv.open_csv(
        emissions_skims_file,
        read_options=pv.ReadOptions(block_size=chunk_size, use_threads=True),
        parse_options=pv.ParseOptions(delimiter=','),
        convert_options=pv.ConvertOptions(column_types=SKIMS_SCHEMA)
    )

    # Progress tracking
    file_size = os.path.getsize(emissions_skims_file)
    progress = tqdm(total=file_size, unit='B', unit_scale=True, desc="Processing emissions data")

    # Define function to process chunks in parallel
    def process_chunk(chunk):
        # Filter to relevant vehicle types
        mask = pc.is_in(chunk['vehicleTypeId'], pa.array(unique_vehicle_types))
        filtered = chunk.filter(mask)

        if filtered.num_rows == 0:
            return None

        # Calculate expanded observations
        observations_expansion = pc.multiply(filtered['observations'], expansion_factor_scalar)

        # Calculate scaled pollutants using PyArrow operations
        new_fields = []
        new_columns = []

        for pollutant in pollutant_cols:
            new_fields.append(pa.field(f'scaled_{pollutant}', pa.float64(), True))
            new_columns.append(
                pc.multiply(
                    pc.divide(filtered[pollutant], million_scalar),
                    observations_expansion
                )
            )

        # Calculate kwh using PyArrow
        new_fields.append(pa.field('kwh', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(filtered['energyInJoule'], joule_to_kwh_scalar),
                observations_expansion
            )
        )

        # Calculate vht using PyArrow
        new_fields.append(pa.field('vht', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(filtered['travelTimeInSecond'], second_to_hour_scalar),
                observations_expansion
            )
        )

        # Create new record batch with additional columns
        new_schema = filtered.schema
        for field in new_fields:
            new_schema = new_schema.append(field)

        result_batch = pa.RecordBatch.from_arrays(
            filtered.columns + new_columns,
            schema=new_schema
        )

        # Convert to pandas after all Arrow computations
        df = result_batch.to_pandas()

        # Add mapped class and fuel
        df['mappedClass'] = df['vehicleTypeId'].map({k: v['mappedClass'] for k, v in vehicle_type_dict.items()})
        df['mappedFuel'] = df['vehicleTypeId'].map({k: v['mappedFuel'] for k, v in vehicle_type_dict.items()})

        # Add link length and calculate VMT
        df['linkLength'] = df['linkId'].map(network_lengths)
        df['vmt'] = df['linkLength'] * mile_conversion * df['observations'] * expansion_factor

        # Rename process column
        df.rename(columns={'emissionsProcess': 'process'}, inplace=True)

        # Melt the dataframe for pollutants
        id_cols = ['hour', 'linkId', 'tazId', 'mappedClass', 'mappedFuel',
                   'process', 'kwh', 'vmt', 'vht']

        # Efficient melt operation
        result_dfs = []
        for pollutant in pollutant_cols:
            temp_df = df[id_cols + [f'scaled_{pollutant}']].copy()
            temp_df['pollutant'] = pollutant
            temp_df['rate'] = temp_df[f'scaled_{pollutant}']
            temp_df = temp_df.drop(columns=[f'scaled_{pollutant}'])
            result_dfs.append(temp_df)

        melted = pd.concat(result_dfs, ignore_index=True)
        melted['scenario'] = scenario_name

        return melted

    # Process chunks in parallel
    result_chunks = []
    with concurrent.futures.ThreadPoolExecutor() as executor:
        futures = []

        for chunk in csv_reader:
            progress.update(chunk.nbytes)
            futures.append(executor.submit(process_chunk, chunk))

        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result is not None and not result.empty:
                result_chunks.append(result)

    progress.close()

    # Combine all chunks
    if not result_chunks:
        print("No valid data processed")
        return pd.DataFrame()

    final_result = pd.concat(result_chunks, ignore_index=True)

    # Clean up memory
    del result_chunks
    gc.collect()

    print(f"Processing completed in {time.time() - start_time:.2f} seconds")
    return final_result

def create_model_vmt_comparison_chart(skims_data, emfac_vmt, output_dir):
    """
    Create a comparison chart between EMFAC and FAMOS VMT data

    Args:
        skims_data: Processed skims data
        output_dir: Directory to save output

    Returns:
        DataFrame with combined EMFAC and FAMOS VMT data
    """
    df = emfac_vmt.copy()
    df["fuel_class"] = df["mappedFuel"] + "-" + df["mappedClass"]
    emfac_vmt = df.groupby(["fuel_class"])["total_vmt"].sum().reset_index()
    emfac_vmt.rename(columns={'total_vmt': 'mvmt'}, inplace=True)
    emfac_vmt["model"] = "emfac"

    beam_vmt = skims_data.groupby(["mappedClass", "mappedFuel"])["vmt"].sum().reset_index()
    beam_vmt["fuel_class"] = beam_vmt["mappedFuel"] + "-" + beam_vmt["mappedClass"]
    beam_vmt = beam_vmt[["fuel_class", "vmt"]].copy()
    beam_vmt.rename(columns={'vmt': 'mvmt'}, inplace=True)
    beam_vmt["model"] = "beam"
    emfac_beam_vmt = pd.concat([emfac_vmt, beam_vmt], axis=0)
    emfac_beam_vmt.to_csv(f"{output_dir}/emfac_beam_vmt_by_fuel_class.csv")
    return emfac_beam_vmt


def load_network(network_file, source_epsg):
    """
    Load and transform network data

    Args:
        network_file: Path to network CSV file
        source_epsg: Source EPSG code for coordinate transformation

    Returns:
        DataFrame with network data including transformed coordinates
    """
    print(f"Loading network data from {network_file}")

    # Read network file
    network = pd.read_csv(network_file)

    # Create transformer for coordinate conversion
    transformer = Transformer.from_crs(source_epsg, "EPSG:4326", always_xy=True)

    # Extract coordinates as numpy arrays for efficient batch processing
    from_x = network['fromLocationX'].values
    from_y = network['fromLocationY'].values
    to_x = network['toLocationX'].values
    to_y = network['toLocationY'].values

    # Transform coordinates in a batch (much faster than row-by-row)
    from_lng_lat = transformer.transform(from_x, from_y)
    to_lng_lat = transformer.transform(to_x, to_y)

    # Update the dataframe with transformed coordinates
    network['fromLocationX'] = from_lng_lat[0]  # longitude
    network['fromLocationY'] = from_lng_lat[1]  # latitude
    network['toLocationX'] = to_lng_lat[0]  # longitude
    network['toLocationY'] = to_lng_lat[1]  # latitude

    # Return only needed columns
    return network[['linkId', 'linkLength',
                    'fromLocationX', 'fromLocationY',
                    'toLocationX', 'toLocationY']]


# Helper function for parallel processing - defined outside the main function
def create_h3_polygon(h):
    """Create a Shapely polygon from an H3 cell index"""
    boundary = h3.cell_to_boundary(h)
    shapely_coords = [(lng, lat) for lat, lng in boundary]
    return Polygon(shapely_coords)


# Helper function for calculating intersections - defined outside the main function
def calculate_intersection(data):
    """Calculate intersection between an H3 cell and a network link"""
    h_cell, from_x, from_y, to_x, to_y, link_id, link_length = data

    try:
        # Create H3 cell polygon
        boundary = h3.cell_to_boundary(h_cell)
        shapely_coords = [(lng, lat) for lat, lng in boundary]
        h3_poly = Polygon(shapely_coords)

        # Create network link linestring
        line = LineString([(from_x, from_y), (to_x, to_y)])

        # Calculate intersection
        intersection = h3_poly.intersection(line)
        length_ratio = intersection.length / link_length

        return h_cell, link_id, length_ratio
    except Exception as e:
        print(f"Error in calculate_intersection: {e}")
        return h_cell, link_id, 0

def calculate_batch_intersections(batch_df, network):
    results = []
    for _, row in batch_df.iterrows():
        try:
            # Get the h3 cell boundary coordinates
            h_cell = row['h3_cell']
            boundary = h3.cell_to_boundary(h_cell)
            shapely_coords = [(lng, lat) for lat, lng in boundary]
            h3_poly = Polygon(shapely_coords)

            # Get the original line geometry
            line_id = row['linkId']
            orig_line_data = network.loc[network['linkId'] == line_id].iloc[0]
            line = LineString([
                (orig_line_data['fromLocationX'], orig_line_data['fromLocationY']),
                (orig_line_data['toLocationX'], orig_line_data['toLocationY'])
            ])

            intersection = h3_poly.intersection(line)
            results.append({
                'h3_cell': h_cell,
                'linkId': line_id,
                'intersection_length': intersection.length
            })
        except Exception as e:
            print(f"Error in calculate_intersection: {e}")
            results.append({
                'h3_cell': row['h3_cell'],
                'linkId': row['linkId'],
                'intersection_length': 0
            })
    return results


# Add this helper function at the module level, outside any other function
def process_batch(batch_network_pair):
    """Process a batch of h3 cells and network data for intersection calculation"""
    batch, network_clean = batch_network_pair
    return calculate_batch_intersections(batch, network_clean)


# Then modify the generate_h3_intersections function:
def generate_h3_intersections(network_df, resolution, output_dir):
    """
    Generate H3 cell intersections with network

    Args:
        network_df: Network dataframe
        resolution: H3 resolution
        output_dir: Directory to save output

    Returns:
        DataFrame with intersection data
    """
    # Check if output file already exists
    output_file = f'{output_dir}/network.h3.csv'
    if os.path.exists(output_file):
        print(f"Reading existing intersection data from {output_file}")
        return pd.read_csv(output_file)
    else:
        print(f"Generating new intersection data with resolution {resolution}")

    print(f"Initial network_df shape: {network_df.shape}")

    # Remove rows with NaN values in coordinate columns
    coord_columns = ['fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']
    network_clean = network_df.dropna(subset=coord_columns)
    print(f"Clean network_df shape: {network_clean.shape}")

    # Create bounding box
    lats = network_clean[['fromLocationY', 'toLocationY']].values.flatten()
    lons = network_clean[['fromLocationX', 'toLocationX']].values.flatten()

    # LatLngPoly expects coordinates as (lat, lng) pairs
    bbox_coords = [
        (min(lats), min(lons)),
        (min(lats), max(lons)),
        (max(lats), max(lons)),
        (max(lats), min(lons)),  # Close the polygon
        (min(lats), min(lons))  # Close the polygon
    ]
    bbox_poly = LatLngPoly(bbox_coords)

    # Generate H3 cells using h3shape_to_cells
    h3_cells = list(h3.h3shape_to_cells(bbox_poly, resolution))
    print(f"Number of H3 cells: {len(h3_cells)}")

    if len(h3_cells) == 0:
        print("No H3 cells created. Check your bounding box and resolution.")
        return pd.DataFrame()

    # Use parallel processing with chunks
    chunk_size = 10000
    h3_cell_geometries = []

    for i in range(0, len(h3_cells), chunk_size):
        chunk = h3_cells[i:i + chunk_size]
        with ProcessPoolExecutor() as executor:
            chunk_geometries = list(executor.map(create_h3_polygon, chunk))
        h3_cell_geometries.extend(chunk_geometries)

    h3_gdf = gpd.GeoDataFrame(
        {'h3_cell': h3_cells},
        geometry=h3_cell_geometries,
        crs="EPSG:4326"
    )

    # Create network GeoDataFrame with a spatial index
    def create_linestring(row):
        return LineString([(row['fromLocationX'], row['fromLocationY']),
                           (row['toLocationX'], row['toLocationY'])])

    network_gdf = gpd.GeoDataFrame(
        network_clean,
        geometry=network_clean.apply(create_linestring, axis=1),
        crs="EPSG:4326"
    )

    # OPTIMIZATION 3: Perform manual spatial join using R-tree index
    # Create spatial index for network geometries
    idx = index.Index()
    for i, geom in enumerate(network_gdf.geometry):
        idx.insert(i, geom.bounds)

    # Find potential intersections using the spatial index
    join_pairs = []
    for h_idx, h_geom in enumerate(h3_gdf.geometry):
        for n_idx in idx.intersection(h_geom.bounds):
            if h_geom.intersects(network_gdf.geometry.iloc[n_idx]):
                join_pairs.append((h_idx, n_idx))

    if not join_pairs:
        # Try with buffered network linestrings
        print("No intersections found with direct approach. Trying with buffered network.")
        network_gdf['buffered_geom'] = network_gdf.geometry.buffer(0.0001)

        # Update spatial index with buffered geometries
        idx = index.Index()
        for i, geom in enumerate(network_gdf['buffered_geom']):
            idx.insert(i, geom.bounds)

        # Find potential intersections using the updated spatial index
        for h_idx, h_geom in enumerate(h3_gdf.geometry):
            for n_idx in idx.intersection(h_geom.bounds):
                if h_geom.intersects(network_gdf['buffered_geom'].iloc[n_idx]):
                    join_pairs.append((h_idx, n_idx))

    if not join_pairs:
        print("No intersections found between H3 cells and network geometries.")
        return pd.DataFrame()

    # Create joined dataframe from the pairs
    h_indices, n_indices = zip(*join_pairs)
    joined = pd.DataFrame({
        'h3_cell': h3_gdf.iloc[list(h_indices)]['h3_cell'].values,
        'geometry': h3_gdf.iloc[list(h_indices)].geometry.values,
    })

    # Add network data
    for col in network_gdf.columns:
        if col != 'geometry' and col != 'buffered_geom':
            joined[col] = network_gdf.iloc[list(n_indices)][col].values

    print(f"Joined DataFrame shape after spatial join: {joined.shape}")

    # Split into batches for parallel processing
    batch_size = 1000
    batches = [joined.iloc[i:i + batch_size] for i in range(0, len(joined), batch_size)]

    # Create pairs of batch and network_clean for the process_batch function
    batch_network_pairs = [(batch, network_clean) for batch in batches]

    all_results = []
    with ProcessPoolExecutor() as executor:
        # Use the named function instead of lambda
        batch_results = list(executor.map(process_batch, batch_network_pairs))

    for batch in batch_results:
        all_results.extend(batch)

    # Convert results to DataFrame
    intersection_df = pd.DataFrame(all_results)

    # Calculate length ratios
    intersection_df = pd.merge(
        intersection_df,
        network_clean[['linkId', 'linkLength']],
        on='linkId'
    )
    intersection_df['length_ratio'] = intersection_df['intersection_length'] / intersection_df['linkLength']

    # Keep only necessary columns
    intersection_df = intersection_df[['h3_cell', 'linkId', 'length_ratio']]

    # Save results
    intersection_df.to_csv(output_file, index=False)

    return intersection_df


def process_h3_data(h3_df, data_df, data_col):
    """
    Process H3 data for a given data column

    Args:
        h3_df: H3 intersection dataframe
        data_df: Data dataframe
        data_col: Column name for data to process

    Returns:
        DataFrame with H3 cell data
    """
    print(f"Initial emissions_df shape: {data_df.shape}")

    # Filter emissions data for the specific pollutant
    data_df[data_col] = pd.to_numeric(data_df[data_col], errors='coerce')
    data_df_filtered = data_df.dropna()
    print(f"Filtered emissions shape: {data_df_filtered.shape}")

    # Merge with intersection data
    merged = pd.merge(h3_df, data_df_filtered, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'weighted_{data_col}'] = merged[data_col] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'weighted_{data_col}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result


def process_h3_emissions(emissions_df, intersection_df, pollutant):
    """
    Process H3 emissions data for a specific pollutant

    Args:
        emissions_df: Emissions dataframe
        intersection_df: H3 intersection dataframe
        pollutant: Pollutant name

    Returns:
        DataFrame with H3 cell emissions data
    """
    print(f"Initial emissions_df shape: {emissions_df.shape}")

    # Filter emissions data for the specific pollutant
    filtered_emissions = emissions_df[emissions_df['pollutant'] == pollutant][['scenario', 'linkId', 'rate']]
    filtered_emissions['rate'] = pd.to_numeric(filtered_emissions['rate'], errors='coerce')
    filtered_emissions = filtered_emissions.dropna()
    print(f"Filtered emissions shape: {filtered_emissions.shape}")

    # Merge with intersection data
    merged = pd.merge(intersection_df, filtered_emissions, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'{pollutant}'] = merged['rate'] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'{pollutant}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result


def calculate_delta_emissions(emissions_df, pollutant, scenario1, scenario2):
    """
    Calculate delta emissions between two scenarios

    Args:
        emissions_df: Emissions dataframe
        pollutant: Pollutant name
        scenario1: First scenario name
        scenario2: Second scenario name

    Returns:
        DataFrame with delta emissions data
    """
    pivot_df = emissions_df.pivot(index='h3_cell', columns='scenario', values=pollutant).reset_index()
    pivot_df = pivot_df.fillna(0)
    pivot_df["scenario"] = f"{scenario1}-{scenario2}"
    pivot_df[f'Delta_{pollutant}'] = pivot_df[scenario1] - pivot_df[scenario2]
    return pivot_df


# ################
# ##### Main #####
# ################

def main():
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
    driving_processes = ["RUNEX", "PMBW", "PMTW", "RUNLOSS", "PRDUST"]
    stationary_processes = ["STREX", "DIURN", "HOTSOAK", "RUNLOSS", "IDLEX"]
    h3_resolution = 8
    study_area_config = get_area_config(area)
    scenario_config = study_area_config["emissions"][scenario]
    run_config = scenario_config["run"]
    run_config["emissions_dir"] = f"emissions/{run_batch}"
    run_config["events_file"] = f"beam-runs/{run_batch}/{scenario}/0.events.csv.gz"
    run_config["emissions_skims_file"] = f"beam-runs/{run_batch}/{scenario}/0.skimsEmissions.csv.gz"
    run_config["link_stats_file"] = f"beam-runs/{run_batch}/{scenario}/0.linkstats.csv.gz"
    run_config["sample_portion"] = 0.1

    ###################################################################################################


    work_dir = study_area_config["work_dir"]
    output_dir = os.path.join(work_dir, run_config["output_dir"])
    utm_epsg = study_area_config["geo"]["utm_epsg"]

    # Output directories
    plot_dir = f'{output_dir}/_plots'
    Path(plot_dir).mkdir(parents=True, exist_ok=True)

    network_name = generate_network_name(study_area_config)
    network_file = f'{work_dir}/network/{network_name}/network.csv.gz'
    expansion_factor = 1 / run_config["sample_portion"]
    car_bike_fuel_map = scenario_config["mapping"]["fuel"]["emfac-pax"]
    bus_fuel_map = scenario_config["mapping"]["fuel"]["emfac-bus"]
    freight_fuel_map = scenario_config["mapping"]["fuel"]["emfac-ft"]

    # File paths
    ft_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["ft_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    pax_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["pax_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    tours_file = f"{work_dir}/{scenario_config["beam"]["tours_file"]}"
    carriers_file = f"{work_dir}/{scenario_config["beam"]["carriers_file"].replace(".csv", "--EM.csv")}"
    emissions_skims_file = f"{work_dir}/{run_config["emissions_skims_file"]}"

    # Reading files
    pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
    ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
    tours = pd.read_csv(tours_file)[["tourId", 'departureTimeInSec']]
    carriers = pd.read_csv(carriers_file)[["tourId", 'vehicleTypeId']]
    emfac_vmt = pd.read_csv(f"{output_dir}/{area}_emfac_vmt_{scenario}.csv")

    # Processing
    pax_vehicle_types = pax_vehicle_types[~pax_vehicle_types["emissionsRatesFile"].isna()].copy()
    pax_vehicle_types['mappedClass'] = pax_vehicle_types['vehicleCategory'].str.strip()
    pax_vehicle_types['fuel_key'] = pax_vehicle_types.apply(get_fuel_key, axis=1)
    bus_mask = pax_vehicle_types['vehicleCategory'] == "MediumDutyPassenger"
    car_bike_vehicle_types = pax_vehicle_types[~bus_mask].copy()
    car_bike_vehicle_types['mappedFuel'] = car_bike_vehicle_types['fuel_key'].map(car_bike_fuel_map)
    bus_vehicle_types = pax_vehicle_types[bus_mask].copy()
    bus_vehicle_types['mappedFuel'] = bus_vehicle_types['fuel_key'].map(bus_fuel_map)
    ft_vehicle_types['fuel_key'] = ft_vehicle_types.apply(get_fuel_key, axis=1)
    ft_vehicle_types['mappedFuel'] = ft_vehicle_types['fuel_key'].map(freight_fuel_map)
    ft_vehicle_types['mappedClass'] = ft_vehicle_types['vehicleCategory'].str.strip()
    vehicle_types = pd.concat([pax_vehicle_types, ft_vehicle_types], axis=0)

    tours_types_2018 = pd.merge(
        tours,
        pd.merge(
            carriers,
            ft_vehicle_types[["vehicleTypeId", 'mappedFuel', 'mappedClass']],
            on="vehicleTypeId"),
        on="tourId"
    )
    tours_types_2018["scenario"] = scenario

    print("Loading network data...")
    network = load_network(network_file, utm_epsg)
    network_h3_intersection = generate_h3_intersections(network, h3_resolution, output_dir)

    print("Processing skims data...")
    skims = read_skims_emissions_chunked(
        vehicle_types,
        network,
        emissions_skims_file,
        expansion_factor,
        scenario,
        chunk_size=1000000
    )

    print("Calculating VMT...")
    freight_vmt = skims.groupby(['scenario', 'hour', 'beamFuel', 'class'])['vmt'].sum().reset_index().copy()

    print("Creating VMT comparison with EMFAC...")
    emfac_freight_vmt = create_model_vmt_comparison_chart(skims, emfac_vmt, output_dir)

    print("Processing activities...")
    driving_process_activity = skims[
        (skims["process"].isin(driving_processes)) & (skims["vht"] > 0)
    ].groupby(["scenario", "linkId"])["vmt"].sum().reset_index(name="vmt")

    h3_vmt = process_h3_data(network_h3_intersection, driving_process_activity, "vmt")
    vmt_column = "Weighted VMT from driving activities"
    h3_vmt.rename(columns={"weighted_vmt": vmt_column}, inplace=True)

    parking_process_activity = skims[
        (skims["process"].isin(stationary_processes)) & (skims["vht"] == 0)
    ].groupby(["scenario", "linkId"]).size().reset_index(name='count')

    h3_count = process_h3_data(network_h3_intersection, parking_process_activity, "count")
    count_column = "Weighted count of parking activities"
    h3_count.rename(columns={"weighted_count": count_column}, inplace=True)

    print("Processing emissions...")
    # Process each pollutant
    pm25 = process_h3_emissions(skims, network_h3_intersection, 'PM2_5')
    nox = process_h3_emissions(skims, network_h3_intersection, 'NOx')
    co = process_h3_emissions(skims, network_h3_intersection, 'CO')
    co2 = process_h3_emissions(skims, network_h3_intersection, 'CO2')

    # Convert to grams per square meter
    pm25_column = "PM2_5 in grams per square meter"
    pm25[pm25_column] = pm25["PM2_5"] * 1e6  # from metric ton to gram

    nox_column = "NOx in grams per square meter"
    nox[nox_column] = nox["NOx"] * 1e6

    co_column = "CO in grams per square meter"
    co[co_column] = co["CO"] * 1e6

    co2_column = "CO2 in grams per square meter"
    co2[co2_column] = co2["CO2"] * 1e6

    print("Calculating delta emissions...")
    # Calculate delta emissions between scenarios
    # PM2.5 delta
    # pm25_delta = pm25.pivot(index='h3_cell', columns='scenario', values='PM2_5').reset_index()
    # pm25_delta = pm25_delta.fillna(0)
    # pm25_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # pm25_delta['Delta_PM2_5'] = pm25_delta[scenario_2050_label] - pm25_delta[scenario_2018_label]
    # pm25_delta_column = "Delta PM2_5 in grams per square meter"
    # pm25_delta[pm25_delta_column] = pm25_delta["Delta_PM2_5"] * 1e6

    # NOx delta
    # nox_delta = nox.pivot(index='h3_cell', columns='scenario', values='NOx').reset_index()
    # nox_delta = nox_delta.fillna(0)
    # nox_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # nox_delta['Delta_NOx'] = nox_delta[scenario_2050_label] - nox_delta[scenario_2018_label]
    # nox_delta_column = "Delta NOx in grams per square meter"
    # nox_delta[nox_delta_column] = nox_delta["Delta_NOx"] * 1e6

    # CO2 delta
    # co2_delta = co2.pivot(index='h3_cell', columns='scenario', values='CO2').reset_index()
    # co2_delta = co2_delta.fillna(0)
    # co2_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # co2_delta['Delta_CO2'] = co2_delta[scenario_2050_label] - co2_delta[scenario_2018_label]
    # co2_delta_column = "Delta CO2 in grams per square meter"
    # co2_delta[co2_delta_column] = co2_delta["Delta_CO2"] * 1e6

    print("Generating plots...")
    # Figure 1: Activity plots
    plot_hourly_activity(tours_types_2018, plot_dir, height_size=6)
    plot_hourly_vmt(freight_vmt, plot_dir, height_size=6)

    # Figure 2: VMT comparison
    plot_multi_pie_emfac_famos_vmt(emfac_freight_vmt, plot_dir)

    # Figure 3: Activity heatmaps
    plot_h3_heatmap(h3_vmt, vmt_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(h3_count, count_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 4: Emissions heatmaps
    plot_h3_heatmap(pm25, pm25_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(nox, nox_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(co2, co2_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 5: Hourly emissions
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'PM2_5', plot_dir, plot_legend=True, height_size=6,font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=True, height_size=6, font_size=24)

    # Figure 6: Delta emissions heatmaps
    # plot_h3_heatmap(pm25_delta, pm25_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(nox_delta, nox_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(co2_delta, co2_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)

    # Figure 7: Pollution variability
    plot_pollution_variability_by_process_vehicle_types(skims, "PM2_5", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "NOx", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "CO2", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)

    print("Processing completed successfully.")


if __name__ == "__main__":
    main()