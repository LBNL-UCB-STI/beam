import os
import sys
import math
import random
import pandas as pd
import numpy as np

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import BeamClasses

operation_dict = {
    'loading': 'Pick up Cargo',
    'unloading': 'Delivery of Cargo'
}

vehicle_classes = BeamClasses.get_medium_heavy_freight_classes()


def get_base_duration(df, weight_dict):
    """
    Calculate base duration for each vehicle class.

    Args:
        df: DataFrame with survey data
        weight_dict: Optional dictionary mapping weight ranges to bin labels

    Returns:
        Dictionary mapping vehicle classes to base durations (in seconds)
    """
    # Create a copy to avoid modifying the original DataFrame
    df_copy = df.copy()

    # Create bins for cargo weights based on the weight_dict
    df_copy['cargoWeightPU_bin'] = df_copy['cargoWeightPU'].apply(lambda x: get_weight_bin(x, weight_dict))
    df_copy['cargoWeightDO_bin'] = df_copy['cargoWeightDO'].apply(lambda x: get_weight_bin(x, weight_dict))

    # Group by vehicle class, activity type, and weight bins
    groupby_columns = ['vehicleClass', 'activityType', 'cargoWeightPU_bin', 'cargoWeightDO_bin']

    # Group and calculate average operation duration
    grouped_data = df_copy.groupby(groupby_columns)['operationDurationInMin'].agg(['mean', 'count']).reset_index()

    print("\nGrouped data summary:")
    print(f"Total groups: {len(grouped_data)}")

    # Find the minimum average duration for each vehicle class across all combinations
    min_durations = {}
    for vehicle_class in vehicle_classes:
        class_data = grouped_data[grouped_data['vehicleClass'] == vehicle_class]

        min_duration = class_data['mean'].min()
        min_durations[vehicle_class] = min_duration
        print(f"{vehicle_class}: Minimum average duration = {min_duration:.2f} minutes")

        # Show the specific combination that resulted in the minimum
        if len(class_data) > 0:
            min_idx = class_data[class_data['mean'] == min_duration].index[0]
            min_row = class_data.iloc[min_idx]
            print(f"  Combination: {min_row[groupby_columns].to_dict()}")
            print(f"  Count: {min_row['count']} records")

    # Convert minutes to seconds for the base_duration dictionary
    min_durations_in_sec = {}
    for vehicle_class, min_duration in min_durations.items():
        # Convert to seconds and round to nearest minute
        min_durations_in_sec[vehicle_class] = int(round(min_duration * 60 / 60) * 60)

    return min_durations_in_sec


