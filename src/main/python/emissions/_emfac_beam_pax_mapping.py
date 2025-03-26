import pandas as pd
import numpy as np
import re
import os
import sys

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)


def parse_sample_probability_string(prob_string):
    """Parse the sampleProbabilityString to extract income and ridehail probabilities."""
    if pd.isna(prob_string) or prob_string == "":
        return None, None, None

    # Remove spaces and convert to lowercase
    cleaned = prob_string.replace(" ", "").lower()

    # Extract income ranges and their probabilities
    income_bin = None
    income_prob = None
    ridehail_prob = None

    for group in cleaned.split(";"):
        parts = group.split("|")
        if len(parts) < 2:
            continue

        group_key = parts[0]
        values = parts[1:]

        for value in values:
            if ":" not in value:
                continue

            key, probability = value.split(":")

            if group_key == "income":
                income_bin = key
                income_prob = float(probability)
            elif group_key == "ridehail":
                ridehail_prob = float(probability)

    return income_bin, income_prob, ridehail_prob


def create_sample_probability_string(income_bin, income_prob, ridehail_prob):
    """
    Convert income bin, income probability, and ridehail probability back to a sample probability string.

    Args:
        income_bin (str): Income bin/range (e.g., '25-50', '50-75')
        income_prob (float): Income probability value
        ridehail_prob (float): Ridehail probability value

    Returns:
        str: Formatted sample probability string
    """
    if income_bin is None and income_prob is None and ridehail_prob is None:
        return ""

    parts = []

    # Add income part if available
    if income_bin is not None and income_prob is not None:
        income_part = f"income|{income_bin}:{income_prob:.6f}"
        parts.append(income_part)

    # Add ridehail part if available
    if ridehail_prob is not None:
        ridehail_part = f"ridehail|{ridehail_prob:.6f}"
        parts.append(ridehail_part)

    # Join parts with semicolon
    return "; ".join(parts)


def process_vehicle_types_probabilities_by_vehicle_category_and_income_group(vehicle_types):
    """Process the vehicle types."""
    # Create new columns
    df = vehicle_types.copy()
    df['income_bin'] = None
    df['income_prop'] = None
    df['ridehail_prop'] = None

    # Parse sample probability strings
    for idx, row in df.iterrows():
        income_bin, income_prob, ridehail_prob = parse_sample_probability_string(row['sampleProbabilityString'])
        df.at[idx, 'income_bin'] = income_bin
        df.at[idx, 'income_prop'] = income_prob
        df.at[idx, 'ridehail_prop'] = ridehail_prob

    # Normalize probabilities by category as done in UniformVehiclesAdjustment
    for category in df['vehicleCategory'].unique():
        category_mask = df['vehicleCategory'] == category

        # Normalize category probabilities
        prob_sum = df.loc[category_mask, 'sampleProbabilityWithinCategory'].sum()
        if prob_sum > 0:
            df.loc[category_mask, 'sampleProbabilityWithinCategory'] = df.loc[category_mask, 'sampleProbabilityWithinCategory'] / prob_sum

    # Normalize probabilities by income bin as done in IncomeBasedVehiclesAdjustment
    for category in df['vehicleCategory'].unique():
        for income_bin in df.loc[df['vehicleCategory'] == category, 'income_bin'].unique():
            if pd.isna(income_bin):
                continue

            mask = (df['vehicleCategory'] == category) & (df['income_bin'] == income_bin)
            prob_sum = df.loc[mask, 'income_prop'].sum()

            if prob_sum > 0:
                df.loc[mask, 'income_prop'] = df.loc[mask, 'income_prop'] / prob_sum

    # Normalize ridehail probabilities
    ridehail_mask = df['ridehail_prop'].notna()
    if ridehail_mask.any():
        prob_sum = df.loc[ridehail_mask, 'ridehail_prop'].sum()

        if prob_sum > 0:
            df.loc[ridehail_mask, 'ridehail_prop'] = df.loc[ridehail_mask, 'ridehail_prop'] / prob_sum

    return df


