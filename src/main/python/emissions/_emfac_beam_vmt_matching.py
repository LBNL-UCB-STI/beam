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
    ft_vehicle_types_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["ft_vehicle_types_file"])), dtype=str)
    ft_freight_mask = ft_vehicle_types_raw['vehicleCategory'].isin(beam_freight_classes)
    ft_vehicle_types_filtered = ft_vehicle_types_raw[ft_freight_mask].copy()

    # Map fuel types
    ft_vehicle_types_filtered['fuel_key'] = ft_vehicle_types_filtered.apply(get_fuel_key, axis=1)
    ft_vehicle_types_filtered['emfacFuel'] = ft_vehicle_types_filtered['fuel_key'].map(_config["fuel"])

    # Check for NA values in emfacFuel
    na_count = ft_vehicle_types_filtered['emfacFuel'].isna().sum()
    if na_count > 0:
        print(f"Warning: {na_count} NA values in emfacFuel")

    ft_vehicle_types_filtered['beamClass'] = ft_vehicle_types_filtered['vehicleCategory']
    ft_vehicle_types = ft_vehicle_types_filtered[['vehicleTypeId', 'beamClass', 'emfacFuel']].copy()

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
    vehicle_w_vmt = pd.merge(vehicle_w_vmt, ft_vehicle_types, on='vehicleTypeId', how='left')
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

    # Save results
    print(f"\nSaving results to {output_file}")
    result_df.to_csv(output_file, index=False)

    return result_df