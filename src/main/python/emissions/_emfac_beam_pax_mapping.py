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
