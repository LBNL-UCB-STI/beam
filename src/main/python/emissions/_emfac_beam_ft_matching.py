import os.path
import sys
import pandas as pd
import numpy as np
from tqdm import tqdm

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import


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
    payloads = payloads_raw[['tourId', 'vehicleId', 'payloadType']].copy()
    payloads['payloadType'] = payloads['payloadType'].astype(str)

    # Group by tour and add distance metrics
    summary = (payloads
               .groupby('tourId')['payloadType']
               .agg('|'.join)
               .reset_index())

    # Add distance metrics
    summary['total_vmt'] = summary['tourId'].map(tour_distances)
    summary['vmt_proportion'] = summary['tourId'].map(tour_proportions)

    # Final aggregation by vehicle
    vehicle_summary = summary.groupby('vehicleId').agg({
        'total_vmt': 'sum',
        'vmt_proportion': 'sum'
    })

    return vehicle_summary


def find_best_match(veh_class, fuel, df):
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
        fuel (str): EMFAC fuel type to match
        df (pandas.DataFrame): DataFrame of EMFAC vehicles with columns:
            'beamClass', 'fuel', 'model_year_group', 'emfacId', and 'vmt_proportion'

    Returns:
        dict: Matching result with the following keys:
            'match': The matched EMFAC vehicle record
            'type': Match type ('exact', 'fuel', 'class', or 'any')
            'composite_key': String key in format "{year},{class},{fuel}"
            'emfacId': EMFAC vehicle ID from the matched record
            'updates': Dictionary of fields that need to be updated in the original record
    """
    # Create boolean arrays once
    class_mask = df['beamClass'].values == veh_class
    fuel_mask = df['fuel'].values == fuel

    # Combine masks with NumPy
    full_match_mask = np.logical_and(class_mask, fuel_mask)
    full_matches = df[full_match_mask]
    if not full_matches.empty:
        match = full_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': 'exact',
            'composite_key': f"{match['model_year_group']},{veh_class},{fuel}",
            'emfacId': match['emfacId'],
            'updates': {}
        }

    # Try fuel match only
    fuel_matches = df[fuel_mask]
    if not fuel_matches.empty:
        match = fuel_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': 'fuel',
            'composite_key': f"{match['model_year_group']},{match['beamClass']},{fuel}",
            'emfacId': match['emfacId'],
            'updates': {'beamClass': match['beamClass']}
        }

    # Try class match only
    class_matches = df[class_mask]
    if not class_matches.empty:
        match = class_matches.sample(n=1, weights='vmt_proportion').iloc[0]
        return {
            'match': match,
            'type': 'class',
            'composite_key': f"{match['model_year_group']},{veh_class},{match['fuel']}",
            'emfacId': match['emfacId'],
            'updates': {'emfacFuel': match['fuel']}
        }

    # Last resort - any vehicle
    match = df.sample(n=1, weights='vmt_proportion').iloc[0]
    return {
        'match': match,
        'type': 'any',
        'composite_key': f"{match['model_year_group']},{match['beamClass']},{match['fuel']}",
        'emfacId': match['emfacId'],
        'updates': {
            'beamClass': match['beamClass'],
            'emfacFuel': match['fuel']
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
            model_year_group, beam_class, fuel = parts

            # Get VMT proportions (default to 0 if not present)
            emfac_proportion = emfac_vmt_track.get(composite_key, 0)
            beam_proportion = beam_vmt_track.get(composite_key, 0)

            # Add to respective lists
            emfac_rows.append({
                'model_year_group': model_year_group,
                'beamClass': beam_class,
                'fuel': fuel,
                'vmt_proportion': emfac_proportion
            })

            beam_rows.append({
                'model_year_group': model_year_group,
                'beamClass': beam_class,
                'fuel': fuel,
                'vmt_share': beam_proportion
            })

    emfac_df = pd.DataFrame(emfac_rows)
    beam_df = pd.DataFrame(beam_rows)

    # 1. Compare by model_year_group and beamClass
    print("\n--- VMT Comparison by Model Year and Vehicle Class ---")

    # Aggregate by year and class
    emfac_by_year_class = emfac_df.groupby(['model_year_group', 'beamClass'])['vmt_proportion'].sum().reset_index()
    beam_by_year_class = beam_df.groupby(['model_year_group', 'beamClass'])['vmt_share'].sum().reset_index()

    # Merge for comparison
    year_class_comparison = pd.merge(
        emfac_by_year_class,
        beam_by_year_class,
        on=['model_year_group', 'beamClass'],
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
              f"{row['beamClass']:^15} | "
              f"{row['vmt_proportion'] * 100:^10.2f} | "
              f"{row['vmt_share'] * 100:^10.2f} | "
              f"{row['difference'] * 100:^10.2f}")

    # 2. Compare by fuel only
    print("\n\n--- VMT Comparison by Fuel Type ---")

    # Aggregate by fuel
    emfac_by_fuel = emfac_df.groupby(['fuel'])['vmt_proportion'].sum().reset_index()
    beam_by_fuel = beam_df.groupby(['fuel'])['vmt_share'].sum().reset_index()

    # Merge for comparison
    fuel_comparison = pd.merge(
        emfac_by_fuel,
        beam_by_fuel,
        on=['fuel'],
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
        print(f"{row['fuel']:^15} | "
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


def emfac2freight_by_model_year_class_fuel(ft_emfac_vmt, carriers_raw, payloads_raw, vehicle_types_formatted):
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
        ft_emfac_vmt (pandas.DataFrame): EMFAC VMT data containing columns 'beamClass',
            'model_year_group', 'fuel', 'total_vmt', and 'emfacId' for freight fleet
        carriers_raw (pandas.DataFrame): Raw carriers data with 'vehicleId' and 'vehicleTypeId'
        payloads_raw (pandas.DataFrame): Raw payload data for calculating tour distances
        vehicle_types_formatted (pandas.DataFrame): Pre-formatted vehicle types with 'vehicleTypeId',
            'beamClass', and 'emfacFuel'

    Returns:
        pandas.DataFrame: Mapping result with columns 'vehicleId', 'emfacId', 'vehicleTypeId',
            'emfacFuel', and 'beamClass'

    Note:
        The function prints progress information and performs VMT distribution analysis
        after completing the mapping.
    """
    print("=== VMT-based Mapping Of BEAM Freight with EMFAC ===")

    # Step 1: Calculate euclidian VMT dataframe
    vehicle_summary = calculate_tour_summary_by_vehicle(payloads_raw)

    # Step 2: Merge with vehicle types
    vehicle_summary_reset = vehicle_summary.reset_index()[['vehicleId', 'total_vmt', 'vmt_proportion']]
    vehicle_w_vmt = pd.merge(
        vehicle_summary_reset,
        carriers_raw[['vehicleId', 'vehicleTypeId']],
        on='vehicleId',
        how='left',
        copy=False
    )
    vehicle_w_vmt = pd.merge(vehicle_w_vmt, vehicle_types_formatted, on='vehicleTypeId', how='left', copy=False)
    vehicle_w_vmt = vehicle_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)
    total_beam_vmt = vehicle_w_vmt['total_vmt'].sum()
    print(f"BEAM VMT with {len(vehicle_w_vmt)} rows and total vmt of {total_beam_vmt}.")

    # Step 3: Extract VMT proportion in EMFAC data
    # Optimized code
    emfac_w_vmt = ft_emfac_vmt.groupby(['beamClass', 'model_year_group', 'fuel'])['total_vmt'].sum().reset_index()
    emfac_w_vmt['vmt_proportion'] = emfac_w_vmt['total_vmt'] / emfac_w_vmt['total_vmt'].sum()
    emfac_w_vmt = emfac_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)

    total_emfac_vmt = emfac_w_vmt['total_vmt'].sum()
    print(f"EMFAC VMT with {len(ft_emfac_vmt)} rows and total vmt of {total_emfac_vmt}.")

    # Step 4: Create composite keys for tracking
    emfac_w_vmt['composite_key'] = np.char.add(
        np.char.add(
            emfac_w_vmt['model_year_group'].astype(str).values.astype('U') + ',',
            emfac_w_vmt['beamClass'].values.astype('U') + ','
        ),
        emfac_w_vmt['fuel'].values.astype('U')
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
        veh_class = row['beamClass']
        fuel = row['emfacFuel']
        vmt_prop = row['vmt_proportion']

        # Restore the full set if we've run out of options
        if emfac_w_vmt.empty:
            emfac_w_vmt = emfac_w_vmt_fallback.copy()

        # Find the best match
        result = find_best_match(veh_class, fuel, emfac_w_vmt)

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
    result_columns = ["vehicleId", "emfacId", "vehicleTypeId", "emfacFuel", "beamClass"]
    result_df = vehicle_w_vmt[result_columns]

    # Analyze VMT distribution
    analyze_vmt_distribution(beam_vmt_track, emfac_vmt_track)

    return result_df


def generate_emfac_mapped_freight_fleet(emfac_vmt, work_dir, config, freight_classes, format_func):
    """
    Create updated vehicle types and carriers files based on EMFAC mapping.

    This function performs a complete EMFAC to BEAM freight vehicle mapping workflow:
    1. Loads necessary input files (carriers, payloads, vehicle types)
    2. Formats vehicle types for EMFAC compatibility
    3. Performs the EMFAC-to-BEAM mapping process
    4. Creates new vehicle type records with EMFAC-specific IDs
    5. Updates carrier references to point to the new vehicle types
    6. Saves the updated files and reports matching statistics

    The matching process uses a hierarchical strategy to find appropriate BEAM vehicle types
    that match the EMFAC characteristics (fuel type and vehicle class). For each mapped
    vehicle, a new vehicle type ID is created that incorporates the EMFAC ID.

    Args:
        emfac_vmt (pandas.DataFrame): EMFAC VMT data with emissions characteristics
        work_dir (str): Working directory containing input files
        config (dict): Configuration dictionary with file paths and settings
        freight_classes (list): List of vehicle classes to consider as freight vehicles
        format_func (callable): Function to format vehicle types for EMFAC mapping,
            taking vehicle_types DataFrame and fuel_map dict as arguments

    Returns:
        tuple: (updated_carriers_df, updated_vehicle_types_df) containing:
            - pandas.DataFrame: Updated carriers data with new vehicle type references
            - pandas.DataFrame: New vehicle types incorporating EMFAC characteristics

    Note:
        Output files are saved with "--TrAP" suffix added to the original filenames.
        The function prints detailed statistics about the matching process.
    """
    # Prepare file paths
    carriers_file = str(os.path.join(work_dir, config["beam"]["carriers_file"]))
    payloads_file = str(os.path.join(work_dir, config["beam"]["payloads_file"]))
    vehicle_types_file = str(os.path.join(work_dir, config["beam"]["ft_vehicle_types_file"]))
    carriers_out_file = carriers_file.replace(".csv", "--TrAP.csv")
    vehicle_types_out_file = vehicle_types_file.replace(".csv", "--TrAP.csv")

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
        ].copy(),
        config["fuel"]
    )

    ft_emfac_vmt = emfac_vmt[
        ['beamClass', 'model_year_group', 'fuel', 'total_vmt', 'emfacId']
    ][
        emfac_vmt["beamClass"].isin(freight_classes)
    ].copy()

    mapping_results = emfac2freight_by_model_year_class_fuel(ft_emfac_vmt, carriers_raw, payloads_raw, vehicle_types)

    # Initialize tracking variables
    new_vehicle_types = pd.DataFrame(columns=vehicle_types_raw.columns)
    new_carriers = carriers_raw.copy()
    vehicle_type_map = {}  # Map old vehicle type IDs to new ones
    match_stats = {"fuel_and_class": 0, "fuel_only": 0, "class_only": 0, "none": 0}

    print(f"Processing {len(mapping_results)} vehicle mappings...")

    # Process each vehicle in the EMFAC mapping results
    for i, row in mapping_results.iterrows():
        mapped_vehicle_id = row["vehicleId"]
        mapped_emfac_id = row["emfacId"]
        mapped_vehicle_type_id = row["vehicleTypeId"]
        mapped_emfac_fuel = row["emfacFuel"]
        mapped_beam_class = row["beamClass"]

        # Create boolean arrays once
        fuel_mask = vehicle_types["emfacFuel"].values == mapped_emfac_fuel
        class_mask = vehicle_types["beamClass"].values == mapped_beam_class

        # Combine masks with NumPy
        full_match_mask = np.logical_and(fuel_mask, class_mask)

        # Try to find a matching record using hierarchical fallback
        if np.any(full_match_mask):
            # Best case: match on both fuel and class
            matched_indices = np.where(full_match_mask)[0]
            random_idx = np.random.choice(matched_indices, 1)[0]
            matched_record = vehicle_types.iloc[random_idx]
            match_stats["fuel_and_class"] += 1
        elif np.any(fuel_mask):
            # Fall back to matching on fuel only
            matched_indices = np.where(fuel_mask)[0]
            random_idx = np.random.choice(matched_indices, 1)[0]
            matched_record = vehicle_types.iloc[random_idx]
            match_stats["fuel_only"] += 1
        elif np.any(class_mask):
            # Fall back to matching on class only
            matched_indices = np.where(class_mask)[0]
            random_idx = np.random.choice(matched_indices, 1)[0]
            matched_record = vehicle_types.iloc[random_idx]
            match_stats["class_only"] += 1
        else:
            # Last resort: use any vehicle type (shouldn't happen with proper preprocessing)
            random_idx = np.random.choice(len(vehicle_types), 1)[0]
            matched_record = vehicle_types.iloc[random_idx]
            match_stats["none"] += 1
            print(f"  Warning: No match found for "
                  f"vehicleId={mapped_vehicle_id}, "
                  f"beamClass={mapped_beam_class}, "
                  f"emfacFuel={mapped_emfac_fuel}")

        # Create new vehicle type ID that incorporates the EMFAC ID
        old_vehicle_type_id = matched_record["vehicleTypeId"]
        new_vehicle_type_id = f"{mapped_emfac_id}--{old_vehicle_type_id}"

        # Store the mapping for later carrier updates
        vehicle_type_map[mapped_vehicle_type_id] = new_vehicle_type_id

        # Create new vehicle type record
        new_row = matched_record.copy()
        new_row["emfacId"] = mapped_emfac_id
        new_row["vehicleTypeId"] = new_vehicle_type_id

        # Add to our new vehicle types dataframe
        new_vehicle_types = pd.concat([new_vehicle_types, pd.DataFrame([new_row])], ignore_index=True)

    # Update the carriers file with new vehicle type IDs
    new_carriers["vehicleTypeId"] = new_carriers["vehicleTypeId"].map(pd.Series(vehicle_type_map)).fillna(new_carriers["vehicleTypeId"])

    # Save updated files
    print(f"\nSaving updated files to:\n  {carriers_out_file}\n  {vehicle_types_out_file}")
    new_vehicle_types.to_csv(vehicle_types_out_file, index=False)
    new_carriers.to_csv(carriers_out_file, index=False)

    # Print summary statistics
    print("\nMatch statistics:")
    print(f"  Exact matches (fuel and class): {match_stats['fuel_and_class']}")
    print(f"  Fuel-only matches: {match_stats['fuel_only']}")
    print(f"  Class-only matches: {match_stats['class_only']}")
    print(f"  No matches: {match_stats['none']}")
    print(f"  Total vehicles processed: {len(mapping_results)}")
    print(f"  Created {len(new_vehicle_types)} new vehicle types")
    print(f"  Updated {len(new_carriers)} carrier records")

    return new_carriers, new_vehicle_types