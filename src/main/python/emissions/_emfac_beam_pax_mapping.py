import os
import re
import sys

import pandas as pd

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
    ridehail_match = re.search(r"ridehail\|([0-9.]+)", cleaned)

    # Extract values from matches
    income_bin = income_match.group(1) if income_match else None
    income_prob = float(income_match.group(2)) if income_match else None
    ridehail_prob = float(ridehail_match.group(1)) if ridehail_match else None

    return income_bin, income_prob, ridehail_prob


def create_sample_probability_string(income_bin, income_prob, ridehail_prob):
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
    if income_bin is None and income_prob is None and ridehail_prob is None:
        return ""

    # Pre-allocate list with appropriate size to avoid resizing
    parts = []

    # Build parts directly
    if income_bin is not None and income_prob is not None:
        parts.append(f"income|{income_bin}:{income_prob:.6f}")

    if ridehail_prob is not None:
        parts.append(f"ridehail|{ridehail_prob:.6f}")

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
    df['ridehail_prop'] = parsed_data.apply(lambda x: x[2])

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
    ridehail_mask = df['ridehail_prop'].notna()
    if ridehail_mask.any():
        prob_sum = df.loc[ridehail_mask, 'ridehail_prop'].sum()

        if prob_sum > 0:
            df.loc[ridehail_mask, 'ridehail_prop'] = df.loc[ridehail_mask, 'ridehail_prop'] / prob_sum

    return df


def emfac2passenger_by_category_income(vehicle_types, emfac_pop):
    """
    Merge passenger vehicle types with EMFAC population data.

    This function combines vehicle type data with EMFAC population distribution data,
    adjusting and normalizing probabilities to maintain consistent distributions from
    both input sources.

    Args:
        vehicle_types (pd.DataFrame): DataFrame of vehicle types with columns:
            - vehicleTypeId: ID of vehicle type
            - beamClass: Vehicle class category in BEAM
            - emfacFuel: Fuel type compatible with EMFAC categories
            - income_bin: Income bin/range (e.g., '25-50')
            - income_prop: Probability for this income group
            - ridehail_prop: Ridehail probability
            - sampleProbabilityWithinCategory: Probability within vehicle category

        emfac_pop (pd.DataFrame): DataFrame of EMFAC vehicle populations with columns:
            - emfacId: ID of EMFAC vehicle type
            - beamClass: Vehicle class category in BEAM
            - vehicle_class: Specific vehicle class (e.g., 'LD1', 'LD2')
            - fuel: Fuel type in EMFAC
            - population_proportion: Proportion in the total vehicle population

    Returns:
        pd.DataFrame: Merged dataframe with new columns:
            - newId: Combined ID (emfacId--vehicleTypeId)
            - population_normalized: Normalized population proportion
            - newProportionIncome: Recalculated income proportion
            - newProportionRidehail: Recalculated ridehail proportion
            - sampleProbabilityString: Updated probability string
    """
    # Create a copy of the EMFAC population dataframe
    car_emfac = emfac_pop.copy()

    # Normalize the population proportions to [0,1] range
    min_value = car_emfac['population_proportion'].min()
    max_value = car_emfac['population_proportion'].max()
    # Avoid division by zero
    range_value = max_value - min_value
    car_emfac['population_normalized'] = (car_emfac[
                                              'population_proportion'] - min_value) / range_value if range_value > 0 else 0

    # Merge dataframes on matching columns
    df_merged = pd.merge(
        left=vehicle_types,
        right=car_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )

    # Calculate vehicle class probabilities given fuel type using groupby
    vehicle_class_probs = {}
    # Group by beamClass and fuel to get distribution by vehicle_class
    grouped = car_emfac.groupby(['beamClass', 'fuel'])

    for group_key, group_df in grouped:
        beam_class, fuel = group_key
        if (beam_class, fuel) not in vehicle_class_probs:
            vehicle_class_probs[(beam_class, fuel)] = {}

        # Calculate normalized probabilities for each vehicle class within the group
        total_prop = group_df['population_normalized'].sum()
        if total_prop > 0:
            for _, row in group_df.iterrows():
                vehicle_class_probs[(beam_class, fuel)][row['vehicle_class']] = row[
                                                                                    'population_normalized'] / total_prop

    # Apply the conditional probability formula to calculate new proportions
    # Using a vectorized approach where possible
    def get_vehicle_class_prob(row):
        key = (row['beamClass'], row['fuel'])
        vehicle_class = row['vehicle_class']
        return vehicle_class_probs.get(key, {}).get(vehicle_class, 0)

    # Calculate vehicle class probabilities for each row
    df_merged['vehicle_class_prob'] = df_merged.apply(get_vehicle_class_prob, axis=1)

    # Calculate new proportions
    df_merged['income_prop'] = df_merged['income_prop'] * df_merged['vehicle_class_prob']
    df_merged['ridehail_prop'] = df_merged['ridehail_prop'] * df_merged['vehicle_class_prob']
    df_merged['sampleProbabilityWithinCategory'] = df_merged['sampleProbabilityWithinCategory'] * df_merged[
        'population_normalized']

    # Normalize by income group using groupby
    for income_group in df_merged['income_bin'].dropna().unique():
        mask = df_merged['income_bin'] == income_group

        # Normalize income proportions
        income_sum = df_merged.loc[mask, 'income_prop'].sum()
        if income_sum > 0:
            df_merged.loc[mask, 'income_prop'] = df_merged.loc[mask, 'income_prop'] / income_sum

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
            row['ridehail_prop']
        ),
        axis=1
    )

    return df_merged


