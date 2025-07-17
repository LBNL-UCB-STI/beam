import os.path
import sys

import numpy as np
import pandas as pd
from tqdm import tqdm

from utils.files_utils import sanitize_name

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.files_utils import sanitize_name

def calculate_tour_summary_by_vehicle(payloads_raw):
    """
    Calculate tour distances and generate vehicle-level VMT summary statistics.

    This function processes raw payload data to compute tour distances based on
    sequential coordinates, then aggregates these distances to the vehicle level.
    It calculates both absolute VMT values and proportional VMT shares.

    Args:
        payloads_raw (pandas.DataFrame): DataFrame containing payload records with
            columns: 'tourId', 'sequenceRank', 'locationX', 'locationY', 'payloadId',
            'vehicleId', and 'payloadType'.

    Returns:
        pandas.DataFrame: Vehicle-level summary with total VMT and VMT proportion.
            The DataFrame is indexed by vehicleId with columns 'total_vmt' and
            'vmt_proportion'.

    Note:
        Distances are calculated using Euclidean distance between consecutive
        locations within each tour.
    """
    # Sort data by tourId and sequenceRank
    df = payloads_raw.sort_values(by=['tourId', 'sequenceRank'])

    # Create shifted columns to calculate distances between consecutive points
    df['next_x'] = df.groupby('tourId')['locationX'].shift(-1)
    df['next_y'] = df.groupby('tourId')['locationY'].shift(-1)

    # Calculate distances (only where next point exists)
    # Create arrays from DataFrame columns for faster operations
    x1 = df['locationX'].values
    y1 = df['locationY'].values
    x2 = df['next_x'].values
    y2 = df['next_y'].values
    mask = ~np.isnan(x2)

    # Calculate distances using NumPy operations
    distances = np.zeros(len(df))
    distances[mask] = np.sqrt((x1[mask] - x2[mask]) ** 2 + (y1[mask] - y2[mask]) ** 2)

    # Assign back to DataFrame
    df['segment_distance'] = distances

    # Sum up distances by tour
    tour_distances = df.groupby('tourId')['segment_distance'].sum().to_dict()
    total_distance = sum(tour_distances.values())
    tour_proportions = {tour_id: dist / total_distance for tour_id, dist in tour_distances.items()}

    # Create summary dataframe
    payloads = payloads_raw[['tourId', 'payloadType']].copy()
    payloads['payloadType'] = payloads['payloadType'].astype(str)

    # Group by tour and add distance metrics
    summary = (payloads
               .groupby('tourId')['payloadType']
               .agg('|'.join)
               .reset_index())

    # Add distance metrics
    summary['total_vmt'] = summary['tourId'].map(tour_distances)
    summary['vmt_proportion'] = summary['tourId'].map(tour_proportions)

    return summary