def emfac2passenger_by_category_income(vehicle_types, emfac_pop):
    """
    Merge two dataframes with the following structure:

    df1: vehicleTypeId, beamClass, emfacFuel, incomeGroup, proportionIncome, proportionRidehail
    df2: emfacId, beamClass, vehicle_class, fuel, proportionEmfac

    Returns a merged dataframe with:
    df_merged: newId, emfacId, vehicleTypeId, beamClass, vehicle_class, fuel, incomeGroup,
               proportionIncome, proportionRidehail, proportionEmfac,
               newProportionIncome, newProportionRidehail

    The merge preserves the distributions from both dataframes.
    """
    car_emfac = emfac_pop.copy()
    min_value = car_emfac['population_proportion'].min()
    max_value = car_emfac['population_proportion'].max()
    car_emfac['population_normalized'] = (car_emfac['population_proportion'] - min_value) / (max_value - min_value)
    # Step 2: Merge dataframes on common columns (beamClass and fuel)
    df_merged = pd.merge(
        left=vehicle_types,
        right=car_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )

    # Step 3: Create the newId column (emfacId--vehicleTypeId)
    df_merged['newId'] = df_merged['emfacId'].astype(str) + '--' + df_merged['vehicleTypeId'].astype(str)

    # Step 4: Calculate joint probabilities
    # Get marginal probabilities of vehicle_class given fuel type from df2
    vehicle_class_given_fuel = {}
    for _, row in car_emfac.iterrows():
        key = (row['beamClass'], row['fuel'])
        if key not in vehicle_class_given_fuel:
            vehicle_class_given_fuel[key] = {}

        vehicle_class_given_fuel[key][row['vehicle_class']] = row['population_normalized']

    # Normalize the probabilities
    for key in vehicle_class_given_fuel:
        total = sum(vehicle_class_given_fuel[key].values())
        if total > 0:
            for vclass in vehicle_class_given_fuel[key]:
                vehicle_class_given_fuel[key][vclass] /= total

    # Step 5: Calculate new proportions
    # Calculate new proportions using the conditional probability formula
    df_merged['income_prop'] = df_merged.apply(
        lambda row: row['income_prop'] * vehicle_class_given_fuel.get(
            (row['beamClass'], row['fuel']), {}).get(row['vehicle_class'], 0),
        axis=1
    )

    df_merged['ridehail_prop'] = df_merged.apply(
        lambda row: row['ridehail_prop'] * vehicle_class_given_fuel.get(
            (row['beamClass'], row['fuel']), {}).get(row['vehicle_class'], 0),
        axis=1
    )

    df_merged['sampleProbabilityWithinCategory'] = df_merged.apply(
        lambda row: row['sampleProbabilityWithinCategory'] * row['population_normalized'],
        axis=1
    )

    # Step 6: Normalize the new proportions by income group
    for income_group in df_merged['income_bin'].unique():
        # Get mask for this income group
        mask = df_merged['income_bin'] == income_group

        # Normalize proportionIncome
        total_income = df_merged.loc[mask, 'income_prop'].sum()
        if total_income > 0:
            df_merged.loc[mask, 'income_prop'] = df_merged.loc[mask, 'income_prop'] / total_income

        # Normalize proportionRidehail
        total_ridehail = df_merged.loc[mask, 'ridehail_prop'].sum()
        if total_ridehail > 0:
            df_merged.loc[mask, 'ridehail_prop'] = df_merged.loc[mask, 'ridehail_prop'] / total_ridehail

    df_merged['sampleProbabilityString'] = df_merged.apply(
        lambda row: create_sample_probability_string(
            row['income_bin'],
            row['new_income_prob'],
            row['ridehail_prob']
        ),
        axis=1
    )

    return df_merged


