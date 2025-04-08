import os
import re
import sys

import pandas as pd
import numpy as np
from tqdm import tqdm

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)


def parse_sample_probability_string(prob_string):
    """
    Parse a sample probability string to extract income and ridehail probabilities.

    The expected format is "income|<income_bin>:<probability>; ridehail|<probability>"
    For example: "income|25-50:0.250000; ridehail|0.200000"

    Args:
        prob_string (str): The probability string to parse

    Returns:
        tuple: A tuple containing:
            - income_bin (str or None): The income bin/range (e.g., '25-50')
            - income_prob (float or None): The probability associated with this income bin
            - ridehail_prob (float or None): The ridehail probability
    """
    # Early return for empty strings or NaN values
    if pd.isna(prob_string) or prob_string == "":
        return None, None, None

    # Remove spaces and convert to lowercase in one step
    cleaned = prob_string.replace(" ", "").lower()

    # Use regex for faster parsing with compile once pattern
    income_match = re.search(r"income\|([^:]+):([0-9.]+)", cleaned)
    ridehail_match = re.search(r"ridehail\|([^:]+):([0-9.]+)", cleaned)

    # Extract values from matches
    income_bin = income_match.group(1) if income_match else None
    income_prob = float(income_match.group(2)) if income_match else None
    ridehail_bin = ridehail_match.group(1) if ridehail_match else None
    ridehail_prob = float(ridehail_match.group(2)) if ridehail_match else None

    return income_bin, income_prob, ridehail_bin, ridehail_prob


def create_sample_probability_string(income_bin, income_prob, ridehail_bin, ridehail_prob):
    """
    Convert income bin, income probability, and ridehail probability back to a sample probability string.

    This function is the inverse of parse_sample_probability_string.
    The resulting string will be in the format: "income|<income_bin>:<probability>; ridehail|<probability>"

    Args:
        income_bin (str or None): Income bin/range (e.g., '25-50', '50-75')
        income_prob (float or None): Income probability value
        ridehail_prob (float or None): Ridehail probability value

    Returns:
        str: Formatted sample probability string
              Example: "income|25-50:0.250000; ridehail|0.200000"
              Returns empty string if all inputs are None
    """
    # Quick return for empty data
    if income_bin is None and income_prob is None and ridehail_bin is None and ridehail_prob is None:
        return ""

    # Pre-allocate list with appropriate size to avoid resizing
    parts = []

    # Build parts directly
    if income_bin is not None and income_prob is not None:
        parts.append(f"income|{income_bin}:{income_prob:.6f}")

    if ridehail_bin is not None and ridehail_prob is not None:
        parts.append(f"ridehail|{ridehail_bin}:{ridehail_prob:.6f}")

    # Use faster string joining
    return "; ".join(parts)