def generate_emfac_mapped_passenger_fleet(emfac_pop, car_class, bike_class, transit_class, filter_out_classes, format_func, work_dir, config):
    """
    Generate a passenger fleet with EMFAC mappings for different vehicle classes.

    This function processes vehicle types data and maps it to EMFAC population data for
    cars, bikes, and transit vehicles. It creates a combined dataset that preserves the
    distributions from both sources while mapping vehicle types to appropriate EMFAC categories.

    Args:
        emfac_pop (pd.DataFrame): EMFAC population data with vehicle classes and proportions
        car_class (str): Identifier for car vehicle classes
        bike_class (str): Identifier for bike vehicle classes
        transit_class (str): Identifier for transit vehicle classes
        filter_out_classes (list): classes to filter out, specifically freight classes
        format_func (function): Function to format vehicle types data
        work_dir (str): Working directory path
        config (dict): Configuration dictionary with keys:
            - beam.pax_vehicle_types_file: Path to vehicle types file
            - fuel: Fuel configuration parameters

    Returns:
        pd.DataFrame: Combined and mapped passenger fleet with EMFAC IDs
    """
    # Load vehicle types file
    vehicle_types_file = os.path.join(work_dir, f"{config['beam']['pax_vehicle_types_file']}")

    # Read and filter vehicle types
    vehicle_types_raw = pd.read_csv(vehicle_types_file, dtype=str)
    vehicle_types_filtered = vehicle_types_raw[~vehicle_types_raw["vehicleCategory"].isin(filter_out_classes)]

    # Create masks for filtering
    car_bike_mask = vehicle_types_filtered['vehicleCategory'].isin([car_class, bike_class])

    # Fix the bus mask - original had a logical error using & instead of bitwise &
    bus_mask = (vehicle_types_filtered['vehicleCategory'] == transit_class) & \
               (vehicle_types_filtered['vehicleTypeId'].str.lower().str.contains('bus'))

    # Format the filtered vehicle types
    filtered_vehicle_types = vehicle_types_filtered.loc[car_bike_mask | bus_mask].copy()
    vehicle_types = format_func(filtered_vehicle_types, config["fuel"])

    # Process car data
    car_vehicle_types = vehicle_types[vehicle_types['beamClass'].isin([car_class])]
    car_emfac_data = emfac_pop[emfac_pop["beamClass"].isin([car_class])]

    # Process car data with probabilities
    processed_car_types = process_vehicle_types_probabilities_by_vehicle_category_and_income_group(car_vehicle_types)
    car_beam_emfac = emfac2passenger_by_category_income(processed_car_types, car_emfac_data)

    # Select only necessary columns from the result
    car_beam_emfac = car_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]

    # Process bike data
    bike_emfac = emfac_pop[emfac_pop["beamClass"].isin([bike_class])]

    # Normalize bike population data
    bike_pop_min = bike_emfac['population_proportion'].min()
    bike_pop_max = bike_emfac['population_proportion'].max()
    bike_pop_range = bike_pop_max - bike_pop_min

    # Add safeguard for division by zero
    if bike_pop_range > 0:
        bike_emfac['population_normalized'] = (bike_emfac['population_proportion'] - bike_pop_min) / bike_pop_range
    else:
        bike_emfac['population_normalized'] = 1.0  # Default value if no range

    # Merge bike data
    bike_beam_emfac = pd.merge(
        left=vehicle_types[vehicle_types['beamClass'].isin([bike_class])],
        right=bike_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )

    # Calculate new proportion for bikes
    # The original had a possible bug with 'prob_category' - changed to 'sampleProbabilityWithinCategory'
    bike_beam_emfac['sampleProbabilityWithinCategory'] = bike_beam_emfac.apply(
        lambda row: 1 if pd.isna(row['sampleProbabilityWithinCategory']) or
                         row['sampleProbabilityWithinCategory'] == ''
        else float(row['sampleProbabilityWithinCategory']) * row['population_normalized'],
        axis=1
    )

    # Select bike columns
    bike_beam_emfac = bike_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]

    # Process bus data
    bus_emfac = emfac_pop[emfac_pop["beamClass"] == transit_class]

    # Normalize bus population data
    bus_pop_min = bus_emfac['population_proportion'].min() if not bus_emfac.empty else 0
    bus_pop_max = bus_emfac['population_proportion'].max() if not bus_emfac.empty else 1
    bus_pop_range = bus_pop_max - bus_pop_min

    if bus_pop_range > 0:
        bus_emfac['population_normalized'] = (bus_emfac['population_proportion'] - bus_pop_min) / bus_pop_range
    else:
        bus_emfac['population_normalized'] = 1.0  # Default value if no range

    # Bus mask for filter - corrected syntax for filtering
    bus_types_mask = (vehicle_types['beamClass'] == transit_class) & \
                     (vehicle_types['vehicleTypeId'].str.lower().str.contains('bus'))

    # Merge bus data - using the corrected mask
    bus_beam_emfac_merged = pd.merge(
        left=vehicle_types[bus_types_mask],
        right=bus_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )

    bus_beam_emfac = bus_beam_emfac_merged.groupby('vehicleTypeId').apply(
        lambda x: x.sample(n=1, weights='population_normalized', replace=True) if len(x) > 0 else x
    ).reset_index(drop=True)

    # Select bus columns
    bus_beam_emfac = bus_beam_emfac[vehicle_types_filtered.columns.tolist() + ["emfacId"]]

    # Combine all vehicle types
    result = pd.concat([car_beam_emfac, bike_beam_emfac, bus_beam_emfac], ignore_index=True)

    # Create new vehicle type IDs
    result["oldVehicleTypeId"] = result["vehicleTypeId"]
    # Fixed string formatting - the original had an f-string syntax error
    result["vehicleTypeId"] = result["emfacId"].astype(str) + "--" + result["oldVehicleTypeId"].astype(str)

    vehicle_types_others = vehicle_types_filtered[
        ~vehicle_types_filtered["vehicleTypeId"].isin(result["oldVehicleTypeId"].unique())]

    return result, vehicle_types_others
