import math
import os.path
import random
import shutil
import numpy as np

# from _emfac_emissions_mapping import *
from generate_california_emissions_rates import *

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config

pd.set_option('display.max_columns', 20)

# Define class constants
beam_class_2b3 = 'Class2b3Vocational'
beam_class_46 = 'Class456Vocational'
beam_class_78_v = 'Class78Vocational'
beam_class_78_t = 'Class78Tractor'
beam_class_car = "Car"  # these include light and medium duty trucks
beam_class_bike = "Bike"
beam_class_mdp = "MediumDutyPassenger"

beam_freight_classes = [beam_class_46, beam_class_78_v, beam_class_78_t]
beam_passenger_classes = [beam_class_car, beam_class_bike, beam_class_mdp]

def calculate_distance(x1, y1, x2, y2):
    """Calculate Euclidean distance between two points"""
    return math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2)

def combine_csv_files(input_files, output_file):
    # Read and combine CSV files vertically
    combined_df = pd.concat([pd.read_csv(f) for f in input_files], ignore_index=True)

    # Write the combined dataframe to a new CSV file
    combined_df.to_csv(output_file, index=False)

    print(f"Combined CSV file has been created: {output_file}")
    return combined_df  # Return the dataframe for further processing if needed


def process_emfac_population(_study_area, _scenario_name, _work_dir, config):
    """
    Process EMFAC population data by model year, adding proportional calculations.

    Args:
        _study_area: Study area name
        _scenario_name: Scenario name
        _work_dir: Working directory path
        config: Configuration dictionary containing filtering and file path information

    Returns:
        pandas.DataFrame: Processed and grouped population data with proportion calculations
    """
    _emfac_population_output_file = os.path.join(
        _work_dir,
        f"emissions/{_study_area}_emfac_population_{_scenario_name}.csv"
    )
    if os.path.exists(_emfac_population_output_file):
        _emfac_population = pd.read_csv(_emfac_population_output_file)
        _emfac_class_map = get_emfac_beam_vehicle_class_mapping(
            _study_area, _scenario_name, _work_dir, _emfac_population["vehicle_class"].unique()
        )
    else:
        include_nan = config["filters"]["include_nan"]
        calendar_year = config["filters"]["calendar_year"]
        air_basin_area = config["filters"]["sub_area"]
        _emfac_population_by_model_year_file = os.path.join(
            _work_dir,
            config["emfac"]["emfac_pop_by_model_year_file"]
        )

        table = csv.read_csv(_emfac_population_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        df = table.to_pandas()

        # Filter by calendar year
        if 'calendar_year' in df.columns:
            df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]

        # Filter by sub area
        if 'sub_area' in df.columns:
            # Create a filter condition for partial matches
            sub_area_filter = include_nan & df['sub_area'].isna()

            for _area in air_basin_area:
                # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({_area}\\)', regex=True) | (
                        df['sub_area'] == _area)

            # Apply the filter
            df = df[sub_area_filter]

        # Convert population column to float for calculations
        if 'population' in df.columns:
            df['population'] = pd.to_numeric(df['population'], errors='coerce')

        # Categorize model years
        df['model_year_group'] = df['model_year'].apply(categorize_model_year)

        # Clean data
        df = df.fillna('')
        df = df.reset_index(drop=True)

        # Group by relevant columns and sum population
        group_col = ['vehicle_class', 'fuel', 'model_year_group']
        df_grouped = df.groupby(group_col)['population'].sum().reset_index()

        # Calculate total population across all groups
        total_population = df_grouped['population'].sum()

        # Calculate proportion of each group relative to total
        df_grouped['population_proportion'] = df_grouped['population'] / total_population

        # Create ID column for reference
        df_grouped['emfacId'] = df_grouped.apply(
            lambda row: sanitize_name(f"{row['model_year_group']}-{row['vehicle_class']}-{row['fuel']}"),
            axis=1
        )

        _emfac_population = df_grouped

        _emfac_class_map = get_emfac_beam_vehicle_class_mapping(
            _study_area, _scenario_name, _work_dir, _emfac_population["vehicle_class"].unique()
        )

        _emfac_population["beamClass"] = _emfac_population["vehicle_class"].map(_emfac_class_map)
        unmapped_classes = _emfac_population[_emfac_population["beamClass"].isna()]["vehicle_class"].unique()
        if len(unmapped_classes) > 0:
            unmapped_classes_message = "The following vehicle classes were not mapped:\n"
            formatted_list = ""
            current_line = ""

            for vehicle_class in unmapped_classes:
                # Check if adding this class would exceed the line limit
                if len(current_line + vehicle_class) > 115:  # 115 to leave room for comma and space
                    formatted_list += current_line.rstrip(", ") + "\n"
                    current_line = vehicle_class + ", "
                else:
                    current_line += vehicle_class + ", "

            # Add the last line
            if current_line:
                formatted_list += current_line.rstrip(", ")

            print(f"{unmapped_classes_message}{formatted_list}")

        _emfac_population = _emfac_population.dropna(subset=["beamClass"])

        _emfac_population.to_csv(_emfac_population_output_file, index=False)

    return _emfac_population, _emfac_class_map