def generate_emfac_mapped_passenger_fleet(emfac_pop, car_class, bike_class, transit_class, format_func, work_dir, config):
    vehicle_types_file = os.path.join(work_dir, f"{config["beam"]["pax_vehicle_types_file"]}")
    vehicle_types_out_file = os.path.join(work_dir, f"{vehicle_types_file.replace(".csv", "--TrAP.csv")}")

    vehicle_types_raw = pd.read_csv(vehicle_types_file, dtype=str)
    car_bike_mask = vehicle_types_raw['vehicleCategory'].isin(car_class + bike_class)
    bus_mask = vehicle_types_raw['vehicleCategory'] == transit_class & vehicle_types_raw['vehicleTypeId'].str.lower().str.contains('bus')
    vehicle_types = format_func(vehicle_types_raw.loc[car_bike_mask | bus_mask].copy(), config["fuel"])

    car_beam_emfac = emfac2passenger_by_category_income(
        process_vehicle_types_probabilities_by_vehicle_category_and_income_group(
            vehicle_types[vehicle_types['beamClass']==car_class]
        ),
        emfac_pop[emfac_pop["beamClass"] == car_class]
    )
    car_beam_emfac = car_beam_emfac[vehicle_types_raw.columns+["emfacId"]]

    bike_emfac = emfac_pop[emfac_pop["beamClass"] == bike_class]
    min_value = bike_emfac['population_proportion'].min()
    max_value = bike_emfac['population_proportion'].max()
    bike_emfac['population_normalized'] = (bike_emfac['population_proportion'] - min_value) / (max_value - min_value)
    bike_beam_emfac = pd.merge(
        left=vehicle_types[vehicle_types['beamClass']==bike_class],
        right=bike_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )
    bike_beam_emfac['new_prop_category'] = bike_beam_emfac.apply(
        lambda row: 1 if pd.isna(row['prob_category']) or row['prob_category'] == '' else row['prob_category'] * row[
            'population_normalized'],
        axis=1
    )
    bike_beam_emfac = bike_beam_emfac[vehicle_types_raw.columns + ["emfacId"]]

    bus_emfac = emfac_pop[emfac_pop["beamClass"] == transit_class]
    min_value = bus_emfac['population_proportion'].min()
    max_value = bus_emfac['population_proportion'].max()
    bus_emfac['population_normalized'] = (bus_emfac['population_proportion'] - min_value) / (max_value - min_value)
    bus_beam_emfac_merged = pd.merge(
        left=vehicle_types['beamClass'] == transit_class & vehicle_types['vehicleTypeId'].str.lower().str.contains('bus'),
        right=bus_emfac,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='outer'
    )
    bus_beam_emfac = bus_beam_emfac_merged.groupby('vehicleTypeId').sample(n=1, weights='population_normalized')
    bus_beam_emfac = bus_beam_emfac[vehicle_types_raw.columns + ["emfacId"]]

    result = pd.concat([car_beam_emfac, bike_beam_emfac, bus_beam_emfac], ignore_index=True)
    result["oldVehicleTypeId"] = result["vehicleTypeId"]
    result["vehicleTypeId"] = f"{result["emfacId"]}--{result["oldVehicleTypeId"]}"

    return result















## TO DELETE

def extract_income_group(vehicle_type_id):
    """
    Extract income group from vehicle type ID or return a default.
    Adjust this based on your actual vehicle type ID format.
    """
    # Try to extract income group from the ID
    # This is a placeholder - adjust according to your specific format
    match = re.search(r'income-(\d+-\d+)', str(vehicle_type_id))
    if match:
        return match.group(1)

    # If income group can't be determined, check if there's a specific pattern or return default
    if 'low' in str(vehicle_type_id).lower():
        return "0-25"
    elif 'med' in str(vehicle_type_id).lower():
        return "25-50"
    elif 'high' in str(vehicle_type_id).lower():
        return "50-75"
    elif 'very-high' in str(vehicle_type_id).lower():
        return "75-100"
    else:
        # Default income group
        return "0-25"