def process_vehicle_types_probabilities_by_vehicle_category_and_income_group(vehicle_types):
    """
    Process vehicle types data by extracting and normalizing probability distributions.

    This function:
    1. Extracts income bins, income probabilities, and ridehail probabilities from the
       sampleProbabilityString column
    2. Normalizes probabilities by vehicle category (ensures sum equals 1 for each category)
    3. Normalizes probabilities by income bin (ensures sum equals 1 for each income bin)
    4. Normalizes ridehail probabilities (ensures sum equals 1)

    Args:
        vehicle_types (pd.DataFrame): DataFrame containing vehicle types data with columns:
            - vehicleCategory: Category of vehicle
            - sampleProbabilityString: String containing probability information
            - sampleProbabilityWithinCategory: Probability of the vehicle within its category

    Returns:
        pd.DataFrame: Processed DataFrame with additional columns:
            - income_bin: Extracted income bin
            - income_prop: Normalized income probability
            - ridehail_prop: Normalized ridehail probability
    """
    # Create a copy of the dataframe to avoid modifying the original
    df = vehicle_types.copy()

    # Add new columns directly using vectorized operations
    # Apply parse_sample_probability_string to all rows at once
    parsed_data = df['sampleProbabilityString'].apply(parse_sample_probability_string)
    df['income_bin'] = parsed_data.apply(lambda x: x[0])
    df['income_prop'] = parsed_data.apply(lambda x: x[1])
    df['ridehail_bin'] = parsed_data.apply(lambda x: x[2])
    df['ridehail_prop'] = parsed_data.apply(lambda x: x[3])

    df['sampleProbabilityWithinCategory'] = pd.to_numeric(df['sampleProbabilityWithinCategory'], errors='coerce')

    # Normalize probabilities by category using groupby operations
    # This is faster than iterating through unique categories
    category_groups = df.groupby('vehicleCategory')
    df['sampleProbabilityWithinCategory'] = df.apply(
        lambda row: row['sampleProbabilityWithinCategory'] /
                    category_groups.get_group(row['vehicleCategory'])['sampleProbabilityWithinCategory'].sum()
        if category_groups.get_group(row['vehicleCategory'])['sampleProbabilityWithinCategory'].sum() > 0
        else row['sampleProbabilityWithinCategory'],
        axis=1
    )

    # Normalize income probabilities by category and income bin
    for category in df['vehicleCategory'].unique():
        category_df = df[df['vehicleCategory'] == category]

        for income_bin in category_df['income_bin'].dropna().unique():
            mask = (df['vehicleCategory'] == category) & (df['income_bin'] == income_bin)
            prob_sum = df.loc[mask, 'income_prop'].sum()

            if prob_sum > 0:
                df.loc[mask, 'income_prop'] = df.loc[mask, 'income_prop'] / prob_sum

    # Normalize ridehail probabilities - can be done with vectorized operations
    for category in df['vehicleCategory'].unique():
        category_df = df[df['vehicleCategory'] == category]

        for ridehail_bin in category_df['ridehail_bin'].dropna().unique():
            mask = (df['vehicleCategory'] == category) & (df['ridehail_bin'] == ridehail_bin)
            prob_sum = df.loc[mask, 'ridehail_prop'].sum()

            if prob_sum > 0:
                df.loc[mask, 'ridehail_prop'] = df.loc[mask, 'ridehail_prop'] / prob_sum

    return df