def process_emfac_vmt(_study_area, _scenario_name, _work_dir, _emfac_class_map, config):
    """
    Process EMFAC VMT data by model year, adding proportional calculations.

    Args:
        _study_area:
        _scenario_name:
        config: Configuration dictionary containing filtering and file path information
        _work_dir:

    Returns:
        pandas.DataFrame: Processed and grouped VMT data with proportion calculations
    """
    _emfac_vmt_output_file = os.path.join(
        _work_dir,
        f"emissions/{_study_area}_emfac_vmt_{_scenario_name}.csv"
    )
    if os.path.exists(_emfac_vmt_output_file):
        _emfac_vmt = pd.read_csv(_emfac_vmt_output_file)
    else:
        include_nan = config["filters"]["include_nan"]
        calendar_year = config["filters"]["calendar_year"]
        air_basin_area = config["filters"]["sub_area"]
        _emfac_vmt_by_model_year_file = os.path.join(
            _work_dir,
            config["emfac"]["emfac_vmt_by_model_year_file"]
        )

        table = csv.read_csv(_emfac_vmt_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        df = table.to_pandas()

        # Filter by calendar year
        if 'calendar_year' in df.columns:
            df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]

        # Filter by sub area
        if 'sub_area' in df.columns:
            # Create a filter condition for partial matches
            sub_area_filter = include_nan & df['sub_area'].isna()

            for _area in air_basin_area:
                # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({_area}\\)', regex=True) | (
                        df['sub_area'] == _area)

            # Apply the filter
            df = df[sub_area_filter]

        # Convert numeric columns to float for calculations
        numeric_columns = ['total_vmt', 'cvmt', 'evmt']
        for col in numeric_columns:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors='coerce')

        # Categorize model years
        df['model_year_group'] = df['model_year'].apply(categorize_model_year)

        # Clean data
        df = df.fillna('')
        df = df.reset_index(drop=True)

        # Group by relevant columns and sum VMT
        group_col = ['vehicle_class', 'fuel', 'model_year_group']
        df_grouped = df.groupby(group_col)['total_vmt'].sum().reset_index()

        # Calculate total VMT across all groups
        total_vmt = df_grouped['total_vmt'].sum()

        # Calculate proportion of each group relative to total
        df_grouped['vmt_proportion'] = df_grouped['total_vmt'] / total_vmt

        # Create ID column for reference
        df_grouped['emfacId'] = df_grouped.apply(
            lambda row: sanitize_name(f"{row['model_year_group']}-{row['vehicle_class']}-{row['fuel']}"),
            axis=1
        )

        _emfac_vmt = df_grouped

        _emfac_vmt["beamClass"] = _emfac_vmt["vehicle_class"].map(_emfac_class_map)

        _emfac_vmt = _emfac_vmt.dropna(subset=["beamClass"])

        _emfac_vmt.to_csv(_emfac_vmt_output_file, index=False)

    return _emfac_vmt


def calculate_tour_distances(df):
    """Calculate total distance for each tour ID from the coordinates using vectorized operations"""
    # Sort data by tourId and sequenceRank
    df = df.sort_values(by=['tourId', 'sequenceRank'])

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

    return tour_distances


def updated_fuel_types_from_emfac(og_vehicletypes_df):
    vehtypes = og_vehicletypes_df.copy()
    # Convert primaryFuelType to lowercase directly
    vehtypes['primaryFuelType_lower'] = vehtypes['primaryFuelType'].str.lower()

    # Create conditions and values for mapping
    # For now we assume H2FC will behave like BEV vehicles
    conditions = [
        (vehtypes['primaryFuelType_lower'] == "hydrogen"),
        (vehtypes['primaryFuelType_lower'] == "electricity") & vehtypes['secondaryFuelType'].isna(),
        (vehtypes['primaryFuelType_lower'] == "electricity") & vehtypes['secondaryFuelType'].notna(),
        (vehtypes['primaryFuelType_lower'] == "gasoline"),
        (vehtypes['primaryFuelType_lower'] == "diesel"),
        (vehtypes['primaryFuelType_lower'] == "biodiesel"),
        (vehtypes['primaryFuelType_lower'] == "naturalgas")
    ]

    values = ['Elec', 'Elec', 'Phe', 'Gas', 'Dsl', 'Dsl', 'NG']

    # Use numpy.select to handle multiple conditions
    vehtypes['emfacFuel'] = np.select(
        conditions,
        values,
        default=vehtypes['primaryFuelType']
    )
    vehtypes['beamClass'] = vehtypes['vehicleCategory']
    vehtypes.drop('primaryFuelType_lower', axis=1, inplace=True)

    return vehtypes


def get_emfac_beam_vehicle_class_mapping(_study_area, _scenario_name, _work_dir, vehicle_list):
    """
    Creates vehicle class mapping and saves it to a JSON file if it doesn't exist.
    If the file exists, loads and returns the existing mapping.

    Args:
        _study_area: Stud Area
        _scenario_name: Scenario Name
        _work_dir:
        vehicle_list: List of vehicle types to map

    Returns:
        dict: The vehicle class mapping (either newly created or loaded from existing file)
    """
    import json
    from collections import defaultdict
    _vehicle_class_output_file = os.path.join(
        _work_dir,
        f"emissions/{_study_area}_vehicle_class_mapping_{_scenario_name}.json"
    )
    # Check if the file already exists
    if os.path.exists(_vehicle_class_output_file):
        print(f"File {_vehicle_class_output_file} already exists. Loading existing mapping.")
        with open(_vehicle_class_output_file, 'r') as f:
            return json.load(f)

    # Create the mapping
    mapping = {}

    for vehicle in vehicle_list:
        if 'Utility' in vehicle or 'Public' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'Port' in vehicle or 'POLA' in vehicle or 'POAK' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'SWCV' in vehicle or 'PTO' in vehicle or 'T6TS' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif vehicle in ['LDA', 'LDT1', 'LDT2', 'MDV']:
            mapping[vehicle] = beam_class_car
        elif vehicle in ['MCY']:
            mapping[vehicle] = beam_class_bike
        elif vehicle in ['UBUS']:
            mapping[vehicle] = beam_class_mdp
        elif 'LHD' in vehicle:
            mapping[vehicle] = beam_class_2b3
        elif 'Class 4' in vehicle or 'Class 5' in vehicle or 'Class 6' in vehicle:
            mapping[vehicle] = beam_class_46
        elif 'Class 7' in vehicle or 'Class 8' in vehicle:
            if 'Tractor' in vehicle or 'CAIRP' in vehicle:
                mapping[vehicle] = beam_class_78_t
            else:
                mapping[vehicle] = beam_class_78_v
        elif "T7IS" in vehicle:
            mapping[vehicle] = beam_class_78_t
        else:
            mapping[vehicle] = "NotMatched"

    # Print category groupings
    class_groups = defaultdict(list)
    for vehicle, vehicle_class in mapping.items():
        class_groups[vehicle_class].append(vehicle)
    for vehicle_class, vehicles in class_groups.items():
        print(f"Category: {vehicle_class}")
        for vehicle in vehicles:
            print(f"  - {vehicle}")

    # Create final mapping structure
    ft_emfac_class_map = {emfac: beam for emfac, beam in mapping.items() if
                          beam in [beam_class_46, beam_class_78_v, beam_class_78_t]}
    pax_emfac_class_map = {emfac: beam for emfac, beam in mapping.items() if
                           beam in [beam_class_car, beam_class_bike, beam_class_mdp]}

    _emfac_class_map = ft_emfac_class_map | pax_emfac_class_map

    # Write to JSON file
    with open(_vehicle_class_output_file, 'w') as f:
        json.dump(_emfac_class_map, f, indent=2)

    print(f"Successfully created {_vehicle_class_output_file}")
    return _emfac_class_map