def get_weight_factor(df, base_duration):
    """
    Calculate weight factor for each vehicle class based on the relationship between
    cargo weight and operation duration in the survey data.
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Group by vehicle class
    weight_factors = {}

    for vehicle_class in vehicle_classes:
        class_data = df_copy[df_copy['vehicleClass'] == vehicle_class]
        loading_df = class_data[class_data['activityType'] == operation_dict["loading"]]
        unloading_df = class_data[class_data['activityType'] == operation_dict["unloading"]]

        # Set effective weight for each activity type
        loading_df['effectiveWeight'] = loading_df['cargoWeightPU']
        unloading_df['effectiveWeight'] = unloading_df['cargoWeightDO']

        # Combine datasets for weight factor calculation
        combined_df = pd.concat([loading_df, unloading_df])
        combined_df = combined_df[combined_df['effectiveWeight'] > 0]

        # Get base duration (convert seconds to minutes)
        base = base_duration[vehicle_class] / 60

        # Calculate individual factors for each record
        combined_df['individual_factor'] = (combined_df['operationDurationInMin'] - base) / combined_df['effectiveWeight']

        # Use median to avoid remaining outliers
        median_factor = combined_df['individual_factor'].median()

        # Convert from minutes/kg to seconds/kg
        weight_factors[vehicle_class] = max(0, median_factor * 60)


    print("\nWeight factors:")
    for vehicle_class, factor in weight_factors.items():
        print(f"{vehicle_class}: {factor:.6f} seconds per kg")

    return weight_factors


def get_operation_factor(df):
    """
    Calculate operation factor (loading vs unloading) for each vehicle class.
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Group and calculate average operation duration normalized by weight
    grouped_data = df_copy.groupby(['vehicleClass', 'activityType'])['operationDurationInMin'].mean().reset_index()

    # Calculate operation factors relative to unloading
    operation_factors = {}

    for vehicle_class in vehicle_classes:
        class_data = grouped_data[grouped_data['vehicleClass'] == vehicle_class]
        loading_df = class_data[class_data['activityType'] == operation_dict["loading"]]
        unloading_df = class_data[class_data['activityType'] == operation_dict["unloading"]]

        loading_duration = loading_df['operationDurationInMin'].values[0]
        unloading_duration = unloading_df['operationDurationInMin'].values[0]

        loading_ratio = loading_duration / (loading_duration+unloading_duration)
        unloading_ratio = 1 - loading_ratio

        operation_factors[vehicle_class] = {
            'loading': 2 * loading_ratio,
            'unloading': 2 * unloading_ratio
        }

    print("\nOperation factors:")
    for vehicle_class, factors in operation_factors.items():
        print(f"{vehicle_class}: loading = {factors['loading']:.2f}, unloading = {factors['unloading']:.2f}")

    return operation_factors


def extract_weight_bins(df, num_bins=7):
    """
    Extract weight bins from the data dynamically.

    Args:
        df: DataFrame with weight data
        num_bins: Number of bins to create

    Returns:
        Dictionary mapping (lower, upper) bounds to bin labels
    """
    # Combine pickup and delivery weights
    weights = []
    for _, row in df.iterrows():
        if row['activityType'] == operation_dict["loading"]:
            weights.append(row['cargoWeightPU'])
        elif row['activityType'] == operation_dict["unloading"]:
            weights.append(row['cargoWeightDO'])

    # Remove extreme outliers to prevent skewing the bin boundaries
    weights = np.array(weights)
    q1, q3 = np.percentile(weights, [25, 75])
    iqr = q3 - q1
    lower_bound = q1 - 1.5 * iqr
    upper_bound = q3 + 1.5 * iqr
    filtered_weights = weights[(weights >= max(0, lower_bound)) & (weights <= upper_bound)]

    # Use percentile-based bins to ensure even distribution of data
    percentiles = np.linspace(0, 100, num_bins + 1)
    bin_edges = np.percentile(filtered_weights, percentiles)

    # Round bin edges for better readability
    bin_edges = np.unique([round(edge, -1) for edge in bin_edges])

    # Ensure the bins are strictly increasing
    bin_edges = np.unique(bin_edges)
    if bin_edges[0] > 0:
        bin_edges = np.insert(bin_edges, 0, 0)
    if bin_edges[-1] != float('inf'):
        bin_edges = np.append(bin_edges, float('inf'))

    # Create weight_dict
    weight_dict = {}
    for i in range(len(bin_edges) - 1):
        lower = bin_edges[i]
        upper = bin_edges[i + 1]

        # Format the label based on weight magnitude
        if upper < 1000:
            label = f"{int(lower)}-{int(upper)}lb"
        elif upper < 10000:
            label = f"{int(lower / 1000)}k-{int(upper / 1000)}klb"
        elif upper == float('inf'):
            label = f"{int(lower / 1000)}k+lb"
        else:
            label = f"{int(lower / 1000)}k-{int(upper / 1000)}klb"

        # For the last bin, use infinity
        if i == len(bin_edges) - 2:
            label = f"{int(lower)}+lb"

        weight_dict[(lower, upper)] = label

    return weight_dict