def find_best_match(veh_class, veh_fuel, alternatives_mapping, df):
    """
    Find the best matching EMFAC vehicle record using a hierarchical matching strategy.

    This function implements a four-tier fallback approach to match BEAM vehicles
    with EMFAC vehicles:
    1. Exact match: Same vehicle class AND fuel type
    2. Fuel-only match: Matching fuel type, any vehicle class
    3. Class-only match: Matching vehicle class, any fuel type
    4. Any-match: Random selection if no other matches are found

    The VMT-weighted sampling ensures that more common vehicle configurations
    in the EMFAC dataset are more likely to be selected as matches.

    Args:
        veh_class (str): BEAM vehicle class to match
        veh_fuel (str): EMFAC fuel type to match
        df (pandas.DataFrame): DataFrame of EMFAC vehicles with columns:
            'mappedClass', 'fuel', 'model_year_group', 'emfacId', and 'vmt_proportion'

    Returns:
        dict: Matching result with the following keys:
            'match': The matched EMFAC vehicle record
            'type': Match type ('exact', 'fuel', 'class', or 'any')
            'composite_key': String key in format "{year},{class},{fuel}"
            'emfacId': EMFAC vehicle ID from the matched record
            'updates': Dictionary of fields that need to be updated in the original record
    """
    # Create boolean arrays once
    class_mask = df['mappedClass'].values == veh_class
    approx_class_mask = df['mappedClass'].isin(alternatives_mapping[veh_class])
    fuel_mask = df['mappedFuel'].values == veh_fuel
    approx_fuel_mask = df['mappedFuel'].isin(alternatives_mapping[veh_fuel])

    # Combine masks with NumPy
    full_match_mask = np.logical_and(class_mask, fuel_mask)
    full_matches = df[full_match_mask]
    match_type = "type"
    if full_matches.empty:
        full_matches = df[np.logical_and(class_mask, approx_fuel_mask)]
        match_type = "exact-approx-fuel"
    if full_matches.empty:
        full_matches = df[np.logical_and(approx_class_mask, fuel_mask)]
        match_type = "exact-approx-class"
    if full_matches.empty:
        full_matches = df[np.logical_and(approx_class_mask, approx_fuel_mask)]
        match_type = "approx-class-fuel"

    if not full_matches.empty:
        match = full_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': match_type,
            'composite_key': f"{match['model_year_group']},{match['mappedClass']},{match['mappedFuel']}",
            'emfacId': match['emfacId'],
            'updates': {}
        }

    # Try fuel match only
    fuel_matches = df[fuel_mask]
    match_type = "fuel"
    if fuel_matches.empty:
        fuel_matches = df[approx_fuel_mask]
        match_type = "approx-fuel"

    if not fuel_matches.empty:
        match = fuel_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': match_type,
            'composite_key': f"{match['model_year_group']},{match['mappedClass']},{match['mappedFuel']}",
            'emfacId': match['emfacId'],
            'updates': {'mappedClass': match['mappedClass']}
        }



    # Try class match only
    class_matches = df[class_mask]
    match_type = 'class'
    if class_matches.empty:
        class_matches = df[approx_class_mask]
        match_type = "approx-class"

    if not class_matches.empty:
        match = class_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': match_type,
            'composite_key': f"{match['model_year_group']},{match['mappedClass']},{match['mappedFuel']}",
            'emfacId': match['emfacId'],
            'updates': {'mappedFuel': match['mappedFuel']}
        }

    # Last resort - any vehicle
    match = df.sample(n=1, weights='vmt_proportion').iloc[0]
    return {
        'match': match,
        'type': 'any',
        'composite_key': f"{match['model_year_group']},{match['mappedClass']},{match['mappedFuel']}",
        'emfacId': match['emfacId'],
        'updates': {
            'mappedClass': match['mappedClass'],
            'mappedFuel': match['mappedFuel']
        }
    }