def print_stats(emfac_df, mapped_beaf_freight_df):
    total_beam_vmt = mapped_beaf_freight_df['total_vmt'].sum()

    # Print final VMT distribution
    print("\nFinal VMT distribution:")
    for cls in beam_freight_classes:
        cls_vmt = mapped_beaf_freight_df[mapped_beaf_freight_df['assigned_class'] == cls]['total_vmt'].sum()
        print(f"{cls}: {cls_vmt:.2f} ({cls_vmt / total_beam_vmt * 100:.2f}%)")

    # NEW: Compare resulting distributions with EMFAC
    print("\n=== Distribution Comparison Analysis ===")

    # Create a merged dataframe with EMFAC details
    result_with_emfac = pd.merge(
        mapped_beaf_freight_df,
        emfac_df[['emfacId', 'model_year_group', 'beamClass']],
        on='emfacId',
        how='left',
        suffixes=('_beam', '_emfac')
    )

    # 1. Compare beamClass VMT distribution
    print("\nVMT Distribution by Vehicle Class:")
    print("-" * 60)

    # Calculate EMFAC distribution
    emfac_class_dist = emfac_df.groupby('beamClass')['total_vmt'].sum()
    emfac_class_dist = emfac_class_dist / emfac_class_dist.sum()

    # Calculate result distribution using assigned_class
    result_class_dist = mapped_beaf_freight_df.groupby('assigned_class')['total_vmt'].sum()
    result_class_dist = result_class_dist / result_class_dist.sum()

    # Create comparison dataframe
    class_comparison = pd.DataFrame({
        'EMFAC_Distribution': emfac_class_dist,
        'Result_Distribution': result_class_dist,
    })
    class_comparison['Difference'] = class_comparison['Result_Distribution'] - class_comparison['EMFAC_Distribution']
    class_comparison['Difference_Percent'] = (class_comparison['Difference'] / class_comparison[
        'EMFAC_Distribution']) * 100

    # Print comparison
    print(class_comparison.round(4))
    print(f"Average absolute difference: {abs(class_comparison['Difference']).mean():.4f}")
    print(f"Max absolute difference: {abs(class_comparison['Difference']).max():.4f}")

    # 2. Compare model_year_group VMT distribution
    print("\nVMT Distribution by Model Year Group:")
    print("-" * 60)

    # Calculate EMFAC distribution
    emfac_my_dist = emfac_df.groupby('model_year_group')['total_vmt'].sum()
    emfac_my_dist = emfac_my_dist / emfac_my_dist.sum()

    # Calculate result distribution using merged data
    result_my_dist = result_with_emfac.groupby('model_year_group')['total_vmt'].sum()
    result_my_dist = result_my_dist / result_my_dist.sum()

    # Create comparison dataframe
    my_comparison = pd.DataFrame({
        'EMFAC_Distribution': emfac_my_dist,
        'Result_Distribution': result_my_dist,
    })
    my_comparison['Difference'] = my_comparison['Result_Distribution'] - my_comparison['EMFAC_Distribution']
    my_comparison['Difference_Percent'] = (my_comparison['Difference'] / my_comparison['EMFAC_Distribution']) * 100

    # Print comparison
    print(my_comparison.round(4))
    print(f"Average absolute difference: {abs(my_comparison['Difference']).mean():.4f}")
    print(f"Max absolute difference: {abs(my_comparison['Difference']).max():.4f}")

    # 3. Compare combined beamClass and model_year_group distribution
    print("\nVMT Distribution by Vehicle Class and Model Year Group:")
    print("-" * 60)

    # Calculate EMFAC distribution
    emfac_cls_my_dist = emfac_df.groupby(['beamClass', 'model_year_group'])['total_vmt'].sum()
    emfac_cls_my_dist = emfac_cls_my_dist / emfac_cls_my_dist.sum()

    # Calculate result distribution
    result_cls_my_dist = result_with_emfac.groupby(['beamClass_emfac', 'model_year_group'])['total_vmt'].sum()
    result_cls_my_dist = result_cls_my_dist / result_cls_my_dist.sum()

    # Create comparison dataframe for top 10 combinations
    cls_my_comparison = pd.DataFrame({
        'EMFAC_Distribution': emfac_cls_my_dist,
        'Result_Distribution': result_cls_my_dist
    }).reset_index()

    # Calculate differences
    cls_my_comparison['Difference'] = cls_my_comparison['Result_Distribution'] - cls_my_comparison['EMFAC_Distribution']
    cls_my_comparison['Difference_Percent'] = (cls_my_comparison['Difference'] / cls_my_comparison[
        'EMFAC_Distribution']) * 100

    # Print summary statistics for combined distribution
    print(f"Number of vehicle class/model year combinations: {len(cls_my_comparison)}")
    print(f"Average absolute difference: {abs(cls_my_comparison['Difference']).mean():.4f}")
    print(f"Max absolute difference: {abs(cls_my_comparison['Difference']).max():.4f}")
    print(f"Top 5 combinations with largest differences:")
    print(cls_my_comparison.sort_values(by='Difference', key=abs, ascending=False).head(5).round(4))


