import os.path
import sys
import pandas as pd
import numpy as np

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import beam_freight_classes
from python.utils.study_area_config import get_fuel_key

def calculate_tour_summary_by_vehicle(payloads_raw):
    """
    Calculate tour distances and create a summary dataframe with distance metrics.
    Returns both the tour summary and vehicle summary.
    """
    # Sort data by tourId and sequenceRank
    df = payloads_raw.sort_values(by=['tourId', 'sequenceRank'])

    # Create shifted columns to calculate distances between consecutive points
    df['next_x'] = df.groupby('tourId')['locationX'].shift(-1)
    df['next_y'] = df.groupby('tourId')['locationY'].shift(-1)

    # Calculate distances (only where next point exists)
    mask = ~df['next_x'].isna()
    df.loc[mask, 'segment_distance'] = np.sqrt(
        (df.loc[mask, 'locationX'] - df.loc[mask, 'next_x']) ** 2 +
        (df.loc[mask, 'locationY'] - df.loc[mask, 'next_y']) ** 2
    )

    # Sum up distances by tour
    tour_distances = df.groupby('tourId')['segment_distance'].sum().to_dict()
    total_distance = sum(tour_distances.values())
    tour_proportions = {tour_id: dist / total_distance for tour_id, dist in tour_distances.items()}

    # Create summary dataframe
    payloads = payloads_raw[['payloadId', 'tourId', 'vehicleId', 'payloadType']].copy()
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
    """Find the best matching EMFAC vehicle with fallbacks"""
    # This step performs hierarchical matching with fallbacks:
    # 1. Try exact match (same vehicle class AND fuel type)
    # 2. Fall back to matching just fuel type if needed
    # 3. Fall back to matching just vehicle class if needed
    # 4. Last resort: use any available EMFAC vehicle
    # The process tracks VMT by composite key (year,class,fuel) to prevent overallocation
    # Try exact match (class AND fuel)
    class_mask = df['beamClass'] == veh_class
    fuel_mask = df['fuel'] == fuel

    full_matches = df[class_mask & fuel_mask]
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

def format_vehicle_types_for_emfac_mapping(vehicle_types, fuel_map):
    vehicle_types['fuel_key'] = vehicle_types.apply(get_fuel_key, axis=1)
    vehicle_types['emfacFuel'] = vehicle_types['fuel_key'].map(fuel_map)

    # Check for NA values in emfacFuel
    na_count = vehicle_types['emfacFuel'].isna().sum()
    if na_count > 0:
        print(f"Warning: {na_count} NA values in emfacFuel")

    vehicle_types['beamClass'] = vehicle_types['vehicleCategory']
    return vehicle_types[['vehicleTypeId', 'beamClass', 'emfacFuel']].copy()