def emfac2passenger_by_category_income(vehicle_types, car_emfac, ignore_beam_distribution):
    """
    Merge passenger vehicle types with EMFAC vmt data.

    This function combines vehicle type data with EMFAC vmt distribution data,
    adjusting and normalizing probabilities to maintain consistent distributions from
    both input sources.

    Args:
        vehicle_types (pd.DataFrame): DataFrame of vehicle types with columns:
            - vehicleTypeId: ID of vehicle type
            - mappedClass: Vehicle class category in BEAM
            - mappedFuel: Fuel type compatible with EMFAC categories
            - income_bin: Income bin/range (e.g., '25-50')
            - income_prop: Probability for this income group
            - ridehail_prop: Ridehail probability
            - sampleProbabilityWithinCategory: Probability within vehicle category

        car_emfac (pd.DataFrame): DataFrame of EMFAC vehicle vmt with columns:
            - emfacId: ID of EMFAC vehicle type
            - mappedClass: Vehicle class category in BEAM
            - vehicle_class: Specific vehicle class (e.g., 'LD1', 'LD2')
            - mappedFuel: Fuel type in EMFAC
            - vmt_proportion: Proportion in the total vehicle vmt

    Returns:
        pd.DataFrame: Merged dataframe with new columns:
            - newId: Combined ID (emfacId--vehicleTypeId)
            - vmt_normalized: Normalized vmt proportion
            - newProportionIncome: Recalculated income proportion
            - newProportionRidehail: Recalculated ridehail proportion
            - sampleProbabilityString: Updated probability string
    """
    # Merge dataframes on matching columns
    df_merged = pd.merge(
        left=vehicle_types,
        right=car_emfac,
        left_on=['mappedClass', 'mappedFuel'],
        right_on=['mappedClass', 'mappedFuel'],
        how='outer'
    )

    # Calculate vehicle class probabilities given fuel type using groupby
    vehicle_class_probs = {}
    # Group by mappedClass and fuel to get distribution by vehicle_class
    grouped = car_emfac.groupby(['mappedClass', 'mappedFuel'])

    for group_key, group_df in grouped:
        mapped_class, mapped_fuel = group_key
        if (mapped_class, mapped_fuel) not in vehicle_class_probs:
            vehicle_class_probs[(mapped_class, mapped_fuel)] = {}

        # Calculate normalized probabilities for each vehicle class within the group
        total_prop = group_df['vmt_normalized'].sum()
        if total_prop > 0:
            for _, row in group_df.iterrows():
                vehicle_class_probs[(mapped_class, mapped_fuel)][row['vehicle_class']] = row['vmt_normalized'] / total_prop

    # Apply the conditional probability formula to calculate new proportions
    # Using a vectorized approach where possible
    def get_vehicle_class_prob(row):
        key = (row['mappedClass'], row['mappedFuel'])
        vehicle_class = row['vehicle_class']
        return vehicle_class_probs.get(key, {}).get(vehicle_class, 0)

    # Calculate vehicle class probabilities for each row
    df_merged['vehicle_class_prob'] = df_merged.apply(get_vehicle_class_prob, axis=1)

    # Calculate new proportions
    if ignore_beam_distribution:
        df_merged['sampleProbabilityWithinCategory'] = df_merged['vmt_normalized']
        df_merged['income_prop'] = df_merged['vehicle_class_prob']
        df_merged['ridehail_prop'] = df_merged['vehicle_class_prob']
    else:
        if 'population' in df_merged.columns and df_merged['population'].sum() > 0:
            total = df_merged['population'].sum()
            df_merged["population_proportion"] = df_merged['population'] / total
            df_merged['sampleProbabilityWithinCategory'] = df_merged['population_proportion'] * df_merged[
                'vmt_normalized']
            df_merged['income_prop'] = df_merged['population_proportion'] * df_merged['vehicle_class_prob']
            df_merged['ridehail_prop'] = df_merged['population_proportion'] * df_merged['vehicle_class_prob']
        else:
            df_merged['sampleProbabilityWithinCategory'] = df_merged['sampleProbabilityWithinCategory'] * df_merged[
                'vmt_normalized']
            df_merged['income_prop'] = df_merged['income_prop'] * df_merged['vehicle_class_prob']
            df_merged['ridehail_prop'] = df_merged['ridehail_prop'] * df_merged['vehicle_class_prob']

    # Normalize by income group using groupby
    for income_group in df_merged['income_bin'].dropna().unique():
        mask = df_merged['income_bin'] == income_group

        # Normalize income proportions
        income_sum = df_merged.loc[mask, 'income_prop'].sum()
        if income_sum > 0:
            df_merged.loc[mask, 'income_prop'] = df_merged.loc[mask, 'income_prop'] / income_sum

    for ridehail_group in df_merged['ridehail_bin'].dropna().unique():
        mask = df_merged['ridehail_bin'] == ridehail_group

        # Normalize ridehail proportions
        ridehail_sum = df_merged.loc[mask, 'ridehail_prop'].sum()
        if ridehail_sum > 0:
            df_merged.loc[mask, 'ridehail_prop'] = df_merged.loc[mask, 'ridehail_prop'] / ridehail_sum

    # Recreate the sample probability string with updated values
    # Note: The original code uses 'new_income_prob' but this variable isn't defined or created in the function
    # Using 'income_prop' instead based on context
    df_merged['sampleProbabilityString'] = df_merged.apply(
        lambda row: create_sample_probability_string(
            row['income_bin'],
            row['income_prop'],  # Changed from 'new_income_prob' which doesn't exist
            row['ridehail_bin'],
            row['ridehail_prop']
        ),
        axis=1
    )

    return df_merged