def analyze_vmt_distribution(beam_vmt_track, emfac_vmt_track):
    """
    Analyze and compare VMT distributions between EMFAC and BEAM data after mapping.

    This function takes the VMT tracking dictionaries from the mapping process
    and generates detailed comparative analysis in two dimensions:
    1. By model year group and vehicle class
    2. By fuel type only

    For each comparison, the function:
    - Creates DataFrames from the tracking dictionaries
    - Aggregates VMT proportions by the relevant dimensions
    - Calculates absolute and percentage differences
    - Prints formatted tables of the most significant differences
    - Reports summary statistics on the overall distribution match

    Args:
        beam_vmt_track (dict): Dictionary with composite keys (year,class,fuel) mapping
            to BEAM VMT proportions
        emfac_vmt_track (dict): Dictionary with composite keys (year,class,fuel) mapping
            to EMFAC VMT proportions

    Returns:
        None: Results are printed to standard output

    Note:
        The function expects composite keys in the format "year,class,fuel" and
        will parse these to create structured DataFrames for comparison.
    """
    print("\n=== VMT Distribution Analysis ===")

    # Create DataFrames from tracking dictionaries
    emfac_rows = []
    beam_rows = []

    for composite_key in set(list(beam_vmt_track.keys()) + list(emfac_vmt_track.keys())):
        parts = composite_key.split(',')
        if len(parts) == 3:
            model_year_group, mapped_class, mapped_fuel = parts

            # Get VMT proportions (default to 0 if not present)
            emfac_proportion = emfac_vmt_track.get(composite_key, 0)
            beam_proportion = beam_vmt_track.get(composite_key, 0)

            # Add to respective lists
            emfac_rows.append({
                'model_year_group': model_year_group,
                'mappedClass': mapped_class,
                'mappedFuel': mapped_fuel,
                'vmt_proportion': emfac_proportion
            })

            beam_rows.append({
                'model_year_group': model_year_group,
                'mappedClass': mapped_class,
                'mappedFuel': mapped_fuel,
                'vmt_share': beam_proportion
            })

    emfac_df = pd.DataFrame(emfac_rows)
    beam_df = pd.DataFrame(beam_rows)

    # 1. Compare by model_year_group and mappedClass
    print("\n--- VMT Comparison by Model Year and Vehicle Class ---")

    # Aggregate by year and class
    emfac_by_year_class = emfac_df.groupby(['model_year_group', 'mappedClass'])['vmt_proportion'].sum().reset_index()
    beam_by_year_class = beam_df.groupby(['model_year_group', 'mappedClass'])['vmt_share'].sum().reset_index()

    # Merge for comparison
    year_class_comparison = pd.merge(
        emfac_by_year_class,
        beam_by_year_class,
        on=['model_year_group', 'mappedClass'],
        how='outer',
        copy=False
    ).fillna(0)

    # Calculate differences
    year_class_comparison['difference'] = year_class_comparison['vmt_proportion'] - year_class_comparison['vmt_share']
    year_class_comparison['abs_difference'] = abs(year_class_comparison['difference'])

    # Sort by absolute difference and get top 10
    top_diff = year_class_comparison.sort_values('abs_difference', ascending=False).head(10)

    # Print table header
    print("\nTop 10 VMT Proportion Differences by Year and Class:")
    print("------------------------------------------------------------------")
    print(f"{'Year':^10} | {'Class':^15} | {'EMFAC %':^10} | {'BEAM %':^10} | {'Diff %':^10}")
    print("------------------------------------------------------------------")

    # Print each row with formatting
    for _, row in top_diff.iterrows():
        print(f"{row['model_year_group']:^10} | "
              f"{row['mappedClass']:^15} | "
              f"{row['vmt_proportion'] * 100:^10.2f} | "
              f"{row['vmt_share'] * 100:^10.2f} | "
              f"{row['difference'] * 100:^10.2f}")

    # 2. Compare by fuel only
    print("\n\n--- VMT Comparison by Fuel Type ---")

    # Aggregate by fuel
    emfac_by_fuel = emfac_df.groupby(['mappedFuel'])['vmt_proportion'].sum().reset_index()
    beam_by_fuel = beam_df.groupby(['mappedFuel'])['vmt_share'].sum().reset_index()

    # Merge for comparison
    fuel_comparison = pd.merge(
        emfac_by_fuel,
        beam_by_fuel,
        on=['mappedFuel'],
        how='outer'
    ).fillna(0)

    # Calculate differences
    fuel_comparison['difference'] = fuel_comparison['vmt_proportion'] - fuel_comparison['vmt_share']
    fuel_comparison['abs_difference'] = abs(fuel_comparison['difference'])

    # Sort by absolute difference
    fuel_comparison = fuel_comparison.sort_values('abs_difference', ascending=False)

    # Print table header
    print("\nVMT Proportion Differences by Fuel Type:")
    print("------------------------------------------------------------------")
    print(f"{'Fuel Type':^15} | {'EMFAC %':^10} | {'BEAM %':^10} | {'Diff %':^10}")
    print("------------------------------------------------------------------")

    # Print each row with formatting
    for _, row in fuel_comparison.iterrows():
        print(f"{row['mappedFuel']:^15} | "
              f"{row['vmt_proportion'] * 100:^10.2f} | "
              f"{row['vmt_share'] * 100:^10.2f} | "
              f"{row['difference'] * 100:^10.2f}")

    # Print summary stats
    print("\n--- Summary Statistics ---")
    print(f"Total model year/class combinations: {len(year_class_comparison)}")
    print(f"Total fuel types: {len(fuel_comparison)}")
    print(f"Max absolute difference by year/class: {year_class_comparison['abs_difference'].max() * 100:.2f}%")
    print(f"Max absolute difference by fuel: {fuel_comparison['abs_difference'].max() * 100:.2f}%")
    print(f"Average absolute difference by year/class: {year_class_comparison['abs_difference'].mean() * 100:.2f}%")
    print(f"Average absolute difference by fuel: {fuel_comparison['abs_difference'].mean() * 100:.2f}%")


