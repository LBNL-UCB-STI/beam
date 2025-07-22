import math
import os
import sys

import numpy as np
import pandas as pd
import random
from collections import defaultdict

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import BeamClasses
from python.utils.study_area_config import get_area_config

vehicle_classes = BeamClasses.get_medium_heavy_freight_classes()


def sample_from_distribution(distribution_info):
    """
    Sample a value from a distribution based on its parameters.
    """
    if distribution_info['distribution'] == 'lognormal':
        params = distribution_info['params']
        bounds = distribution_info['bounds']

        # Sample from lognormal distribution
        try:
            sample = float(np.random.lognormal(
                float(params['mu']),
                float(params['sigma'])
            ))
        except (ValueError, TypeError):
            # Fallback if parameters are invalid
            sample = float(distribution_info['mean'])

        # Apply bounds
        sample = max(float(bounds['min']), min(float(bounds['max']), sample))

        # Round to nearest minute
        return round(sample)
    else:
        # Default to mean if distribution type not recognized
        return round(float(distribution_info['mean']))


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
        class_data = grouped_data[grouped_data['vehicleClass'] == vehicle_class].copy().reset_index()

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


def get_weight_factor(df, base_duration, operation_dict):
    """
    Calculate weight factor for each vehicle class based on the relationship between
    cargo weight and operation duration in the survey data.
    """
    # Create a copy to avoid warnings
    df_copy = df.copy()

    # Group by vehicle class
    weight_factors = {}

    for vehicle_class in vehicle_classes:
        class_data = df_copy[df_copy['vehicleClass'] == vehicle_class].copy()
        loading_df = class_data[class_data['activityType'] == operation_dict["loading"]].copy()
        unloading_df = class_data[class_data['activityType'] == operation_dict["unloading"]].copy()

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
        print(f"    {vehicle_class}: {factor:.6f} seconds per kg")

    return weight_factors


def get_operation_factor(df, operation_dict):
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
        print(f"    {vehicle_class}: loading = {factors['loading']:.2f}, unloading = {factors['unloading']:.2f}")

    return operation_factors


def extract_weight_bins(df, operation_dict, num_bins=7):
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


def build_operation_duration_model_from_austin_survey(survey_file_path, variability_exponent=0.7):
    # Process the survey data to extract parameters
    operation_dict, weight_dict, base_durations, weight_factors, operation_factors, variability_factors = \
        process_austin_survey_data(survey_file_path)

    # Create the model class
    duration_model = OperationDurationModel(
        operation_dict,
        weight_dict,
        base_durations,
        weight_factors,
        operation_factors,
        variability_factors,
        variability_exponent
    )

    return duration_model



