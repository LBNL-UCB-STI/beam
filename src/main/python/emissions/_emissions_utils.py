import concurrent.futures
import gc
import json
import os
import sys
import time
from collections import defaultdict

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.csv as csv
import pyarrow.csv as pv
import pandas as pd
from tqdm import tqdm
from tqdm.auto import tqdm

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import BeamClasses


def generate_emfac_beam_class_mapping(emfac_pop_by_model_year_file, vehicle_class_output_file, to_filter_out):
    """
    Creates vehicle class mapping and saves it to a JSON file if it doesn't exist.
    If the file exists, loads and returns the existing mapping.

    Args:
        to_filter_out:

    Returns:
        dict: The vehicle class mapping (either newly created or loaded from existing file)
    """
    # Check if the file already exists
    if os.path.exists(vehicle_class_output_file):
        print(f"File {vehicle_class_output_file} already exists. Loading existing mapping.")
        with open(vehicle_class_output_file, 'r') as f:
            return json.load(f)

    # Create the mapping
    mapping = {}

    table = csv.read_csv(emfac_pop_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
    df = table.to_pandas()

    for vehicle in df["vehicle_class"].unique():
        if 'Utility' in vehicle or 'Public' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'Port' in vehicle or 'POLA' in vehicle or 'POAK' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'SWCV' in vehicle or 'PTO' in vehicle or 'T6TS' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif vehicle in ['LDA', 'LDT1', 'LDT2', 'MDV']:
            mapping[vehicle] = BeamClasses.CLASS_CAR
        elif vehicle in ['MCY']:
            mapping[vehicle] = BeamClasses.CLASS_BIKE
        elif vehicle in ['UBUS']:
            mapping[vehicle] = BeamClasses.CLASS_MDP
        elif 'LHD' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_2B3_VOCATIONAL
        elif 'Class 4' in vehicle or 'Class 5' in vehicle or 'Class 6' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_456_VOCATIONAL
        elif 'Class 7' in vehicle or 'Class 8' in vehicle:
            if 'Tractor' in vehicle or 'CAIRP' in vehicle:
                mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
            else:
                mapping[vehicle] = BeamClasses.CLASS_78_VOCATIONAL
        elif "T7IS" in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
        else:
            mapping[vehicle] = "NotMatched"

    # Print category groupings
    class_groups = defaultdict(list)
    for vehicle, vehicle_class in mapping.items():
        if vehicle_class in to_filter_out:
            mapping[vehicle] = "NotMatched"
        class_groups[mapping[vehicle]].append(vehicle)
    for vehicle_class, vehicles in class_groups.items():
        print(f"Category: {vehicle_class}")
        for vehicle in vehicles:
            print(f"  - {vehicle}")

    return {k: v for k, v in mapping.items() if v != "NotMatched"}


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
    # List of pollutants to process
    pollutant_cols = ['CH4', 'CO', 'CO2', 'HC', 'NH3', 'NOx', 'PM', 'PM10', 'PM2_5', 'ROG', 'SOx', 'TOG', 'BC', 'BCm', 'BCh']

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