def generate_emfac_mapped_passenger_vehicle_types(emfac_vmt, car_class, bike_class, transit_class, filter_out_classes, work_dir, config, format_func):
    """
    Generate a passenger vehicle types with EMFAC mappings for different vehicle classes.

    This function processes vehicle types data and maps it to EMFAC vmt data for
    cars, bikes, and transit vehicles. It creates a combined dataset that preserves the
    distributions from both sources while mapping vehicle types to appropriate EMFAC categories.

    Args:
        emfac_vmt (pd.DataFrame): EMFAC vmt data with vehicle classes and proportions
        car_class (str): Identifier for car vehicle classes
        bike_class (str): Identifier for bike vehicle classes
        transit_class (str): Identifier for transit vehicle classes
        filter_out_classes (list): classes to filter out, specifically freight classes
        format_func (function): Function to format vehicle types data
        work_dir (str): Working directory path
        config (dict): Configuration dictionary with keys:
            - beam.pax_vehicle_types_file: Path to vehicle types file
            - mappedFuel: Fuel configuration parameters

    Returns:
        pd.DataFrame: Combined and mapped passenger vehicle types with EMFAC IDs
    """
    # Load vehicle types file
    vehicle_types_file = os.path.join(work_dir, f"{config['beam']['pax_vehicle_types_file']}")
    vehicles_file = os.path.join(work_dir, f"{config['beam']['pax_vehicles_file']}")

    # Read and filter vehicle types
    vehicle_types_raw = pd.read_csv(vehicle_types_file, dtype=str)
    vehicle_types_filtered = vehicle_types_raw[~vehicle_types_raw["vehicleCategory"].isin(filter_out_classes)]

    if os.path.exists(vehicles_file):
        vehicles_raw = pd.read_csv(vehicle_types_file, dtype=str)
        counts = vehicles_raw["vehicleTypeId"].value_counts()
        vehicle_summary = pd.DataFrame({
            'vehicleTypeId': counts.index,
            'population': counts.values
        }).reset_index(drop=True)
        # Merge with the filtered vehicle types DataFrame
        vehicle_types_filtered = pd.merge(
            vehicle_types_filtered,
            vehicle_summary,
            on="vehicleTypeId",
            how="left"
        )


    # Create masks for filtering
    car_bike_mask = vehicle_types_filtered['vehicleCategory'].isin([car_class, bike_class])

    # Fix the bus mask - original had a logical error using & instead of bitwise &
    bus_mask = (vehicle_types_filtered['vehicleCategory'] == transit_class) & \
               (vehicle_types_filtered['vehicleTypeId'].str.lower().str.contains('bus'))

    # Format the filtered vehicle types
    filtered_vehicle_types = vehicle_types_filtered.loc[car_bike_mask | bus_mask].copy()
    vehicle_types = format_func(filtered_vehicle_types)

    # ###################################################################################################
    # CAR
    # ###################################################################################################

    # Process car data
    car_emfac = emfac_vmt[emfac_vmt["mappedClass"].isin([car_class])].copy()

    # Normalize bike vmt data
    car_pop_sum = car_emfac['total_vmt'].sum()

    if car_pop_sum > 0:
        car_emfac['vmt_normalized'] = car_emfac['total_vmt'] / car_pop_sum
    else:
        car_emfac['vmt_normalized'] = 0.0

    # Process car data with probabilities
    car_vehicle_types = vehicle_types[vehicle_types['mappedClass'].isin([car_class])].copy()
    processed_car_types = process_vehicle_types_probabilities_by_vehicle_category_and_income_group(car_vehicle_types)
    ignore_beam_passenger_distribution = config["mapping"]["fleet"]["ignore_beam_passenger_distribution"]
    car_beam_emfac = emfac2passenger_by_category_income(processed_car_types, car_emfac, ignore_beam_passenger_distribution)

    # Select only necessary columns from the result
    car_beam_emfac = car_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]
    car_beam_emfac["oldVehicleTypeId"] = car_beam_emfac["vehicleTypeId"]
    car_beam_emfac["vehicleTypeId"] = car_beam_emfac["emfacId"].astype(str) + "--" + car_beam_emfac["oldVehicleTypeId"].astype(str)

    # ###################################################################################################
    # BIKE
    # ###################################################################################################

    # Process bike data
    bike_emfac = emfac_vmt[emfac_vmt["mappedClass"].isin([bike_class])].copy()

    # Normalize bike vmt data
    bike_pop_sum = bike_emfac['total_vmt'].sum()

    # Add safeguard for division by zero
    if bike_pop_sum > 0:
        bike_emfac['vmt_normalized'] = bike_emfac['total_vmt'] / bike_pop_sum
    else:
        bike_emfac['vmt_normalized'] = 0.0  # Default value if no range

    # Merge bike data
    bike_beam_emfac = pd.merge(
        left=vehicle_types[vehicle_types['mappedClass'].isin([bike_class])],
        right=bike_emfac,
        left_on=['mappedClass', 'mappedFuel'],
        right_on=['mappedClass', 'mappedFuel'],
        how='outer'
    )

    # Calculate new proportion for bikes
    # The original had a possible bug with 'prob_category' - changed to 'sampleProbabilityWithinCategory'
    bike_beam_emfac['sampleProbabilityWithinCategory'] = bike_beam_emfac.apply(
        lambda row: 1 if pd.isna(row['sampleProbabilityWithinCategory']) or
                         row['sampleProbabilityWithinCategory'] == ''
        else float(row['sampleProbabilityWithinCategory']) * row['vmt_normalized'],
        axis=1
    )

    # Select bike columns
    bike_beam_emfac = bike_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]
    bike_beam_emfac["oldVehicleTypeId"] = bike_beam_emfac["vehicleTypeId"]
    bike_beam_emfac["vehicleTypeId"] = bike_beam_emfac["emfacId"].astype(str) + "--" + bike_beam_emfac["oldVehicleTypeId"].astype(str)

    # ###################################################################################################
    # BUS
    # ###################################################################################################

    # Process bus data
    bus_emfac = emfac_vmt[emfac_vmt["mappedClass"] == transit_class].copy()

    # Normalize bus vmt data
    bus_pop_sum = bus_emfac['total_vmt'].sum()

    if bus_pop_sum > 0:
        bus_emfac['vmt_normalized'] = bus_emfac['total_vmt'] / bus_pop_sum
    else:
        bus_emfac['vmt_normalized'] = 1.0  # Default value if no range

    # Bus mask for filter - corrected syntax for filtering
    bus_types_mask = (vehicle_types['mappedClass'] == transit_class) & \
                     (vehicle_types['vehicleTypeId'].str.lower().str.contains('bus'))

    # Merge bus data - using the corrected mask
    bus_beam_emfac_merged = pd.merge(
        left=vehicle_types[bus_types_mask],
        right=bus_emfac,
        on=['mappedClass', 'mappedFuel'],
        how='outer'
    )

    bus_beam_emfac = bus_beam_emfac_merged.groupby('vehicleTypeId').apply(
        lambda x: x.sample(n=1, weights='vmt_normalized', replace=True) if len(x) > 0 else x
    ).reset_index(drop=True)

    # Select bus columns
    bus_beam_emfac = bus_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]
    bus_beam_emfac["oldVehicleTypeId"] = bus_beam_emfac["vehicleTypeId"]

    # Combine all vehicle types
    result = pd.concat([car_beam_emfac, bike_beam_emfac, bus_beam_emfac], ignore_index=True)

    vehicle_types_others = vehicle_types_filtered[~vehicle_types_filtered["vehicleTypeId"].isin(result["oldVehicleTypeId"].unique())]

    return result, vehicle_types_others