def build_operation_duration_model(operation_dict, weight_dict, base_durations,
                                   weight_factors, operation_factors, variability_factors,
                                   variability_exponent=0.7):
    """
    Build a nested model for operation durations from predefined factors.

    Args:
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
    model = {
        "weight_dict": weight_dict,
        "sample_func": sample_operation_duration
    }

    # First level: Vehicle Class
    for vehicle_class in vehicle_classes:
        model[vehicle_class] = {}

        # Get the variability factor for this vehicle class
        variability = variability_factors[vehicle_class]

        # Second level: Operation Type (fixed missing .items())
        for operation_key, standard_op_type in operation_dict.items():
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
                    'count': 10,  # Add a default count for compatibility
                    'bounds': {
                        'min': max(1, mean_duration_min - 2.5 * std_duration_min),
                        'max': mean_duration_min + 3 * std_duration_min
                    }
                }

    return model


def find_closest_bin(weight_lbs, weight_bins, available_bins):
    # Find closest bin based on numeric weight value
    closest_bin = available_bins[0]
    closest_distance = float('inf')

    for bin_name in available_bins:
        # Find the bin that would contain this weight
        for (lower, upper), label in weight_bins.items():
            if label == bin_name:
                # Calculate midpoint of this bin
                if upper == float('inf'):
                    midpoint = lower * 1.5  # Approximate midpoint for highest bin
                else:
                    midpoint = (lower + upper) / 2

                # Check if this is closer to our target weight
                distance = abs(midpoint - weight_lbs)
                if distance < closest_distance:
                    closest_distance = distance
                    closest_bin = bin_name
                break

    return closest_bin


def sample_operation_duration(model, vehicle_class, operation_type, weight_lbs, fallback_duration=30):
    """
    Sample an operation duration from the model based on vehicle class, operation type, and weight.
    """
    # Handle empty model
    if not model or vehicle_class not in model or operation_type not in model[vehicle_class]:
        return fallback_duration

    # Get weight dictionary
    weight_dict = model.get("weight_dict", {})
    if not weight_dict:
        return fallback_duration

    # Determine weight bin
    weight_bin = get_weight_bin(weight_lbs, weight_dict)

    # If weight bin isn't found in the model for this combination, find closest bin
    if weight_bin not in model[vehicle_class][operation_type]:
        available_bins = list(model[vehicle_class][operation_type].keys())
        if not available_bins:
            return fallback_duration
        weight_bin = find_closest_bin(weight_lbs, weight_dict, available_bins)

    # Get the distribution for this combination
    distribution = model[vehicle_class][operation_type][weight_bin]

    # Sample a duration using the distribution parameters
    return sample_from_distribution(distribution)


def process_austin_survey_data(survey_file_path):
    """
    Process the Austin survey data to extract parameters for the operation duration model.

    Args:
        survey_file_path: Path to the survey data file

    Returns:
        Tuple of (vehicle_classes, operation_dict, base_durations, weight_factors,
               operation_factors, variability_factors, weight_dict)
    """
    print("Loading survey data from:", survey_file_path)

    # Load the survey data
    survey_data = pd.read_csv(survey_file_path)

    print("Extracting model parameters from survey data...")
    # Print summary of the survey data
    print(f"Found {len(survey_data)} valid records in survey data")
    print(f"Vehicle classes: {', '.join(survey_data['vehicleClass'].unique())}")
    print(f"Activity types: {', '.join(survey_data['activityType'].unique())}")

    operation_dict = {
        'loading': 'Pick up Cargo',
        'unloading': 'Delivery of Cargo'
    }

    # Filter data for relevant activity types and vehicle classes
    survey_data2 = survey_data[survey_data['activityType'].isin(operation_dict.values())]
    survey_data2 = survey_data2[survey_data2['vehicleClass'].isin(vehicle_classes)].copy()

    # Use the extract_weight_bins function to get data-driven weight bins
    weight_dict = extract_weight_bins(survey_data2, operation_dict)

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
    weight_factors = get_weight_factor(survey_data2, base_durations, operation_dict)
    print("\nExtracted weight factors (seconds per lb):")
    for vc, factor in weight_factors.items():
        print(f"  {vc}: {factor:.6f} seconds per lb")

    # Extract operation factors
    operation_factors = get_operation_factor(survey_data2, operation_dict)
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

    return operation_dict, weight_dict, base_durations, weight_factors, operation_factors, variability_factors


def update_operation_duration(config, payloads, tours, carriers, vehicle_types):
    """
    Update operation durations based on factors extracted from survey data.
    """
    survey_file_path = os.path.join(config["work_dir"],
                                    config["stops_data"])

    # Create and load the model
    duration_model = SimpleStopDurationModel()
    duration_model.load_survey_data(survey_file_path)

    # # Build the model from Austin survey data
    # duration_model = build_operation_duration_model_from_austin_survey(survey_file_path)

    # Print model summary
    duration_model.print_summary()

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

    # Calculate updated durations
    def calculate_duration(row):
        # Sample from the model
        duration_min = duration_model.sample_duration(
            row['vehicleCategory'],
            row['requestType'],
            row['weightInKg'] * 2.20462,  # Convert kg to lbs
            randomize_factor=0
        )
        # Convert to seconds
        return duration_min * 60

    # Apply the calculation to each row
    payload_with_vehicle['operationDurationInSec'] = payload_with_vehicle.apply(calculate_duration, axis=1)

    updated_columns = payloads.columns.tolist()
    return payload_with_vehicle[updated_columns]


class OperationDurationModel:
    def __init__(self, operation_dict, weight_dict, base_durations, weight_factors, operation_factors,
                 variability_factors, variability_exponent=0.7):
        """
        Initialize the operation duration model.

        Args:
            operation_dict: Dictionary mapping operation keys to operation types
            weight_dict: Dictionary mapping weight ranges to bin labels
            base_durations: Dictionary mapping vehicle classes to base durations (in seconds)
            weight_factors: Dictionary mapping vehicle classes to weight factors (seconds per lb)
            operation_factors: Nested dictionary mapping vehicle classes and operation types to factors
            variability_factors: Dictionary mapping vehicle classes to variability factors
            variability_exponent: Exponent for the utility-based variability model (default: 0.7)
        """
        self.operation_dict = operation_dict
        self.weight_bins = weight_dict
        self.base_durations = base_durations
        self.weight_factors = weight_factors
        self.operation_factors = operation_factors
        self.variability_factors = variability_factors
        self.variability_exponent = variability_exponent

        # Build the model structure
        self.model = self._build_model()

    def _build_model(self):
        """Build the nested model structure from the provided factors."""
        model = {}

        # First level: Vehicle Class
        for vehicle_class in vehicle_classes:
            model[vehicle_class] = {}

            # Get the variability factor for this vehicle class
            variability = self.variability_factors[vehicle_class]

            # Second level: Operation Type
            for operation_key, standard_op_type in self.operation_dict.items():
                model[vehicle_class][operation_key] = {}

                # Get operation factor for this combination
                op_factor = self.operation_factors[vehicle_class][operation_key]

                # Third level: Weight Bins
                for (lower_bound, upper_bound), bin_label in self.weight_bins.items():
                    # Calculate the midpoint of the weight bin for reference
                    if upper_bound == float('inf'):
                        midpoint = lower_bound * 1.5
                    else:
                        midpoint = (lower_bound + upper_bound) / 2

                    # Get the base duration for this vehicle class (in seconds)
                    base = self.base_durations[vehicle_class]

                    # Get the weight factor for this vehicle class (seconds per lb)
                    weight_factor = self.weight_factors[vehicle_class]

                    # Calculate mean duration using the formula
                    mean_duration_sec = (base + midpoint * weight_factor) * op_factor

                    # Convert to minutes
                    mean_duration_min = mean_duration_sec / 60

                    # Utility approach: variability is a non-linear function of duration
                    std_duration_min = variability * mean_duration_min ** self.variability_exponent

                    # Create the distribution parameters
                    model[vehicle_class][operation_key][bin_label] = {
                        'distribution': 'lognormal',
                        'params': calculate_lognormal_params(mean_duration_min, std_duration_min),
                        'mean': mean_duration_min,
                        'std': std_duration_min,
                        'count': 10,
                        'bounds': {
                            'min': max(1, mean_duration_min - 2.5 * std_duration_min),
                            'max': mean_duration_min + 3 * std_duration_min
                        }
                    }

        return model


    def sample_operation_duration(self, vehicle_class, operation_type, weight_lbs, fallback_duration=30):
        """
        Sample an operation duration from the model based on vehicle class, operation type, and weight.

        Args:
            vehicle_class: The vehicle class (e.g., 'Class456Vocational')
            operation_type: Either 'loading' or 'unloading'
            weight_lbs: The weight in pounds (lbs)
            fallback_duration: Default duration if sampling fails

        Returns:
            Duration in minutes
        """
        # Handle empty model
        if not self.model or vehicle_class not in self.model or operation_type not in self.model[vehicle_class]:
            return fallback_duration

        # Determine weight bin
        weight_bin = get_weight_bin(weight_lbs, self.weight_bins)

        # If weight bin isn't found in the model for this combination, find closest bin
        if weight_bin not in self.model[vehicle_class][operation_type]:
            available_bins = list(self.model[vehicle_class][operation_type].keys())
            if not available_bins:
                return fallback_duration
            weight_bin = find_closest_bin(weight_lbs, self.weight_bins, available_bins)


        # Get the distribution for this combination
        distribution = self.model[vehicle_class][operation_type][weight_bin]

        # Sample a duration using the distribution parameters
        return sample_from_distribution(distribution)

    def print_summary(self):
        """Print a summary of the model statistics."""
        print("Operation Duration Model Summary:")
        for vehicle_class in self.model:
            print(f"\nVehicle Class: {vehicle_class}")
            for op_type in self.model[vehicle_class]:
                print(f"  Operation Type: {op_type}")
                for weight_bin in self.model[vehicle_class][op_type]:
                    stats = self.model[vehicle_class][op_type][weight_bin]
                    print(f"    {weight_bin}: mean={stats['mean']:.1f}min, std={stats['std']:.1f}min")


class SimpleStopDurationModel:
    """
    A simplified decision tree model for stop durations based on:
    - Vehicle class
    - Operation type (loading/unloading)
    - Weight bins

    The model samples actual durations from the survey data and adds randomness.
    """

    def __init__(self):
        """Initialize the model with empty structure."""
        # Main structure to hold the decision tree
        self.duration_tree = defaultdict(
            lambda: defaultdict(
                lambda: defaultdict(list)
            )
        )

        # Weight bins dictionary
        self.weight_bins = {}

        # Operation type mapping
        self.operation_dict = {
            'loading': 'Pick up Cargo',
            'unloading': 'Delivery of Cargo'
        }

        # For reporting statistics
        self.stats = {}

    def load_survey_data(self, survey_file_path):
        """
        Load the Austin survey data and organize it into the decision tree.

        Args:
            survey_file_path: Path to the survey data CSV file
        """
        print(f"Loading survey data from: {survey_file_path}")

        # Load the survey data
        survey_data = pd.read_csv(survey_file_path)

        # Print summary of the survey data
        print(f"Found {len(survey_data)} records in survey data")
        print(f"Vehicle classes: {', '.join(survey_data['vehicleClass'].unique())}")
        print(f"Activity types: {', '.join(survey_data['activityType'].unique())}")

        # Filter data for relevant activity types and vehicle classes
        filtered_data = survey_data[
            survey_data['activityType'].isin(self.operation_dict.values()) &
            survey_data['vehicleClass'].isin(vehicle_classes)
            ].copy()

        print(f"Filtered to {len(filtered_data)} relevant records")

        self.weight_bins = extract_weight_bins(filtered_data, self.operation_dict)

        # Build the decision tree from the filtered data
        self._build_decision_tree(filtered_data)

        # Calculate statistics for reporting
        self._calculate_statistics()

        return self

    def _build_decision_tree(self, data):
        """
        Build the decision tree from the filtered data.

        Args:
            data: Filtered DataFrame with survey data
        """
        # Process each row in the data
        for _, row in data.iterrows():
            # Get the vehicle class
            vehicle_class = row['vehicleClass']

            # Get the operation type
            activity = row['activityType']
            operation_type = 'loading' if activity == self.operation_dict['loading'] else 'unloading'

            # Get the weight and determine its bin
            weight = row['cargoWeightPU'] if operation_type == 'loading' else row['cargoWeightDO']

            weight_bin = get_weight_bin(weight, self.weight_bins)

            # Get the duration
            duration = row['operationDurationInMin']

            # Add the duration to the appropriate leaf in the tree
            self.duration_tree[vehicle_class][operation_type][weight_bin].append(duration)

    def _calculate_statistics(self):
        """Calculate and store statistics for each leaf in the tree."""
        for vehicle_class in self.duration_tree:
            self.stats[vehicle_class] = {}

            for operation_type in self.duration_tree[vehicle_class]:
                self.stats[vehicle_class][operation_type] = {}

                for weight_bin in self.duration_tree[vehicle_class][operation_type]:
                    durations = self.duration_tree[vehicle_class][operation_type][weight_bin]

                    if durations:
                        self.stats[vehicle_class][operation_type][weight_bin] = {
                            'count': len(durations),
                            'min': min(durations),
                            'max': max(durations),
                            'mean': sum(durations) / len(durations),
                            'median': sorted(durations)[len(durations) // 2],
                            'std': np.std(durations) if len(durations) > 1 else 0
                        }
                    else:
                        # Empty leaf
                        self.stats[vehicle_class][operation_type][weight_bin] = {
                            'count': 0,
                            'min': 0,
                            'max': 0,
                            'mean': 0,
                            'median': 0,
                            'std': 0
                        }


    def sample_duration(self, vehicle_class, operation_type, weight_lbs, randomize_factor=1.0, fallback_duration=30):
        """
        Sample a duration for the given vehicle class, operation type, and weight.

        Args:
            vehicle_class: The vehicle class
            operation_type: The operation type (loading/unloading)
            weight_lbs: The weight in pounds
            randomize_factor: Whether to add randomness to the sampled duration

        Returns:
            The sampled duration in minutes
        """
        # Get the weight bin
        weight_bin = get_weight_bin(weight_lbs, self.weight_bins)

        # If the bin has no data, find the closest bin with data
        if weight_bin not in self.duration_tree[vehicle_class][operation_type] or not \
        self.duration_tree[vehicle_class][operation_type][weight_bin]:
            available_bins = list(self.duration_tree[vehicle_class][operation_type].keys())
            if not available_bins:
                return fallback_duration
            weight_bin = find_closest_bin(weight_lbs, self.weight_bins, available_bins)

        # Get the durations for this leaf
        durations = self.duration_tree[vehicle_class][operation_type][weight_bin]

        # Sample a random duration from the available ones
        duration = random.choice(durations)

        # Add randomness based on the randomize_factor
        if randomize_factor > 0:
            # Calculate standard deviation of durations in this bin
            std = np.std(durations) if len(durations) > 1 else duration * 0.2

            # Scale the standard deviation by the randomize_factor
            scaled_std = std * randomize_factor * 0.5

            # Add Gaussian noise, but ensure the duration remains positive
            randomized_duration = max(1, duration + random.gauss(0, scaled_std))
            return randomized_duration
        else:
            return duration

    def print_summary(self):
        """Print a summary of the model statistics."""
        print("\nSimple Stop Duration Model Summary:")

        for vehicle_class in sorted(self.stats.keys()):
            print(f"\nVehicle Class: {vehicle_class}")

            for operation_type in sorted(self.stats[vehicle_class].keys()):
                print(f"  Operation Type: {operation_type}")

                for weight_bin in sorted(self.stats[vehicle_class][operation_type].keys(),
                                         key=lambda x: next(
                                             (lower for (lower, upper), label in self.weight_bins.items() if
                                              label == x), 0)):
                    stats = self.stats[vehicle_class][operation_type][weight_bin]
                    print(f"    {weight_bin}: "
                          f"n={stats['count']}, "
                          f"mean={stats['mean']:.1f}min, "
                          f"std={stats['std']:.1f}min, "
                          f"range=[{stats['min']:.1f}-{stats['max']:.1f}]")


if __name__ == '__main__':
    STUDY_AREA_CONFIG = get_area_config("sfbay")
    work_dir = STUDY_AREA_CONFIG["work_dir"]
    scenario_config = STUDY_AREA_CONFIG["freight"]["2018_Baseline"]

    _payload_plans = pd.read_csv(str(os.path.join(work_dir, scenario_config["payloads_file"])))
    _tours = pd.read_csv(str(os.path.join(work_dir, scenario_config["tours_file"])))
    _carriers = pd.read_csv(str(os.path.join(work_dir, scenario_config["carriers_file"])))
    _vehicle_types = pd.read_csv(str(os.path.join(work_dir, scenario_config["ft_vehicle_types_file"])))

    _payload_plans["operationDurationInSecOG"] = _payload_plans["operationDurationInSec"]
    _payload_plans = update_operation_duration(STUDY_AREA_CONFIG, _payload_plans, _tours, _carriers, _vehicle_types)
    _payload_plans.to_csv("outputs/payloads_test.csv", index=False)