def get_weight_bin(weight_value, weight_dict):
    """
    Determine the weight bin for a given weight value.

    Args:
        weight_value: The weight value
        weight_dict: Dictionary mapping (lower, upper) bounds to bin labels

    Returns:
        The bin label for the weight value
    """
    for (lower, upper), label in weight_dict.items():
        if lower <= weight_value < upper:
            return label

    # Fallback for any value not covered (should not happen with properly defined bins)
    return list(weight_dict.values())[-1]  # Return the highest bin


def calculate_lognormal_params(mean, std):
    """
    Calculate mu and sigma parameters for lognormal distribution
    given desired mean and standard deviation.
    """
    # Avoid division by zero or negative values
    if mean <= 0 or std <= 0:
        return {'mu': 0, 'sigma': 1}

    # Calculate variance
    variance = std ** 2

    # Calculate sigma squared
    sigma_squared = math.log(1 + (variance / (mean ** 2)))

    # Calculate sigma
    sigma = math.sqrt(sigma_squared)

    # Calculate mu
    mu = math.log(mean) - (sigma_squared / 2)

    return {'mu': mu, 'sigma': sigma}


def build_operation_duration_model(weight_dict, base_durations,
                                   weight_factors, operation_factors, variability_factors,
                                   variability_exponent=0.7):
    """
    Build a nested model for operation durations from predefined factors.

    Args:
        vehicle_classes: List of vehicle classes
        operation_dict: Dictionary mapping activity types to standardized operation types
        weight_dict: Dictionary mapping weight ranges to bin labels
        base_durations: Dictionary mapping vehicle classes to base durations (in seconds)
        weight_factors: Dictionary mapping vehicle classes to weight factors (seconds per lb)
        operation_factors: Nested dictionary mapping vehicle classes and operation types to factors
        variability_factors: Dictionary mapping vehicle classes to variability factors
        variability_exponent: Exponent for the utility-based variability model (default: 0.7)

    Returns:
        Nested model structure
    """
    # Build the nested model structure
    model = {}

    # First level: Vehicle Class
    for vehicle_class in vehicle_classes:
        model[vehicle_class] = {}

        # Get the variability factor for this vehicle class
        variability = variability_factors[vehicle_class]

        # Second level: Operation Type
        for _, standard_op_type in operation_dict.values():
            model[vehicle_class][standard_op_type] = {}

            # Get operation factor for this combination
            op_factor = operation_factors[vehicle_class][standard_op_type]

            # Third level: Weight Bins
            for (lower_bound, upper_bound), bin_label in weight_dict.items():
                # Calculate the midpoint of the weight bin for reference
                if upper_bound == float('inf'):
                    midpoint = lower_bound * 1.5
                else:
                    midpoint = (lower_bound + upper_bound) / 2

                # Get the base duration for this vehicle class (in seconds)
                base = base_durations[vehicle_class]

                # Get the weight factor for this vehicle class (seconds per lb)
                weight_factor = weight_factors[vehicle_class]

                # Calculate mean duration using the formula
                mean_duration_sec = (base + midpoint * weight_factor) * op_factor

                # Convert to minutes
                mean_duration_min = mean_duration_sec / 60

                # Utility approach: variability is a non-linear function of duration
                # Using the configurable exponent parameter
                std_duration_min = variability * mean_duration_min ** variability_exponent

                # Create the distribution parameters
                model[vehicle_class][standard_op_type][bin_label] = {
                    'distribution': 'lognormal',  # More realistic for durations
                    'params': calculate_lognormal_params(mean_duration_min, std_duration_min),
                    'mean': mean_duration_min,
                    'std': std_duration_min,
                    'bounds': {
                        'min': max(1, mean_duration_min - 2.5 * std_duration_min),
                        'max': mean_duration_min + 3 * std_duration_min
                    }
                }

    return model