def emfac2freight_by_model_year_class_fuel(ft_emfac_vmt, carriers_raw, payloads_raw, vehicle_types_formatted, alternatives_mapping):
    """
    Map EMFAC vehicle data to BEAM freight vehicles based on VMT proportions and vehicle attributes.

    This function performs a comprehensive matching process between EMFAC's emissions database
    and BEAM's freight vehicle fleet. It uses VMT (vehicle miles traveled) proportions as a key
    metric to ensure the distribution of vehicle types in the mapped result preserves the original
    EMFAC emissions characteristics.

    The matching process:
    1. Calculates VMT for each BEAM freight vehicle from tour payload data
    2. Extracts VMT proportions from the EMFAC data by vehicle class, model year, and fuel type
    3. Creates composite tracking keys in the format "year,class,fuel" for comparison
    4. Matches each BEAM vehicle to an appropriate EMFAC vehicle using a hierarchical strategy
    5. Tracks VMT allocation to prevent overallocation of specific vehicle configurations
    6. Analyzes and reports on the resulting VMT distribution match quality

    Args:
        ft_emfac_vmt (pandas.DataFrame): EMFAC VMT data containing columns 'mappedClass',
            'model_year_group', 'fuel', 'total_vmt', and 'emfacId' for freight fleet
        carriers_raw (pandas.DataFrame): Raw carriers data with 'vehicleId' and 'vehicleTypeId'
        payloads_raw (pandas.DataFrame): Raw payload data for calculating tour distances
        vehicle_types_formatted (pandas.DataFrame): Pre-formatted vehicle types with 'vehicleTypeId',
            'mappedClass', and 'mappedFuel'

    Returns:
        pandas.DataFrame: Mapping result with columns 'vehicleId', 'emfacId', 'vehicleTypeId',
            'mappedFuel', and 'mappedClass'

    Note:
        The function prints progress information and performs VMT distribution analysis
        after completing the mapping.
    """
    print("=== VMT-based Mapping Of BEAM Freight with EMFAC ===")

    # Step 1: Calculate euclidian VMT dataframe
    tour_summary = calculate_tour_summary_by_vehicle(payloads_raw)

    # Step 2: Merge with vehicle types
    vehicle_w_vmt = pd.merge(
        tour_summary,
        carriers_raw[['tourId', 'vehicleId', 'vehicleTypeId']],
        on='tourId',
        how='left',
        copy=False
    ).groupby('vehicleId').agg({
        'total_vmt': 'sum',
        'vmt_proportion': 'sum',
        'vehicleTypeId': 'first'
    }).reset_index()
    vehicle_w_vmt = pd.merge(vehicle_w_vmt, vehicle_types_formatted, on='vehicleTypeId', how='left', copy=False)
    vehicle_w_vmt = vehicle_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)
    total_beam_vmt = vehicle_w_vmt['total_vmt'].sum()
    print(f"BEAM VMT with {len(vehicle_w_vmt)} rows and total vmt of {total_beam_vmt}.")

    # Step 3: Extract VMT proportion in EMFAC data
    # Optimized code
    emfac_w_vmt = ft_emfac_vmt.groupby(['mappedClass', 'model_year_group', 'mappedFuel', 'emfacId'])['total_vmt'].sum().reset_index()
    emfac_w_vmt['vmt_proportion'] = emfac_w_vmt['total_vmt'] / emfac_w_vmt['total_vmt'].sum()
    emfac_w_vmt = emfac_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)

    total_emfac_vmt = emfac_w_vmt['total_vmt'].sum()
    print(f"EMFAC VMT with {len(ft_emfac_vmt)} rows and total vmt of {total_emfac_vmt}.")

    # Step 4: Create composite key for tracking
    emfac_w_vmt['composite_key'] = (
            emfac_w_vmt['model_year_group'].astype(str) + ',' +
            emfac_w_vmt['mappedClass'] + ',' +
            emfac_w_vmt['mappedFuel']
    )

    key_vmt_series = emfac_w_vmt.groupby('composite_key')['total_vmt'].sum()
    emfac_vmt_track = {k: v / total_emfac_vmt for k, v in key_vmt_series.items()}

    # Print top EMFAC VMT proportions
    print("Top EMFAC VMT proportions:")
    for key, prop in sorted(emfac_vmt_track.items(), key=lambda x: x[1], reverse=True)[:5]:
        print(f"  {key}: {prop:.4f}")

    # Step 5: Match BEAM vehicles to EMFAC vehicles with VMT-weighted sampling
    # Initialize tracking
    emfac_w_vmt_fallback = emfac_w_vmt.copy()
    beam_vmt_track = {}
    vehicle_w_vmt['assigned_class'] = ""  # Track matching strategy

    # Create progress bar
    total_vehicles = len(vehicle_w_vmt)
    print(f"Matching {total_vehicles} vehicles to EMFAC records...")

    # Perform the matching
    for i, row in tqdm(vehicle_w_vmt.iterrows(), total=total_vehicles, desc="Matching vehicles"):
        veh_class = row['mappedClass']
        veh_fuel = row['mappedFuel']
        vmt_prop = row['vmt_proportion']

        # Restore the full set if we've run out of options
        if emfac_w_vmt.empty:
            emfac_w_vmt = emfac_w_vmt_fallback.copy()

        # Find the best match
        result = find_best_match(
            veh_class,
            veh_fuel,
            alternatives_mapping,
            emfac_w_vmt
        )

        if result['type'] == "any":
            print(result)

        # Apply updates
        vehicle_w_vmt.loc[i, "emfacId"] = result['emfacId']
        vehicle_w_vmt.loc[i, "assigned_class"] = result['type']

        for key, value in result['updates'].items():
            vehicle_w_vmt.loc[i, key] = value

        # Track VMT allocation
        composite_key = result['composite_key']
        beam_vmt_track[composite_key] = beam_vmt_track.get(composite_key, 0) + vmt_prop

        # Check if we've exhausted this composite key
        if beam_vmt_track[composite_key] >= emfac_vmt_track[composite_key]:
            print(f"Exhausted composite key {composite_key}: "
                  f"emfac={emfac_vmt_track[composite_key]:.4f}, "
                  f"beam={beam_vmt_track[composite_key]:.4f}")

            # Remove this composite key from available options
            emfac_w_vmt = emfac_w_vmt[emfac_w_vmt["composite_key"] != composite_key]

    # Prepare the final result
    result_columns = ["vehicleId", "emfacId", "vehicleTypeId", "mappedFuel", "mappedClass"]
    result_df = vehicle_w_vmt[result_columns]

    # Analyze VMT distribution
    analyze_vmt_distribution(beam_vmt_track, emfac_vmt_track)

    return result_df