def map_emfac_to_beam_freight(_scenario, _work_dir, _emfac_data, _beam_data, _config):
    """
    Maps EMFAC vehicle classes to BEAM freight data preserving the distribution of:
    1. Model year
    2. Vehicle class
    3. Fuel type

    The function assigns appropriate emfacId to each BEAM freight vehicle while maintaining
    the statistical integrity of VMT distributions across vehicle classes and model years.
    After mapping, it compares the resulting distributions to verify preservation quality.

    This version ensures that each emfacId is unique by removing it from the pool after use
    and recalculating VMT proportions.

    Parameters:
    -----------
    scenario_name : str
        Scenario name
    work_dir : str
        Working directory
    emfac_data : pd.DataFrame
        EMFAC VMT distribution data
    beam_data : pd.DataFrame
        BEAM freight VMT data
    config : dict
        Configuration dictionary

    Returns:
    --------
    pd.DataFrame
        The BEAM data with emfacId assigned to each row
    """
    # Check if output file already exists
    _carriers_dir = f"{_work_dir}/{os.path.dirname(_config['beam']['carriers_file'])}"
    output_file = os.path.join(_carriers_dir, f"emfac-fleet--{_scenario.replace('_', '-')}.csv")

    if os.path.exists(output_file):
        print(f"Using existing mapping file: {output_file}")
        return pd.read_csv(output_file)

    print("=== VMT-based Mapping Of BEAM Freight with EMFAC ===")
    # Filter EMFAC data to freight classes
    emfac_freight_data = _emfac_data[_emfac_data["beamClass"].isin(beam_freight_classes)]
    beam_df = _beam_data.copy()
    emfac_df = emfac_freight_data.copy()

    # Print distributions for verification
    print(f"Loaded BEAM file with {len(beam_df)} rows and EMFAC file with {len(emfac_df)} rows.")

    # Step 1: Calculate total BEAM data VMT
    total_beam_vmt = beam_df['total_vmt'].sum()
    print(f"Total BEAM VMT: {total_beam_vmt}")

    # Step 2: Extract VMT proportion in EMFAC data
    emfac_vmt_props = emfac_df.groupby(['beamClass', 'model_year_group', 'fuel'])['total_vmt'].sum().reset_index()
    emfac_vmt_props['vmt_proportion'] = emfac_vmt_props['total_vmt'] / emfac_vmt_props['total_vmt'].sum()

    # Step 3: Convert EMFAC data to a nested dictionary for easier manipulation and removal
    emfac_entries = {}
    for cls in beam_freight_classes:
        class_data = emfac_df[emfac_df['beamClass'] == cls].copy()
        if not class_data.empty:
            # Group by fuel and model year
            emfac_entries[cls] = {}
            for (fuel, model_year), group in class_data.groupby(['fuel', 'model_year_group']):
                emfac_entries[cls][(fuel, model_year)] = group.copy()

    # Initialize result dataframe
    result_df = beam_df.copy()
    result_df['emfacId'] = None
    result_df['assigned_class'] = result_df['beamClass']  # Track original vs assigned class

    # Calculate target VMT for each class based on EMFAC proportions
    assigned_vmt = {cls: 0 for cls in beam_freight_classes}
    target_vmt = {cls: 0 for cls in beam_freight_classes}

    for cls in beam_freight_classes:
        class_vmt = emfac_df[emfac_df['beamClass'] == cls]['total_vmt'].sum()
        target_vmt[cls] = (class_vmt / emfac_df['total_vmt'].sum()) * total_beam_vmt

    # Print target VMT distribution
    print("Target VMT distribution from EMFAC:")
    for cls, vmt in target_vmt.items():
        print(f"{cls}: {vmt:.2f} ({vmt / total_beam_vmt * 100:.2f}%)")

    # Sort BEAM data by class for processing
    beam_by_class = {cls: beam_df[beam_df['beamClass'] == cls].copy() for cls in beam_freight_classes}
    for cls, df in beam_by_class.items():
        print(f"BEAM {cls} count: {len(df)}, VMT: {df['total_vmt'].sum()}")

    # Keep track of unassigned vehicles
    unassigned_indices = set(beam_df.index)

    # Process each class in order
    for i, current_class in enumerate(beam_freight_classes):
        print(f"\nProcessing {current_class}...")

        # Skip if no EMFAC data for this class
        if current_class not in emfac_entries or not emfac_entries[current_class]:
            print(f"No EMFAC data for {current_class}, skipping...")
            continue

        # Process unassigned vehicles in current class
        current_beam = beam_by_class[current_class]
        beam_indices = set(current_beam.index)
        unassigned_in_class = beam_indices.intersection(unassigned_indices)

        # STEP 4.1: Assign vehicles from current class until threshold is met
        while (assigned_vmt[current_class] < target_vmt[current_class]) and unassigned_in_class:
            # Sample a vehicle
            idx = random.choice(list(unassigned_in_class))
            vehicle = beam_df.loc[idx]

            # Check if we have entries for this fuel
            fuel = vehicle['emfacFuel']

            # Get all keys (fuel, model_year) for this class with matching fuel
            matching_keys = [k for k in emfac_entries[current_class].keys() if k[0] == fuel]

            if matching_keys:
                # Calculate total VMT for this fuel type across all model years
                total_vmt = sum(group['total_vmt'].sum() for k, group in
                                [(k, emfac_entries[current_class][k]) for k in matching_keys])

                if total_vmt > 0:  # Make sure we have valid VMT entries
                    # Sample a key based on VMT proportion
                    key_probs = [emfac_entries[current_class][k]['total_vmt'].sum() / total_vmt
                                 for k in matching_keys]

                    sampled_key = matching_keys[np.random.choice(len(matching_keys), p=key_probs)]

                    # Get the group of potential entries
                    group = emfac_entries[current_class][sampled_key]

                    # Sample an entry based on VMT proportion
                    if not group.empty:
                        vmt_props = group['total_vmt'] / group['total_vmt'].sum()
                        sampled_idx = np.random.choice(group.index, p=vmt_props)
                        sampled_emfac = group.loc[sampled_idx]

                        # Assign emfacId
                        result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                        result_df.loc[idx, 'assigned_class'] = current_class

                        # Remove used entry from the pool
                        emfac_entries[current_class][sampled_key] = group.drop(sampled_idx)

                        # If group is now empty, remove the key
                        if emfac_entries[current_class][sampled_key].empty:
                            del emfac_entries[current_class][sampled_key]

                        # Update tracking
                        assigned_vmt[current_class] += vehicle['total_vmt']
                        unassigned_indices.remove(idx)
                        unassigned_in_class.remove(idx)
                    else:
                        # Empty group - remove key
                        del emfac_entries[current_class][sampled_key]
                        continue
                else:
                    # No valid VMT entries
                    for k in matching_keys:
                        if emfac_entries[current_class][k].empty:
                            del emfac_entries[current_class][k]
                    print(f"No valid VMT entries for fuel {fuel} in class {current_class}")
                    unassigned_in_class.remove(idx)
                    continue
            else:
                # No matching fuel for this class, remove from consideration
                print(f"No matching fuel {fuel} in EMFAC for vehicle {vehicle['vehicleId']}")
                unassigned_in_class.remove(idx)
                continue

        # STEP 4.2: Current class exhausted but threshold not met
        if (assigned_vmt[current_class] < target_vmt[current_class]) and not unassigned_in_class:
            print(f"{current_class} exhausted but threshold not met. " +
                  f"Current: {assigned_vmt[current_class]:.2f}, Target: {target_vmt[current_class]:.2f}")

            # Check if there are more classes to process
            if i + 1 < len(beam_freight_classes):
                next_class = beam_freight_classes[i + 1]

                # Skip if next class has no EMFAC data
                if next_class not in emfac_entries or not emfac_entries[next_class]:
                    print(f"No EMFAC data for next class {next_class}, continuing...")
                    continue

                print(f"Flipping vehicles from {next_class} to {current_class}")

                # Get unassigned vehicles from next class
                next_beam = beam_by_class[next_class]
                next_indices = set(next_beam.index)
                unassigned_next = next_indices.intersection(unassigned_indices)

                # Process vehicles from next class
                while (assigned_vmt[current_class] < target_vmt[current_class]) and unassigned_next:
                    # Sample a vehicle from next class
                    idx = random.choice(list(unassigned_next))
                    vehicle = beam_df.loc[idx]

                    # Find matching entries from current class
                    fuel = vehicle['emfacFuel']
                    matching_keys = [k for k in emfac_entries[current_class].keys() if k[0] == fuel]

                    if matching_keys:
                        # Calculate total VMT
                        total_vmt = sum(group['total_vmt'].sum() for k, group in
                                        [(k, emfac_entries[current_class][k]) for k in matching_keys])

                        if total_vmt > 0:
                            # Sample key based on VMT proportion
                            key_probs = [emfac_entries[current_class][k]['total_vmt'].sum() / total_vmt
                                         for k in matching_keys]

                            sampled_key = matching_keys[np.random.choice(len(matching_keys), p=key_probs)]
                            group = emfac_entries[current_class][sampled_key]

                            # Sample an entry
                            if not group.empty:
                                vmt_props = group['total_vmt'] / group['total_vmt'].sum()
                                sampled_idx = np.random.choice(group.index, p=vmt_props)
                                sampled_emfac = group.loc[sampled_idx]

                                # Assign and flip class
                                result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                                result_df.loc[idx, 'assigned_class'] = current_class

                                # Remove used entry
                                emfac_entries[current_class][sampled_key] = group.drop(sampled_idx)

                                # Clean up empty groups
                                if emfac_entries[current_class][sampled_key].empty:
                                    del emfac_entries[current_class][sampled_key]

                                # Update tracking
                                assigned_vmt[current_class] += vehicle['total_vmt']
                                unassigned_indices.remove(idx)
                                unassigned_next.remove(idx)
                            else:
                                # Empty group
                                del emfac_entries[current_class][sampled_key]
                                continue
                        else:
                            # No valid VMT entries
                            for k in matching_keys:
                                if emfac_entries[current_class][k].empty:
                                    del emfac_entries[current_class][k]
                            unassigned_next.remove(idx)
                            continue
                    else:
                        # No matching fuel
                        print(f"When flipping from {next_class} to {current_class}: " +
                              f"No matching fuel in EMFAC for vehicle {vehicle['vehicleId']} with fuel {vehicle['emfacFuel']}")
                        unassigned_next.remove(idx)

        # STEP 4.3: Threshold met before exhausting current class
        elif (assigned_vmt[current_class] >= target_vmt[current_class]) and unassigned_in_class:
            print(f"{current_class} threshold met before exhausting class. " +
                  f"Current: {assigned_vmt[current_class]:.2f}, Target: {target_vmt[current_class]:.2f}")

            # Check if there are more classes to process
            if i + 1 < len(beam_freight_classes):
                next_class = beam_freight_classes[i + 1]

                # Skip if next class has no EMFAC data
                if next_class not in emfac_entries or not emfac_entries[next_class]:
                    print(f"No EMFAC data for next class {next_class}, continuing with unassigned vehicles...")
                    continue

                print(f"Flipping remaining {current_class} vehicles to {next_class}")

                # Process remaining vehicles in current class
                for idx in list(unassigned_in_class):
                    vehicle = beam_df.loc[idx]
                    fuel = vehicle['emfacFuel']

                    # Find matching entries in next class
                    matching_keys = [k for k in emfac_entries[next_class].keys() if k[0] == fuel]

                    if matching_keys:
                        # Calculate total VMT
                        total_vmt = sum(group['total_vmt'].sum() for k, group in
                                        [(k, emfac_entries[next_class][k]) for k in matching_keys])

                        if total_vmt > 0:
                            # Sample key based on VMT proportion
                            key_probs = [emfac_entries[next_class][k]['total_vmt'].sum() / total_vmt
                                         for k in matching_keys]

                            sampled_key = matching_keys[np.random.choice(len(matching_keys), p=key_probs)]
                            group = emfac_entries[next_class][sampled_key]

                            # Sample an entry
                            if not group.empty:
                                vmt_props = group['total_vmt'] / group['total_vmt'].sum()
                                sampled_idx = np.random.choice(group.index, p=vmt_props)
                                sampled_emfac = group.loc[sampled_idx]

                                # Assign and flip class
                                result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                                result_df.loc[idx, 'assigned_class'] = next_class

                                # Remove used entry
                                emfac_entries[next_class][sampled_key] = group.drop(sampled_idx)

                                # Clean up empty groups
                                if emfac_entries[next_class][sampled_key].empty:
                                    del emfac_entries[next_class][sampled_key]

                                # Update tracking
                                assigned_vmt[next_class] += vehicle['total_vmt']
                                unassigned_indices.remove(idx)
                                unassigned_in_class.remove(idx)
                            else:
                                # Empty group
                                del emfac_entries[next_class][sampled_key]
                                continue
                        else:
                            # No valid VMT entries
                            for k in matching_keys:
                                if emfac_entries[next_class][k].empty:
                                    del emfac_entries[next_class][k]
                            unassigned_in_class.remove(idx)
                            continue
                    else:
                        # No matching fuel
                        print(f"When flipping from {current_class} to {next_class}: " +
                              f"No matching fuel in EMFAC for vehicle {vehicle['vehicleId']} with fuel {vehicle['emfacFuel']}")
                        unassigned_in_class.remove(idx)

    # Handle any remaining unassigned vehicles
    if unassigned_indices:
        print(f"\nHandling {len(unassigned_indices)} remaining unassigned vehicles...")

        # Create a pool of all remaining EMFAC entries
        all_remaining_entries = []
        for cls in emfac_entries:
            for key in emfac_entries[cls]:
                all_remaining_entries.append(emfac_entries[cls][key])

        all_remaining_df = pd.concat(all_remaining_entries) if all_remaining_entries else pd.DataFrame()

        for idx in list(unassigned_indices):
            vehicle = beam_df.loc[idx]

            if not all_remaining_df.empty:
                # Try to match by fuel first
                matching_by_fuel = all_remaining_df[all_remaining_df['fuel'] == vehicle['emfacFuel']]

                if not matching_by_fuel.empty:
                    # Sample based on VMT proportion
                    vmt_props = matching_by_fuel['total_vmt'] / matching_by_fuel['total_vmt'].sum()
                    sampled_idx = np.random.choice(matching_by_fuel.index, p=vmt_props)
                    sampled_emfac = all_remaining_df.loc[sampled_idx]
                else:
                    # If no fuel match, sample any remaining entry
                    vmt_props = all_remaining_df['total_vmt'] / all_remaining_df['total_vmt'].sum()
                    sampled_idx = np.random.choice(all_remaining_df.index, p=vmt_props)
                    sampled_emfac = all_remaining_df.loc[sampled_idx]

                # Assign the emfacId
                result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                result_df.loc[idx, 'assigned_class'] = sampled_emfac['beamClass']

                # Remove the used entry
                all_remaining_df = all_remaining_df.drop(sampled_idx)

                # Update tracking
                assigned_class = sampled_emfac['beamClass']
                if assigned_class in assigned_vmt:
                    assigned_vmt[assigned_class] += vehicle['total_vmt']
            else:
                # No more EMFAC entries available, create a fallback ID
                # This should be very rare if EMFAC dataset is large enough
                print(f"Warning: No more EMFAC entries available for vehicle {vehicle['vehicleId']}")
                result_df.loc[idx, 'emfacId'] = f"synthetic_emfac_{idx}"
                result_df.loc[idx, 'assigned_class'] = vehicle['beamClass']

            unassigned_indices.remove(idx)

    # Verify all vehicles have been assigned
    unassigned_count = result_df['emfacId'].isna().sum()
    if unassigned_count > 0:
        print(f"Warning: {unassigned_count} vehicles still not assigned an emfacId")
    else:
        print("All freight vehicles successfully assigned an emfacId")

    # Verify emfacId uniqueness
    duplicate_count = result_df['emfacId'].duplicated().sum()
    if duplicate_count > 0:
        print(f"Warning: {duplicate_count} duplicate emfacIds found")
    else:
        print("All emfacIds are unique")

    # Save results
    print(f"\nSaving results to {output_file}")
    # Drop temporary columns before saving
    result_df = result_df.drop(['assigned_class'], axis=1)
    result_df.to_csv(output_file, index=False)

    return result_df


