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

    return summary, vehicle_summary

def map_emfac_to_beam_freight(_scenario, _work_dir, _emfac_vmt, _config):
    # Step 0: Check if output file already exists
    _carriers_dir = f"{_work_dir}/{os.path.dirname(_config['beam']['carriers_file'])}"
    output_file = os.path.join(_carriers_dir, f"emfac-fleet--{_scenario.replace('_', '-')}.csv")

    if os.path.exists(output_file):
        print(f"Using existing mapping file: {output_file}")
        return pd.read_csv(output_file)

    print("=== VMT-based Mapping Of BEAM Freight with EMFAC ===")

    # Step 1: Prepare Vehicle Types
    ft_vehicle_types_raw = pd.read_csv(os.path.join(_work_dir, f"{_config["beam"]["ft_vehicle_types_file"]}"), dtype=str)
    ft_freight_mask = (ft_vehicle_types_raw['vehicleCategory'].isin(beam_freight_classes))
    ft_vehicle_types_filtered = ft_vehicle_types_raw[ft_freight_mask].copy()
    ft_vehicle_types_filtered['fuel_key'] = ft_vehicle_types_filtered.apply(get_fuel_key, axis=1)
    ft_vehicle_types_filtered['emfacFuel'] = ft_vehicle_types_filtered['fuel_key'].map(_config["fuel"])
    # Check if any values in emfacFuel are NA
    if ft_vehicle_types_filtered['emfacFuel'].isna().any():
        print(f"There are {ft_vehicle_types_filtered['emfacFuel'].isna().sum()} NA values in emfacFuel")
    ft_vehicle_types_filtered['beamClass'] = ft_vehicle_types_filtered['vehicleCategory']
    ft_vehicle_types = ft_vehicle_types_filtered.drop('fuel_key', axis=1)[['vehicleTypeId', 'beamClass', 'emfacFuel']]


    # Step 2: Extract VMT proportion in BEAM Freight data
    payloads_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["payloads_file"])))
    carriers_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["carriers_file"])))
    tour_summary = calculate_tour_summary_by_vehicle(payloads_raw)
    payloads_merged = pd.merge(
        tour_summary,
        carriers_raw[['vehicleId', 'vehicleTypeId']],
        on='vehicleId',
        how='left'
    )
    vehicle_w_vmt = pd.merge(payloads_merged, ft_vehicle_types, on='vehicleTypeId', how='left')
    vehicle_w_vmt = vehicle_w_vmt.drop_duplicates(subset=['vehicleId'], keep='first')
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

    # Step 4: Calculate EMFAC VMT tracking
    emfac_w_vmt['composite_key'] = emfac_w_vmt['model_year_group'].astype(str) + ',' + \
                                   emfac_w_vmt['beamClass'] + ',' + \
                                   emfac_w_vmt['fuel']
    key_vmt_series = emfac_w_vmt.groupby('composite_key')['total_vmt'].sum()
    emfac_vmt_track = {k: v / total_emfac_vmt for k, v in key_vmt_series.items()}
    print("Top EMFAC VMT proportions:")
    for key, prop in sorted(emfac_vmt_track.items(), key=lambda x: x[1], reverse=True)[:5]:
        print(f"  {key}: {prop:.4f}")

    # Step 5: Match BEAM vehicles to EMFAC vehicles with VMT-weighted sampling
    # This step performs hierarchical matching with fallbacks:
    # 1. Try exact match (same vehicle class AND fuel type)
    # 2. Fall back to matching just fuel type if needed
    # 3. Fall back to matching just vehicle class if needed
    # 4. Last resort: use any available EMFAC vehicle
    # The process tracks VMT by composite key (year,class,fuel) to prevent overallocation
    emfac_w_vmt_fall_back = emfac_w_vmt.copy()
    beam_vmt_track = {}
    for i, (veh_type_id, veh_class, fuel, vmt, vmt_prop) in enumerate(
            vehicle_w_vmt[['vehicleTypeId', 'beamClass', 'emfacFuel', 'total_vmt', 'vmt_proportion']].values
    ):
        class_mask = emfac_w_vmt[emfac_w_vmt['beamClass'] == veh_class]
        fuel_mask = emfac_w_vmt[emfac_w_vmt['emfacFuel'] == fuel]
        full_matches = emfac_w_vmt[class_mask & fuel_mask].copy()
        if not full_matches.empty:
            sampled_match = full_matches.sample(n=1, weights='vmt_proportion').iloc[0]
            selected_emfac_id, selected_model_year = sampled_match[["emfacId", 'model_year_group']].values()
            composite_key = f"{selected_model_year},{veh_class},{fuel}"
            vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id
        else:
            fuel_matches = emfac_w_vmt[fuel_mask].copy()
            if not fuel_matches.empty:
                sampled_match = fuel_matches.sample(n=1, weights='vmt_proportion').iloc[0]
                selected_emfac_id, selected_model_year, selected_beam_class = sampled_match[
                    ["emfacId", 'model_year_group', 'beamClass']
                ].values()
                composite_key = f"{selected_model_year},{selected_beam_class},{fuel}"
                vehicle_w_vmt.loc[i, "beamClass"] = selected_beam_class
                vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id
            else:
                class_matches = emfac_w_vmt[class_mask].copy()
                if not class_matches.empty:
                    sampled_match = class_matches.sample(n=1, weights='vmt_proportion').iloc[0]
                    selected_emfac_id, selected_model_year, selected_fuel = sampled_match[
                        ["emfacId", 'model_year_group', 'fuel']
                    ].values()
                    composite_key = f"{selected_model_year},{veh_class},{selected_fuel}"
                    vehicle_w_vmt.loc[i, "emfacFuel"] = selected_fuel
                    vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id
                else:
                    sampled_match = emfac_w_vmt.sample(n=1, weights='vmt_proportion').iloc[0]
                    selected_emfac_id, selected_model_year, selected_fuel, selected_beam_class = sampled_match[
                        ["emfacId", 'model_year_group', 'fuel', 'beamClass']
                    ].values()
                    composite_key = f"{selected_model_year},{selected_beam_class},{selected_fuel}"
                    vehicle_w_vmt.loc[i, "emfacFuel"] = selected_fuel
                    vehicle_w_vmt.loc[i, "beamClass"] = selected_beam_class
                    vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id


        if composite_key not in beam_vmt_track:
            beam_vmt_track[composite_key] = 0
        beam_vmt_track[composite_key] += vmt_prop

        if beam_vmt_track[composite_key] >= emfac_vmt_track[composite_key]:
            print(f"We exhausted the composite key {composite_key} "
                  f"    with emfac vmt share of {emfac_vmt_track[composite_key]} "
                  f"    and beam freight vmt share of {beam_vmt_track[composite_key]}.")
            emfac_w_vmt = emfac_w_vmt[emfac_w_vmt["composite_key"] != composite_key]
            if emfac_w_vmt.empty:
                emfac_w_vmt = emfac_w_vmt_fall_back.copy()

    result_df = emfac_w_vmt[["vehicleId", "emfacId", "vehicleTypeId", "emfacFuel", "beamClass"]]

    # Save results
    print(f"\nSaving results to {output_file}")
    # Drop temporary columns before saving
    result_df = result_df.drop(['assigned_class'], axis=1)
    result_df.to_csv(output_file, index=False)

    return result_df