def generate_fleet_from_vehicle_types(mapped_vehicle_types, car_class, bike_class, work_dir, config):
    """
    Update vehicle.csv file by sampling from new vehicle types based on original vehicleTypeId.

    This function uses vectorized operations and batch processing for better performance.

    Args:
        mapped_vehicle_types (pd.DataFrame): DataFrame containing mapped vehicle types
        car_class (str): Identifier for car vehicle class
        bike_class (str): Identifier for bike vehicle class
        work_dir (str): Working directory path
        config (dict): Configuration dictionary with beam.pax_vehicles_file key

    Returns:
        pd.DataFrame: Updated vehicles DataFrame with new vehicleTypeIds and stateOfCharge values
    """
    # Read the vehicle.csv file
    vehicles_file_path = os.path.join(work_dir, config["beam"]["pax_vehicles_file"])
    vehicles_df = pd.read_csv(vehicles_file_path)
    total_vehicles = len(vehicles_df)

    # Filter vehicle types to only cars and bikes
    car_bike_mask = mapped_vehicle_types['vehicleCategory'].isin([car_class, bike_class])
    filtered_vehicle_types = mapped_vehicle_types.loc[car_bike_mask].copy()

    # Ensure sampleProbabilityWithinCategory is numeric
    filtered_vehicle_types['sampleProbabilityWithinCategory'] = pd.to_numeric(
        filtered_vehicle_types['sampleProbabilityWithinCategory'], errors='coerce'
    ).fillna(0)

    # Precompute vehicle category mappings
    vehicle_categories = {}
    for vehicle_type_id in vehicles_df['vehicleTypeId'].unique():
        if isinstance(vehicle_type_id, str) and 'BIKE' in vehicle_type_id.upper():
            vehicle_categories[vehicle_type_id] = bike_class
        else:
            vehicle_categories[vehicle_type_id] = car_class

    # Create a dictionary to store matches by original type
    type_matches = {}

    # Precompute category filters
    category_filters = {
        car_class: filtered_vehicle_types[filtered_vehicle_types['vehicleCategory'] == car_class],
        bike_class: filtered_vehicle_types[filtered_vehicle_types['vehicleCategory'] == bike_class]
    }

    # Create new columns in the vehicles DataFrame
    vehicles_df['oldVehicleTypeId'] = vehicles_df['vehicleTypeId']
    vehicles_df['stateOfCharge'] = ""

    # Process in batches with progress bar
    batch_size = 1000
    num_batches = (total_vehicles + batch_size - 1) // batch_size

    with tqdm(total=total_vehicles, desc="Processing vehicles") as pbar:
        for batch_idx in range(num_batches):
            start_idx = batch_idx * batch_size
            end_idx = min(start_idx + batch_size, total_vehicles)
            batch = vehicles_df.iloc[start_idx:end_idx]

            for idx, vehicle in batch.iterrows():
                original_type_id = str(vehicle['vehicleTypeId'])

                # Use cached matches if available
                if original_type_id in type_matches:
                    matches, weights = type_matches[original_type_id]
                else:
                    # Filter vehicle types to only include those with matching oldVehicleTypeId
                    matches = filtered_vehicle_types[
                        filtered_vehicle_types['oldVehicleTypeId'] == original_type_id].copy()

                    if len(matches) == 0:
                        # If no direct match, use vehicle category
                        category = vehicle_categories.get(original_type_id, car_class)
                        matches = category_filters[category].copy()

                    # Get weights for sampling
                    weights = matches['sampleProbabilityWithinCategory'].values

                    # Cache the matches and weights
                    type_matches[original_type_id] = (matches, weights)

                # Sample a new vehicle type
                if np.sum(weights) > 0:
                    sampled_idx = np.random.choice(len(matches), p=weights / np.sum(weights))
                else:
                    sampled_idx = np.random.randint(0, len(matches))

                sampled_row = matches.iloc[sampled_idx]

                # Update vehicleTypeId to the sampled one
                vehicles_df.at[idx, 'vehicleTypeId'] = sampled_row['vehicleTypeId']

                # Update stateOfCharge based on fuel type
                fuel_type = str(sampled_row.get('mappedFuel', ''))
                if 'Elec' in fuel_type or 'Phe' in fuel_type:
                    vehicles_df.at[idx, 'stateOfCharge'] = 1

            pbar.update(end_idx - start_idx)

    return vehicles_df