def update_vehicle_probabilities(_pax_vehicle_types, _emfac_pop):
    """
    Calculate vehicle type probabilities based on BEAM probabilities and EMFAC population share.
    Buses are handled separately and concatenated after processing the rest.
    """
    # Step 1: Merge vehicle types with population data
    df_merged_raw = pd.merge(
        _pax_vehicle_types,
        _emfac_pop,
        left_on=['beamClass', 'emfacFuel'],
        right_on=['beamClass', 'fuel'],
        how='left'
    )

    # Separate buses and non-buses
    bus_mask = ((df_merged_raw['vehicleCategory'] == BeamClasses.CLASS_MDP) &
                (df_merged_raw['vehicleTypeId'].str.lower().str.contains('bus')))

    # Process buses - sample one row per vehicleTypeId based on population
    df_buses = df_merged_raw[bus_mask].copy()
    df_buses_filtered = df_buses.groupby('vehicleTypeId').sample(n=1, weights='population')


    # Process cars and bikes
    car_bike_mask = (df_merged_raw['vehicleCategory'].isin([BeamClasses.CLASS_CAR, BeamClasses.CLASS_BIKE]))
    df_merged = df_merged_raw[car_bike_mask].copy()

    # Save original vehicle type ID and create new combined ID
    df_merged["originalVehicleTypeId"] = df_merged["vehicleTypeId"]
    df_merged["vehicleTypeId"] = df_merged.apply(
        lambda row: f"{row['emfacId']}--{row['originalVehicleTypeId']}",
        axis=1
    )

    # Extract income group from vehicle type ID
    df_merged['income_group'] = df_merged.apply(
        lambda row: extract_income_group(row['vehicleTypeId']),
        axis=1
    )

    # Step 2: Calculate total probabilities by income group
    income_group_probabilities = {}
    total_probability = 0

    # Calculate total probability for each income group
    for income_group in df_merged['income_group'].unique():
        group_mask = df_merged['income_group'] == income_group
        if 'sampleProbabilityWithinCategory' in df_merged.columns:
            group_probs = df_merged.loc[group_mask, 'sampleProbabilityWithinCategory']
            group_probs = pd.to_numeric(group_probs, errors='coerce').fillna(0)
            income_group_probabilities[income_group] = group_probs.sum()
            total_probability += group_probs.sum()

    # Step 3: Adjust probabilities based on EMFAC population share
    for idx, row in df_merged.iterrows():
        beam_class = row['beamClass']
        emfac_fuel = row['emfacFuel']
        income_group = row['income_group']

        # Get original probability
        orig_prob = pd.to_numeric(row['sampleProbabilityWithinCategory'], errors='coerce')
        if pd.isna(orig_prob):
            orig_prob = 1  # Default to 1 if conversion fails

        # Match by both beam class and fuel
        matching_emfac = _emfac_pop[
            (_emfac_pop['beamClass'] == beam_class) &
            (_emfac_pop['fuel'] == emfac_fuel)
            ]

        # If no match, fall back to matching by beam class only
        if len(matching_emfac) == 0:
            matching_emfac = _emfac_pop[_emfac_pop['beamClass'] == beam_class]

        # If we found matching EMFAC entries, adjust probability based on population share
        if len(matching_emfac) > 0:
            # Calculate total population for this beam class
            total_pop = matching_emfac['population'].sum()

            # Find the matching EMFAC row for this specific vehicle
            emfac_match = matching_emfac[matching_emfac['emfacId'] == row['emfacId']]

            if emfac_match is not None and len(emfac_match) > 0:
                # Calculate population share
                pop_share = emfac_match.iloc[0]['population'] / total_pop if total_pop > 0 else 1.0 / len(
                    matching_emfac)

                # Adjust probability based on population share
                adjusted_prob = orig_prob * pop_share
                df_merged.at[idx, 'sampleProbabilityWithinCategory'] = adjusted_prob

                # Update probability string
                update_probability_string(df_merged, idx, adjusted_prob, income_group, total_probability)

    # Step 4: Normalize probabilities within income groups
    normalize_probabilities_by_income_group(df_merged, income_group_probabilities)

    # Step 5: Normalize ridehail probabilities
    normalize_ridehail_probabilities(df_merged)

    # Drop temporary columns
    if 'originalVehicleTypeId' in df_merged.columns:
        df_merged.drop('originalVehicleTypeId', axis=1, inplace=True)

    # Combine buses with processed cars and bikes
    df_buses_filtered["vehicleTypeId"] = df_buses_filtered["vehicleTypeId"]  # Keep original ID for buses
    result_df = pd.concat([df_merged, df_buses_filtered], ignore_index=True)

    return result_df


# Helper functions to make the main function cleaner
def update_probability_string(df, idx, adjusted_prob, income_group, total_probability):
    """Update the probability string for a given row"""
    if 'sampleProbabilityString' not in df.columns or pd.isna(df.at[idx, 'sampleProbabilityString']):
        df.at[idx, 'sampleProbabilityString'] = f"income | {income_group}:{adjusted_prob}"
        return

    original_string = df.at[idx, 'sampleProbabilityString']
    has_ridehail = 'ridehail' in original_string

    if has_ridehail:
        parts = original_string.split(';')
        ridehail_part = parts[0].strip()
        ridehail_prefix = ridehail_part.split(':')[0] + ':'

        # Calculate overall probability
        overall_prob = adjusted_prob / total_probability if total_probability > 0 else 0

        if len(parts) > 1:
            income_part = parts[1].strip()
            income_prefix = income_part.split(':')[0] + ':'
            df.at[idx, 'sampleProbabilityString'] = f"{ridehail_prefix}{overall_prob}; {income_prefix}{adjusted_prob}"
        else:
            df.at[idx, 'sampleProbabilityString'] = f"{ridehail_prefix}{overall_prob}"
    else:
        if ';' in original_string:
            parts = original_string.split(';')
            first_part = parts[0]
            if len(parts) > 1 and 'income' in parts[1]:
                income_prefix = parts[1].split(':')[0] + ':'
                df.at[idx, 'sampleProbabilityString'] = f"{first_part}; {income_prefix}{adjusted_prob}"
            else:
                df.at[idx, 'sampleProbabilityString'] = f"{first_part}; income | {income_group}:{adjusted_prob}"
        else:
            df.at[idx, 'sampleProbabilityString'] = f"income | {income_group}:{adjusted_prob}"