def process_single_vehicle_type(veh_type, emissions_rates, rates_prefix_filepath):
    veh_type_id = veh_type['vehicleTypeId']

    # Filter taz_emissions_rates for the current vehicle type
    veh_emissions = emissions_rates[emissions_rates['emfacId'] == veh_type_id].copy()

    if not veh_emissions.empty:
        # Remove the emfacId column as it's no longer needed
        veh_emissions = veh_emissions.drop('emfacId', axis=1)

        # Generate the file name
        file_path = f"{rates_prefix_filepath}{veh_type_id}.csv"

        print("Writing " + file_path)
        # Save the emissions rates to a CSV file
        veh_emissions.to_csv(file_path, index=False)

        return veh_type_id
    else:
        print(f"Warning: No emissions data found for vehicle type {veh_type_id}")
        return veh_type_id


# Helper function to extract income group from vehicle type ID
def extract_income_group(vehicle_type_id):
    """
    Extract income group from vehicle type ID or return a default.
    Adjust this based on your actual vehicle type ID format.
    """
    # Try to extract income group from the ID
    # This is a placeholder - adjust according to your specific format
    import re
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
    bus_mask = ((df_merged_raw['vehicleCategory'] == beam_class_mdp) &
                (df_merged_raw['vehicleTypeId'].str.lower().str.contains('bus')))

    # Process buses - sample one row per vehicleTypeId based on population
    df_buses = df_merged_raw[bus_mask].copy()
    df_buses_filtered = df_buses.groupby('vehicleTypeId').sample(n=1, weights='population')


    # Process cars and bikes
    car_bike_mask = (df_merged_raw['vehicleCategory'].isin([beam_class_car, beam_class_bike]))
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