def process_emfac_mappings(mapping_results, vehicle_types, vehicle_types_raw):
    """
    Process EMFAC mapping results to create new vehicle types with optimized performance.

    Args:
        mapping_results (pd.DataFrame): Results of EMFAC-to-BEAM mapping
        vehicle_types (pd.DataFrame): Formatted vehicle types for matching
        vehicle_types_raw (pd.DataFrame): Original vehicle types with all columns

    Returns:
        tuple: (new_fleet, vehicle_type_map)
    """
    from tqdm import tqdm

    print(f"Processing {len(mapping_results)} vehicle mappings...")

    # Initialize tracking variables
    match_stats = {"fuel_and_class": 0, "fuel_only": 0, "class_only": 0, "none": 0}

    # For reproducibility
    np.random.seed(42)

    # Step 1: Create lookup dataframes for each matching strategy
    print("Building lookup tables for matching...")
    match_keys = []

    # Get all unique combinations from mapping_results
    unique_fuel_class_combos = mapping_results[['mappedFuel', 'mappedClass']].drop_duplicates().reset_index(drop=True)

    # For each unique combination, find matching vehicle types
    for _, combo in tqdm(unique_fuel_class_combos.iterrows(),
                         total=len(unique_fuel_class_combos),
                         desc="Building match tables"):
        mapped_fuel = combo['mappedFuel']
        mapped_class = combo['mappedClass']

        # Find matching indices for this combination
        both_match = vehicle_types[(vehicle_types['mappedFuel'] == mapped_fuel) &
                                   (vehicle_types['mappedClass'] == mapped_class)]

        fuel_match = vehicle_types[vehicle_types['mappedFuel'] == mapped_fuel]
        class_match = vehicle_types[vehicle_types['mappedClass'] == mapped_class]

        # Store the match keys for later use
        match_keys.append({
            'mappedFuel': mapped_fuel,
            'mappedClass': mapped_class,
            'both_match': both_match['vehicleTypeId'].tolist() if not both_match.empty else [],
            'fuel_match': fuel_match['vehicleTypeId'].tolist() if not fuel_match.empty else [],
            'class_match': class_match['vehicleTypeId'].tolist() if not class_match.empty else []
        })

    # Convert to dataframe for easier joining
    match_keys_df = pd.DataFrame(match_keys)

    # Step 2: Join mapping results with match keys
    print("Joining mapping results with match keys...")
    merged_data = pd.merge(
        mapping_results,
        match_keys_df,
        on=['mappedFuel', 'mappedClass'],
        how='left'
    )

    # Step 3: Vectorized creation of new records
    print("Applying matching strategy...")
    # Prepare to collect results
    vehicle_type_map = {}
    new_rows = []

    # Allocate arrays to determine match type for each record
    match_type = np.full(len(merged_data), 'none', dtype=object)
    match_vehicle_type_id = np.full(len(merged_data), None, dtype=object)

    # Apply matching strategy in order of preference: both > fuel > class > random
    for i, row in tqdm(merged_data.iterrows(),
                       total=len(merged_data),
                       desc="Finding matches"):
        if row['both_match']:
            # Match on both fuel and class
            match_vehicle_type_id[i] = np.random.choice(row['both_match'])
            match_type[i] = 'fuel_and_class'
            match_stats['fuel_and_class'] += 1
        elif row['fuel_match']:
            # Fall back to matching on fuel only
            match_vehicle_type_id[i] = np.random.choice(row['fuel_match'])
            match_type[i] = 'fuel_only'
            match_stats['fuel_only'] += 1
        elif row['class_match']:
            # Fall back to matching on class only
            match_vehicle_type_id[i] = np.random.choice(row['class_match'])
            match_type[i] = 'class_only'
            match_stats['class_only'] += 1
        else:
            # Last resort: use any vehicle type
            match_vehicle_type_id[i] = np.random.choice(vehicle_types['vehicleTypeId'].values)
            match_type[i] = 'none'
            match_stats['none'] += 1
            print(f"  Warning: No match found for vehicleId={row['vehicleId']}, "
                  f"mappedClass={row['mappedClass']}, mappedFuel={row['mappedFuel']}")

    # Step 4: Create new vehicle types in a vectorized way
    print("Creating new vehicle types...")
    for i, row in tqdm(merged_data.iterrows(),
                       total=len(merged_data),
                       desc="Creating vehicle types"):
        mapped_vehicle_id = row['vehicleId']
        mapped_emfac_id = row['emfacId']
        old_vehicle_type_id = match_vehicle_type_id[i]

        # Create new vehicle type ID that incorporates the EMFAC ID
        old_vehicle_type_id_formatted = sanitize_name(old_vehicle_type_id).replace("_", "")
        new_vehicle_type_id = f"{mapped_emfac_id}--{old_vehicle_type_id_formatted}"

        # Store the mapping for later carrier updates
        vehicle_type_map[mapped_vehicle_id] = new_vehicle_type_id

        # Get the original record from vehicle_types_raw
        original_record = vehicle_types_raw[vehicle_types_raw['vehicleTypeId'] == old_vehicle_type_id].iloc[
            0].copy()
        original_record['emfacId'] = mapped_emfac_id
        original_record['vehicleTypeId'] = new_vehicle_type_id
        new_rows.append(original_record)

    # Create dataframe from collected rows
    print("Finalizing results...")
    new_fleet = pd.DataFrame(new_rows)

    # Print summary statistics
    print("\nMatch statistics:")
    print(f"  Exact matches (fuel and class): {match_stats['fuel_and_class']}")
    print(f"  Fuel-only matches: {match_stats['fuel_only']}")
    print(f"  Class-only matches: {match_stats['class_only']}")
    print(f"  No matches: {match_stats['none']}")
    print(f"  Total vehicles processed: {len(mapping_results)}")
    print(f"  Created {len(new_fleet)} new vehicle types")

    return new_fleet, vehicle_type_map