def normalize_probabilities_by_income_group(df, income_group_probabilities):
    """Normalize probabilities within income groups"""
    for income_group in df['income_group'].unique():
        for beam_class in df['beamClass'].unique():
            # Get rows for this income group and beam class
            mask = (df['income_group'] == income_group) & (df['beamClass'] == beam_class)

            if not mask.any():
                continue

            # Get the sum of probabilities for this group
            group_probs = pd.to_numeric(df.loc[mask, 'sampleProbabilityWithinCategory'], errors='coerce').fillna(0)
            group_sum = group_probs.sum()

            # Normalize if needed
            target_sum = income_group_probabilities.get(income_group, 0)
            if group_sum > 0 and target_sum > 0 and abs(group_sum - target_sum) > 0.001:
                norm_factor = target_sum / group_sum

                for idx in df.loc[mask].index:
                    if pd.notna(df.at[idx, 'sampleProbabilityWithinCategory']):
                        current_prob = pd.to_numeric(df.at[idx, 'sampleProbabilityWithinCategory'], errors='coerce')
                        if not pd.isna(current_prob):
                            df.at[idx, 'sampleProbabilityWithinCategory'] = current_prob * norm_factor
                            update_normalized_probability_string(df, idx, income_group)


def update_normalized_probability_string(df, idx, income_group):
    """Update probability string after normalization"""
    if pd.isna(df.at[idx, 'sampleProbabilityString']):
        return

    has_ridehail = 'ridehail' in df.at[idx, 'sampleProbabilityString']
    parts = df.at[idx, 'sampleProbabilityString'].split(';')

    if has_ridehail and len(parts) >= 2:
        ridehail_part = parts[0]
        income_part_prefix = parts[1].split(':')[0]
        updated_prob = df.at[idx, 'sampleProbabilityWithinCategory']
        df.at[idx, 'sampleProbabilityString'] = f"{ridehail_part}; {income_part_prefix}:{updated_prob}"
    elif len(parts) >= 1:
        if len(parts) >= 2:
            first_part = parts[0]
            income_prefix = parts[1].split(':')[0]
            updated_prob = df.at[idx, 'sampleProbabilityWithinCategory']
            df.at[idx, 'sampleProbabilityString'] = f"{first_part}; {income_prefix}:{updated_prob}"
        else:
            updated_prob = df.at[idx, 'sampleProbabilityWithinCategory']
            df.at[idx, 'sampleProbabilityString'] = f"income | {income_group}:{updated_prob}"


def normalize_ridehail_probabilities(df):
    """Normalize ridehail probabilities to sum to 1.0 for each beam class"""
    has_any_ridehail = any('ridehail' in str(row.get('sampleProbabilityString', ''))
                           for _, row in df.iterrows() if pd.notna(row.get('sampleProbabilityString', '')))

    if not has_any_ridehail:
        return

    for beam_class in df['beamClass'].unique():
        beam_mask = df['beamClass'] == beam_class

        # Extract ridehail probabilities
        ridehail_probs = []
        for idx in df.loc[beam_mask].index:
            if pd.notna(df.at[idx, 'sampleProbabilityString']):
                try:
                    parts = df.at[idx, 'sampleProbabilityString'].split(';')
                    if len(parts) >= 1 and 'ridehail' in parts[0]:
                        ridehail_part = parts[0].strip()
                        prob_str = ridehail_part.split(':')[-1].strip()
                        prob = float(prob_str)
                        ridehail_probs.append((idx, prob))
                except:
                    continue

        # Skip if no valid ridehail probabilities
        if not ridehail_probs:
            continue

        # Calculate sum and normalize if needed
        indices, values = zip(*ridehail_probs)
        prob_sum = sum(values)

        if prob_sum > 0 and abs(prob_sum - 1.0) > 0.001:
            norm_factor = 1.0 / prob_sum

            # Update probabilities
            for idx, _ in ridehail_probs:
                parts = df.at[idx, 'sampleProbabilityString'].split(';')
                if len(parts) >= 1:
                    ridehail_prefix = parts[0].split(':')[0] + ':'
                    income_part = parts[1] if len(parts) > 1 else ""

                    current_prob = float(parts[0].split(':')[-1].strip())
                    updated_prob = current_prob * norm_factor

                    if income_part:
                        df.at[idx, 'sampleProbabilityString'] = f"{ridehail_prefix}{updated_prob}; {income_part}"
                    else:
                        df.at[idx, 'sampleProbabilityString'] = f"{ridehail_prefix}{updated_prob}"