def assign_emfac_id_to_vehicle_types(_scenario, _emissions_rates, _emfac_pop, _emfac_vmt, _work_dir, _config):
    """
    Process freight vehicle emissions in three steps:
    1. Build new freight vehicle types
    2. Assign new vehicle types to carriers
    3. Assign emissions rates to vehicle types

    Parameters:
    -----------
    emissions_rates : DataFrame
        DataFrame containing emissions rates
    discrete_freight_population : DataFrame
        DataFrame containing freight fleet information
    config: Dictionary
        Dictionary containing configuration parameters

    Returns:
    --------
    tuple
        (updated_vehicle_types, updated_carrier_df)
    """
    from joblib import Parallel, delayed
    carriers_out_file = os.path.join(_work_dir, f"{_config["beam"]["carriers_file"].replace(".csv", "--TrAP.csv")}")
    ft_vehtypes_out_file = os.path.join(_work_dir,f"{_config["beam"]["ft_vehicle_types_file"].replace(".csv", "--TrAP.csv")}")
    pax_vehtypes_out_file = os.path.join(_work_dir,f"{_config["beam"]["pax_vehicle_types_file"].replace(".csv", "--TrAP.csv")}")
    emissions_rates_dir = os.path.join(
        os.path.dirname(os.path.join(_work_dir, f"{_config["beam"]["ft_vehicle_types_file"]}")),
        f"TrAP/{_scenario.replace("_", "-")}"
    )

    if os.path.exists(carriers_out_file) and os.path.exists(ft_vehtypes_out_file) and os.path.exists(pax_vehtypes_out_file):
        print("All carriers and vehicle types emissions files have already been created")
    else:
        # Create a copy of the original vehicleTypeId and set up a lookup dictionary
        pax_vehicle_types = pd.read_csv(os.path.join(_work_dir, f"{_config["beam"]["pax_vehicle_types_file"]}"), dtype=str)
        car_bike_mask = (pax_vehicle_types['vehicleCategory'].isin([beam_class_car, beam_class_bike]))
        bus_mask = ((pax_vehicle_types['vehicleCategory'] == beam_class_mdp) & (pax_vehicle_types['vehicleTypeId'].str.lower().str.contains('bus')))
        pax_freight_mask = (pax_vehicle_types['vehicleCategory'].isin(beam_freight_classes))
        pax_vehicle_types_filtered = pax_vehicle_types[car_bike_mask | bus_mask]
        pax_vehicle_types_others = pax_vehicle_types[~(car_bike_mask | bus_mask | pax_freight_mask)]

        ft_vehicle_types = pd.read_csv(os.path.join(_work_dir, f"{_config["beam"]["ft_vehicle_types_file"]}"), dtype=str)
        ft_freight_mask = (ft_vehicle_types['vehicleCategory'].isin(beam_freight_classes))
        ft_vehicle_types_filtered = ft_vehicle_types[ft_freight_mask]

        vehicle_types_updated = updated_fuel_types_from_emfac(
            pd.concat([pax_vehicle_types_filtered, ft_vehicle_types_filtered], axis=0)
        )

        ft_vehicle_types_filtered = vehicle_types_updated[vehicle_types_updated["beamClass"].isin(beam_freight_classes)]
        pax_vehicle_types_filtered = vehicle_types_updated[vehicle_types_updated["beamClass"].isin(beam_passenger_classes)]

        # ## Freight ## #
        _carriers_file = str(os.path.join(_work_dir, _config["beam"]["carriers_file"]))
        _payloads_file = str(os.path.join(_work_dir, _config["beam"]["payloads_file"]))
        carriers_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["carriers_file"])))
        payloads_raw = pd.read_csv(str(os.path.join(_work_dir, _config["beam"]["payloads_file"])))
        tour_distances = calculate_tour_distances(payloads_raw)
        carriers = carriers_raw[['tourId', 'vehicleId', 'vehicleTypeId']].copy()
        payloads = payloads_raw[['payloadId', 'tourId', 'payloadType']].copy()
        payloads.loc[:, 'payloadType'] = payloads['payloadType'].astype(str)
        payloads_summary = payloads.groupby(['tourId'])['payloadType'].agg('|'.join).reset_index()
        payloads_merged = pd.merge(payloads_summary, carriers, on='tourId', how='left')
        # Merge payloads with vehicle types
        payloads_vehtypes = pd.merge(
            payloads_merged,
            ft_vehicle_types_filtered[['vehicleTypeId', 'beamClass', 'emfacFuel', 'primaryFuelType', 'secondaryFuelType']],
            on='vehicleTypeId',
            how='left'
        )
        freight_pop = payloads_vehtypes.drop_duplicates('vehicleId', keep='first').copy()
        # Calculate total distance across all tours
        total_distance = sum(tour_distances.values())
        # Calculate proportion for each tour
        tour_proportions = {tour_id: distance / total_distance for tour_id, distance in tour_distances.items()}
        freight_pop['total_vmt'] = freight_pop['tourId'].map(tour_distances)
        freight_pop['vmt_proportion'] = freight_pop['tourId'].map(tour_proportions)
        freight_pop_with_emfac_id = map_emfac_to_beam_freight(_scenario, _work_dir, _emfac_vmt, freight_pop, _config)
        freight_pop_with_emfac_id["oldVehicleTypeId"] = freight_pop_with_emfac_id["vehicleTypeId"]
        freight_pop_with_emfac_id["vehicleTypeId"] = freight_pop_with_emfac_id['emfacId']
        freight_pop_with_emfac_id.drop_duplicates(subset='vehicleTypeId', keep='first')
        # Join the dataframes instead of iterating
        ft_vehtypes_with_emfac_id = freight_pop_with_emfac_id.merge(
            ft_vehicle_types_filtered,
            left_on="oldVehicleTypeId",
            right_on="vehicleTypeId",
            suffixes=('', '_original')
        )
        # Keep only the columns you need and rename as necessary
        ft_vehtypes_with_emfac_id = ft_vehtypes_with_emfac_id.rename(columns={"beamClass": "vehicleCategory"})
        cols_to_drop = [col for col in ft_vehtypes_with_emfac_id.columns if col.endswith('_original')] # Drop any redundant columns
        ft_vehtypes_with_emfac_id = ft_vehtypes_with_emfac_id.drop(columns=cols_to_drop + ["oldVehicleTypeId"])
        vehicle_id_to_type_mapping = dict(
            zip(freight_pop_with_emfac_id['vehicleId'], freight_pop_with_emfac_id['vehicleTypeId'])
        )
        carriers = carriers_raw.copy()
        carriers['vehicleTypeId'] = carriers.apply(lambda row: vehicle_id_to_type_mapping.get(row['vehicleId']), axis=1)
        carriers.dropna(subset=['vehicleTypeId'], inplace=True)
        # Find the dropped rows by filtering the original dataframe
        dropped_rows = carriers_raw[carriers_raw['vehicleTypeId'].isna()]
        if not dropped_rows.empty:
            # Print the dropped rows
            print("Dropped rows:")
            print(dropped_rows)

        print(f"Writing {carriers_out_file}")
        carriers.to_csv(carriers_out_file, index=False)

        # ## Passenger ## #
        _emfac_pop_for_pax = _emfac_pop[_emfac_pop["beamClass"].isin(beam_passenger_classes)]
        pax_vehtypes_with_emfac_id = update_vehicle_probabilities(pax_vehicle_types_filtered, _emfac_pop_for_pax)

        # ## Freight and Passenger ## #
        columns_to_keep = list(pax_vehicle_types.columns) + ["emfacId"]
        # Create separate column lists for each DataFrame
        pax_columns_to_keep = ['vehicleTypeId'] + [col for col in columns_to_keep if
                                                   col in pax_vehtypes_with_emfac_id.columns and col != 'vehicleTypeId']
        ft_columns_to_keep = ['vehicleTypeId'] + [col for col in columns_to_keep if
                                                  col in ft_vehtypes_with_emfac_id.columns and col != 'vehicleTypeId']

        # Check for duplicates before concatenation
        print("Pax duplicates:", pax_vehtypes_with_emfac_id['vehicleTypeId'].duplicated().any())
        print("Freight duplicates:", ft_vehtypes_with_emfac_id['vehicleTypeId'].duplicated().any())

        # Concatenate with appropriate columns for each DataFrame
        vehtypes_with_emfac_id = pd.concat([
            pax_vehtypes_with_emfac_id[pax_columns_to_keep].reset_index(drop=True),
            ft_vehtypes_with_emfac_id[ft_columns_to_keep].reset_index(drop=True)
        ], axis=0, sort=False)

        # ## Emissions Rates ## #
        # Prepare the directory for writing emissions rates files
        try:
            # Remove directory if it exists
            if os.path.exists(emissions_rates_dir):
                shutil.rmtree(emissions_rates_dir)
            # Create directory
            os.makedirs(emissions_rates_dir, exist_ok=True)
            print(f"Ready to write new data to the directory {emissions_rates_dir}")
        except Exception as e:
            print(f"Failed to prepare directory {emissions_rates_dir}: {e}")

        # Use parallel processing with error handling and chunking
        chunk_size = 100  # Adjust this value based on your data size and available memory
        results = []
        for i in range(0, len(vehtypes_with_emfac_id), chunk_size):
            chunk = vehtypes_with_emfac_id.iloc[i:i + chunk_size]
            chunk_results = Parallel(n_jobs=-1, timeout=600)(  # 10-minute timeout
                delayed(process_single_vehicle_type)(
                    veh_type,
                    _emissions_rates,
                    f"{emissions_rates_dir}/"
                ) for _, veh_type in chunk.iterrows()
            )
            results.extend(chunk_results)
            # Clear some memory
            del chunk_results

        # Update the vehicle_types DataFrame with the new emissionsRatesFile information
        # Split the path into components
        path_parts = emissions_rates_dir.split('/')
        # Find the index of "TrAP" in the parts
        trap_index = path_parts.index("TrAP")
        # Join the parts from "TrAP" onwards
        shortened_path = '/'.join(path_parts[trap_index:])
        for veh_type_id in results:
            if veh_type_id:
                relative_rates_filepath = f"{shortened_path}/{veh_type_id}.csv"
                vehtypes_with_emfac_id.loc[
                    vehtypes_with_emfac_id['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'] = relative_rates_filepath

        # Save updated vehicle types
        print(f"Writing:\n{ft_vehtypes_out_file}\n{pax_vehtypes_out_file}")
        ft_freight_mask = (vehtypes_with_emfac_id['vehicleCategory'].isin(beam_freight_classes))
        _updated_ft_vehicle_types = vehtypes_with_emfac_id[ft_freight_mask]
        _updated_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)

        _updated_pax_vehicle_types_others = pax_vehicle_types_others.copy()
        _updated_pax_vehicle_types_others['emissionsRatesFile'] = ""
        _updated_pax_vehicle_types = pd.concat(
            [vehtypes_with_emfac_id[~ft_freight_mask], pax_vehicle_types_others],
            axis=0
        )
        _updated_pax_vehicle_types.to_csv(pax_vehtypes_out_file, index=False)