def sample_operation_duration(model, weight_dict, vehicle_class, operation_type, weight_lbs, fallback_duration=30):
    """
    Sample an operation duration from the model based on vehicle class, operation type, and weight.

    Args:
        model: The nested model structure
        weight_dict: Dictionary mapping weight ranges to bin labels
        vehicle_class: The vehicle class (e.g., 'Class456Vocational')
        operation_type: Either 'loading' or 'unloading'
        weight_lbs: The weight in pounds (lbs)

    Returns:
        Duration in minutes
    """
    # Determine weight bin
    weight_bin = get_weight_bin(weight_lbs, weight_dict)

    # Handle missing weight bin
    if weight_bin not in model[vehicle_class][operation_type]:
        print(f"Warning: Weight bin '{weight_bin}' not found for {vehicle_class}/{operation_type}, finding closest.")

        # Find the closest weight bin that has data
        available_bins = list(model[vehicle_class][operation_type].keys())
        bin_midpoints = {}
        for bin_label in available_bins:
            # Extract approximate midpoint from bin names
            # This is an approximation that works with our naming convention
            if '-' in bin_label:
                parts = bin_label.replace('lb', '').replace('k', '000').split('-')
                try:
                    lower = float(parts[0])
                    upper = float(parts[1])
                    bin_midpoints[bin_label] = (lower + upper) / 2
                except:
                    bin_midpoints[bin_label] = 0
            elif '+' in bin_label:
                try:
                    lower = float(bin_label.replace('+lb', '').replace('k', '000'))
                    bin_midpoints[bin_label] = lower * 1.5  # Approximation for "+" bins
                except:
                    bin_midpoints[bin_label] = float('inf')

        # Find bin with closest midpoint to our weight
        closest_bin = min(available_bins, key=lambda x: abs(bin_midpoints.get(x, 0) - weight_lbs))
        weight_bin = closest_bin

    # Get the distribution for this combination
    distribution = model[vehicle_class][operation_type][weight_bin]

    # Sample a duration
    if distribution['count'] > 0:
        # If we have multiple values, randomly sample from the actual distribution
        if distribution['count'] > 1:
            duration = random.choice(distribution['durations'])
        else:
            # Just one value, use it directly
            duration = distribution['durations'][0]

        # Add some random variation (±10%)
        variation_factor = random.uniform(0.9, 1.1)
        duration = duration * variation_factor

        # Round to nearest minute
        duration = round(duration)

        return duration
    else:
        return fallback_duration


def process_austin_survey_data(survey_data):
    print("Extracting model parameters from survey data...")
    # Print summary of the survey data
    print(f"Found {len(survey_data)} valid records in survey data")
    print(f"Vehicle classes: {', '.join(survey_data['vehicleClass'].unique())}")
    print(f"Activity types: {', '.join(survey_data['activityType'].unique())}")

    survey_data2 = survey_data[survey_data['activityType'].isin(operation_dict.values())]
    survey_data2 = survey_data2[survey_data2['vehicleClass'].isin(
        BeamClasses.get_medium_heavy_freight_classes()
    )].copy()

    # Use the extract_weight_bins function to get data-driven weight bins
    weight_dict = extract_weight_bins(survey_data2)

    # Print the extracted weight bins
    print("\nExtracted weight bins:")
    for (lower, upper), label in sorted(weight_dict.items(), key=lambda x: x[0][0]):
        print(f"  {label}: {lower} to {upper} lbs")

    # Extract base durations
    base_durations = get_base_duration(survey_data2, weight_dict)
    print("\nExtracted base durations (seconds):")
    for vc, duration in base_durations.items():
        print(f"  {vc}: {duration} seconds")

    # Extract weight factors
    weight_factors = get_weight_factor(survey_data2, base_durations)
    print("\nExtracted weight factors (seconds per lb):")
    for vc, factor in weight_factors.items():
        print(f"  {vc}: {factor:.6f} seconds per lb")

    # Extract operation factors
    operation_factors = get_operation_factor(survey_data2)
    print("\nExtracted operation factors:")
    for vc, factors in operation_factors.items():
        print(f"  {vc}: loading={factors['loading']:.2f}, unloading={factors['unloading']:.2f}")

    # Calculate variability factors from the data
    variability_factors = {}
    for vehicle_class in vehicle_classes:
        # Filter data for this vehicle class
        class_data = survey_data2[survey_data2['vehicleClass'] == vehicle_class]
        # Calculate coefficient of variation (std/mean)
        cv = class_data['operationDurationInMin'].std() / class_data['operationDurationInMin'].mean()
        # Adjust CV to work with our utility model
        variability_factors[vehicle_class] = min(0.5, max(0.1, cv))

    print("\nCalculated variability factors:")
    for vc, factor in variability_factors.items():
        print(f"  {vc}: {factor:.2f}")

    return base_durations, weight_factors, operation_factors, variability_factors