def analyze_vmt_distribution(beam_vmt_track, emfac_vmt_track):
    """
    Analyzes and compares VMT distribution between EMFAC and mapped BEAM data.
    Prints the comparison results directly.

    Args:
        beam_vmt_track: Dictionary tracking beam VMT by composite key
        emfac_vmt_track: Dictionary tracking emfac VMT by composite key
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
        how='outer'
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


def map_emfac_to_beam_freight(_scenario, _work_dir, _emfac_vmt, _config):
    """Maps EMFAC vehicle data to BEAM freight vehicles based on VMT proportions.

    Args:
        _scenario: Scenario name
        _work_dir: Working directory
        _emfac_vmt: EMFAC VMT dataframe
        _config: Configuration dictionary

    Returns:
        DataFrame with mapped vehicle data
    """
    # Step 0: Check if output file already exists
    _carriers_dir = f"{_work_dir}/{os.path.dirname(_config['beam']['carriers_file'])}"
    output_file = os.path.join(_carriers_dir, f"emfac-fleet--{_scenario.replace('_', '-')}.csv")

    if os.path.exists(output_file):
        print(f"Using existing mapping file: {output_file}")
        return pd.read_csv(output_file)

    print("=== VMT-based Mapping Of BEAM Freight with EMFAC ===")

    # Step 1: Prepare Vehicle Types
    vehicle_types_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["ft_vehicle_types_file"])), dtype=str)
    vehicle_types = format_vehicle_types_for_emfac_mapping(
        vehicle_types_raw[vehicle_types_raw['vehicleCategory'].isin(beam_freight_classes)].copy(),
        _config["fuel"]
    )

    # Step 2: Extract VMT proportion in BEAM Freight data
    payloads_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["payloads_file"])))
    carriers_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["carriers_file"])))

    # Get vehicle summary directly
    vehicle_summary = calculate_tour_summary_by_vehicle(payloads_raw)

    # Join with carriers data to get vehicle types
    vehicle_w_vmt = pd.merge(
        vehicle_summary.reset_index(),  # Reset index to make vehicleId a column
        carriers_raw[['vehicleId', 'vehicleTypeId']],
        on='vehicleId',
        how='left'
    )

    # Merge with vehicle types
    vehicle_w_vmt = pd.merge(vehicle_w_vmt, vehicle_types, on='vehicleTypeId', how='left')
    vehicle_w_vmt = vehicle_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)

    total_beam_vmt = vehicle_w_vmt['total_vmt'].sum()
    print(f"BEAM VMT with {len(vehicle_w_vmt)} rows and total vmt of {total_beam_vmt}.")

    # Step 3: Extract VMT proportion in EMFAC data
    ft_emfac_vmt = _emfac_vmt[_emfac_vmt["beamClass"].isin(beam_freight_classes)]
    emfac_w_vmt = ft_emfac_vmt.groupby(['beamClass', 'model_year_group', 'fuel'])['total_vmt'].sum().reset_index()
    emfac_w_vmt['vmt_proportion'] = emfac_w_vmt['total_vmt'] / emfac_w_vmt['total_vmt'].sum()
    emfac_w_vmt = emfac_w_vmt.sort_values('vmt_proportion', ascending=False).reset_index(drop=True)

    total_emfac_vmt = emfac_w_vmt['total_vmt'].sum()
    print(f"EMFAC VMT with {len(ft_emfac_vmt)} rows and total vmt of {total_emfac_vmt}.")

    # Step 4: Create composite keys for tracking
    emfac_w_vmt['composite_key'] = (emfac_w_vmt['model_year_group'].astype(str) + ',' +
                                    emfac_w_vmt['beamClass'] + ',' +
                                    emfac_w_vmt['fuel'])

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

    # Perform the matching
    for i, row in vehicle_w_vmt.iterrows():
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

    # Save results
    print(f"\nSaving results to {output_file}")
    result_df.to_csv(output_file, index=False)

    return result_df


def update_vehicle_types_from_emfac_mapping(emfac_mapping_results, _work_dir, _config):
    """
    Updates vehicle types and carriers files based on EMFAC mapping results.

    This function takes the output from map_emfac_to_beam_freight and creates:
    1. A new vehicle types file with EMFAC-specific vehicle type IDs
    2. An updated carriers file that references these new vehicle types

    Args:
        emfac_mapping_results: DataFrame result from map_emfac_to_beam_freight function
        _work_dir: Working directory containing input files
        _config: Configuration dictionary with file paths

    Returns:
        tuple: (updated_carriers_df, updated_vehicle_types_df)
    """
    # Prepare file paths
    carriers_file = str(os.path.join(_work_dir, _config["beam"]["carriers_file"]))
    vehicle_types_file = str(os.path.join(_work_dir, _config["beam"]["ft_vehicle_types_file"]))
    carriers_out_file = carriers_file.replace(".csv", "--TrAP.csv")
    vehicle_types_out_file = vehicle_types_file.replace(".csv", "--TrAP.csv")

    # Load source data
    print(f"Loading data from:\n  {carriers_file}\n  {vehicle_types_file}")
    carriers_raw = pd.read_csv(carriers_file)
    vehicle_types_raw = pd.read_csv(vehicle_types_file, dtype=str)

    # Get freight vehicle types with EMFAC mappings
    vehicle_types = format_vehicle_types_for_emfac_mapping(
        vehicle_types_raw[vehicle_types_raw['vehicleCategory'].isin(beam_freight_classes)].copy()
    )

    # Initialize tracking variables
    new_vehicle_types = pd.DataFrame(columns=vehicle_types_raw.columns)
    new_carriers = carriers_raw.copy()
    vehicle_type_map = {}  # Map old vehicle type IDs to new ones
    match_stats = {"fuel_and_class": 0, "fuel_only": 0, "class_only": 0, "none": 0}

    print(f"Processing {len(emfac_mapping_results)} vehicle mappings...")

    # Process each vehicle in the EMFAC mapping results
    for i, row in emfac_mapping_results.iterrows():
        mapped_vehicle_id = row["vehicleId"]
        mapped_emfac_id = row["emfacId"]
        mapped_vehicle_type_id = row["vehicleTypeId"]
        mapped_emfac_fuel = row["emfacFuel"]
        mapped_beam_class = row["beamClass"]

        # Prepare masks for matching
        fuel_mask = vehicle_types["emfacFuel"] == mapped_emfac_fuel
        class_mask = vehicle_types["beamClass"] == mapped_beam_class

        # Try to find a matching record using hierarchical fallback
        if (fuel_mask & class_mask).any():
            # Best case: match on both fuel and class
            matched_record = vehicle_types[fuel_mask & class_mask].sample(n=1).iloc[0]
            match_stats["fuel_and_class"] += 1
        elif fuel_mask.any():
            # Fall back to matching on fuel only
            matched_record = vehicle_types[fuel_mask].sample(n=1).iloc[0]
            match_stats["fuel_only"] += 1
        elif class_mask.any():
            # Fall back to matching on class only
            matched_record = vehicle_types[class_mask].sample(n=1).iloc[0]
            match_stats["class_only"] += 1
        else:
            # Last resort: use any vehicle type (shouldn't happen with proper preprocessing)
            matched_record = vehicle_types.sample(n=1).iloc[0]
            match_stats["none"] += 1
            print(f"  Warning: No match found for vehicleId={mapped_vehicle_id}, beamClass={mapped_beam_class}, emfacFuel={mapped_emfac_fuel}")

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
    for old_id, new_id in vehicle_type_map.items():
        new_carriers.loc[new_carriers["vehicleTypeId"] == old_id, "vehicleTypeId"] = new_id

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
    print(f"  Total vehicles processed: {len(emfac_mapping_results)}")
    print(f"  Created {len(new_vehicle_types)} new vehicle types")
    print(f"  Updated {len(new_carriers)} carrier records")

    return new_carriers, new_vehicle_types