def run():
    # Configuration parameters
    area = "sfbay"
    run_batch = "2024-11-06"
    run_batch_label = run_batch.replace("-", "")
    scenario = "2018_Baseline"
    scenario_label = scenario.replace("_", "-")

    print(f"\n{'='*50}")
    print(f"  EMISSIONS PROCESSING - {area.upper()} REGION")
    print(f"  Run Batch: {run_batch}")
    print(f"  Scenario: {scenario}")
    print(f"{'='*50}\n")

    study_area_config = get_area_config(area)
    config = study_area_config["emissions"][scenario]
    beam_config = config["beam"]
    beam_config["carriers_file"] = f"beam-ft/{run_batch}/{scenario}/carriers--{scenario_label}.csv"
    beam_config["payloads_file"] = f"beam-ft/{run_batch}/{scenario}/payloads--{scenario_label}.csv"
    beam_config["ft_vehicle_types_file"] = f"vehicle-tech/ft-vehicletypes--{run_batch_label}--{scenario_label}.csv"
    beam_config["pax_vehicle_types_file"] = f"vehicle-tech/pax-vehicletypes--{scenario_label}.csv"

    # ### Output directories and files ### #
    #
    emfac_pop, emfac_class_map = process_emfac_population(area, scenario, study_area_config["work_dir"], config)
    print("\n=== EMFAC Population ===\n")
    print(f"total_population: {emfac_pop["population"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== EMFAC VMT ===\n")
    emfac_vmt = process_emfac_vmt(area, scenario, study_area_config["work_dir"], emfac_class_map, config)
    print(f"total_vmt: {emfac_vmt["total_vmt"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== CARB Emissions Rates ===\n")
    rates = process_emissions_rates(area, scenario, study_area_config["work_dir"], emfac_class_map, config)
    print(f"rates: {len(rates):,}")

    print("\n=== Map EMFAC To BEAM Population ===\n")
    assign_emfac_id_to_vehicle_types(scenario, rates, emfac_pop, emfac_vmt, study_area_config["work_dir"], config)