def generate_emfac_mapped_freight_fleet(emfac_vmt, freight_classes, work_dir, config, format_func):
    """
    Create updated vehicle types and carriers files based on EMFAC mapping.

    This function performs a complete EMFAC to BEAM freight vehicle mapping workflow:
    1. Loads necessary input files (carriers, payloads, vehicle types)
    2. Formats vehicle types for EMFAC compatibility
    3. Performs the EMFAC-to-BEAM mapping process
    4. Creates new vehicle type records with EMFAC-specific IDs
    5. Updates carrier references to point to the new vehicle types

    Args:
        emfac_vmt (pandas.DataFrame): EMFAC VMT data with emissions characteristics
        freight_classes (list): List of vehicle classes to consider as freight vehicles
        format_func (callable): Function to format vehicle types for EMFAC mapping
        work_dir (str): Working directory containing input files
        config (dict): Configuration dictionary with file paths and settings

    Returns:
        tuple: (updated_carriers_df, updated_vehicle_types_df)
    """
    # Prepare file paths
    carriers_file = str(os.path.join(work_dir, config["beam"]["carriers_file"]))
    payloads_file = str(os.path.join(work_dir, config["beam"]["payloads_file"]))
    vehicle_types_file = str(os.path.join(work_dir, config["beam"]["ft_vehicle_types_file"]))

    # Load source data
    print(f"Loading data from:\n  {carriers_file}\n  {vehicle_types_file}")
    carriers_raw = pd.read_csv(carriers_file)
    payloads_raw = pd.read_csv(payloads_file)
    vehicle_types_raw = pd.read_csv(vehicle_types_file, dtype=str)

    # Get freight vehicle types with EMFAC mappings
    vehicle_types = format_func(
        vehicle_types_raw.loc[
            vehicle_types_raw['vehicleCategory'].isin(freight_classes),
            ['vehicleTypeId', 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']
        ].copy()
    )

    # Filter EMFAC VMT data to freight classes
    ft_emfac_vmt = emfac_vmt[['mappedClass', 'model_year_group', 'mappedFuel', 'total_vmt', 'emfacId']][
        emfac_vmt["mappedClass"].isin(freight_classes)
    ].copy()

    fuel_class_alternative_mapping = config["mapping"]["fuel"]["alternatives"] | config["mapping"]["class"]["alternatives"]

    # Get mapping between EMFAC and freight vehicles
    mapping_results = emfac2freight_by_model_year_class_fuel(
        ft_emfac_vmt,
        carriers_raw,
        payloads_raw,
        vehicle_types,
        fuel_class_alternative_mapping
    )

    # Process mappings to create new vehicle types
    new_fleet, vehicle_type_map = process_emfac_mappings(
        mapping_results,
        vehicle_types,
        vehicle_types_raw
    )

    # Update the carriers file with new vehicle type IDs
    new_carriers = carriers_raw.copy()
    new_carriers["vehicleTypeId"] = new_carriers["vehicleId"].map(
        pd.Series(vehicle_type_map)).fillna(new_carriers["vehicleTypeId"])

    print(f"Updated {len(new_carriers)} carrier records")

    # Group by all columns except vehicleId, dropping the vehicleId column
    new_vehicle_types = new_fleet[[col for col in new_fleet.columns if col != 'vehicleId']].drop_duplicates()

    return new_carriers, new_vehicle_types