def update_operation_duration(study_area_config, payloads, tours, carriers, vehicle_types,
                              variability_exponent=0.7, weight_dict=None, num_weight_bins=7):
    """
    Update operation durations based on factors extracted from survey data.

    Args:
        austin_survey: Survey data with operation durations
        payloads: Payload data to update
        tours: Tour data
        carriers: Carrier data
        vehicle_types: Vehicle type data
        variability_exponent: Exponent for the utility-based variability model
        weight_dict: Optional predefined weight bins dictionary
        num_weight_bins: Number of weight bins to create if weight_dict is not provided

    Returns:
        Updated payload dataframe with operation durations
    """
    vehicle_classes, operation_dict, base_durations, weight_factors, operation_factors, variability_factors \
        = process_austin_survey_data(
        os.path.join(study_area_config["work_dir"], study_area_config["freight"]["stops_data"])
    )

    # Build the model from factors
    duration_model = build_operation_duration_model(
        vehicle_classes,
        operation_dict,
        weight_dict,
        base_durations,
        weight_factors,
        operation_factors,
        variability_factors,
        variability_exponent
    )

    # Print model statistics
    print("Operation Duration Model Summary:")
    for vehicle_class in duration_model:
        print(f"\nVehicle Class: {vehicle_class}")
        for op_type in duration_model[vehicle_class]:
            print(f"  Operation Type: {op_type}")
            for weight_bin in duration_model[vehicle_class][op_type]:
                stats = duration_model[vehicle_class][op_type][weight_bin]
                print(
                    f"    {weight_bin}: {stats['count']} records, mean={stats['mean']:.1f}min, std={stats['std']:.1f}min")

    # Create a copy to avoid modifying the original DataFrame
    updated_payloads = payloads.copy()

    # Merge tours with carriers and vehicle_types to get vehicle information
    tours_with_vehicle = tours.merge(
        carriers,
        on='tourId',
        how='left'
    )

    # Now merge with vehicle_types
    tours_with_vehicle = tours_with_vehicle.merge(
        vehicle_types,
        on='vehicleTypeId',
        how='left'
    )

    tours_with_vehicle = tours_with_vehicle.drop_duplicates(subset=['tourId', 'vehicleTypeId'], keep='first')

    # Then, merge payloads with the combined tours/vehicle data to get vehicle info for each payload
    payload_with_vehicle = updated_payloads.merge(
        tours_with_vehicle[['tourId', 'vehicleCategory']],
        on='tourId',
        how='left'
    )

    # Map requestType to operation_type
    operation_type_map = {
        'loading': 'loading',
        'unloading': 'unloading',
        # Add more mappings if needed
    }

    # Calculate updated durations
    def calculate_duration(row):
        # The weight is in lbs, so we use it directly
        weight_lbs = abs(row['weightInKg'])  # Assuming weightInKg is actually in lbs despite the column name
        category = row['vehicleCategory']
        operation_type = operation_type_map.get(row['requestType'], 'loading')

        # Sample from the model
        duration_min = sample_operation_duration(duration_model, category, operation_type, weight_lbs)

        # Convert to seconds
        return duration_min * 60

    # Apply the calculation to each row
    payload_with_vehicle['operationDurationInSec'] = payload_with_vehicle.apply(calculate_duration, axis=1)

    updated_columns = payloads.columns.tolist()
    return payload_with_vehicle[updated_columns]