if __name__ == "__main__":
    run()
    # # Load common data
    # emfac_population = pd.read_csv(emfac_population_file, low_memory=False, dtype=str)
    # emfac_population['population'] = pd.to_numeric(emfac_population['population'], errors='coerce')
    #
    # emissions_rates = pd.read_csv(emfac_emissions_file, low_memory=False, dtype={
    #     'calendar_year': int,
    #     'season_month': str,
    #     'sub_area': str,
    #     'vehicle_class': str,
    #     'fuel': str,
    #     'temperature': float,
    #     'relative_humidity': float,
    #     'process': str,
    #     'speed_time': float,
    #     'pollutant': str,
    #     'emission_rate': float
    # })
    #
    # # Filter rates for the specific area and year
    # filtered_rates = emissions_rates[
    #     emissions_rates["sub_area"].str.contains(fr"\({re.escape(region_to_carb_area[area])}\)", case=False, na=False) &
    #     (emissions_rates["calendar_year"] == emfac_year)
    #     ]
    #
    # # Process passenger mapping if enabled
    # if run_config["run_pax"]:
    #     # Print scenario info
    #     print(f"Scenario {area}, {str(ft_year)}-{ft_scenario} from {ft_iteration}..")
    #     pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
    #     process_passenger_mapping(
    #         emfac_year, filtered_rates, emfac_population,
    #         pax_vehicle_types,
    #         pax_filtered_out_emissions_file, pax_vehicle_types_emissions_file,
    #         pax_emissions_rates_relative_filepath, input_dir
    #     )
    #
    # combine_csv_files(
    # [
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2018.csv'),
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2025.csv'),
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2030.csv'),
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2040.csv'),
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2050.csv')
    # ],
    #     os.path.expanduser('~/Workspace/Simulation/sfbay/emissions/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv')
    # )

    print("End")