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
            area, scenario, work_dir, _emfac_population["vehicle_class"].unique()
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
        group_col = ['sub_area', 'vehicle_class', 'fuel', 'model_year_group']
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
            area, scenario, work_dir, _emfac_population["vehicle_class"].unique()
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
        group_col = ['sub_area', 'vehicle_class', 'fuel', 'model_year_group']
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
    """Calculate total distance for each tour ID from the coordinates"""
    # Sort data by tourId and sequenceRank
    df = df.sort_values(by=['tourId', 'sequenceRank'])

    # Get unique tour IDs
    tour_ids = df['tourId'].unique()

    # Initialize results dictionary
    tour_distances = {}

    # Calculate total distance for each tour
    for tour_id in tour_ids:
        # Get points for this tour
        tour_points = df[df['tourId'] == tour_id]

        # Initialize total distance
        total_distance = 0

        # Calculate distance between consecutive points
        for i in range(len(tour_points) - 1):
            current_point = tour_points.iloc[i]
            next_point = tour_points.iloc[i + 1]

            distance = calculate_distance(
                current_point['locationX'],
                current_point['locationY'],
                next_point['locationX'],
                next_point['locationY']
            )

            total_distance += distance

        # Store the total distance for this tour
        tour_distances[tour_id] = total_distance

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

    values = ['Elec', 'Elec', 'Phe', 'Gas', 'Dsl', 'BioDsl', 'NG']

    # Use numpy.select to handle multiple conditions
    vehtypes['emfacFuel'] = np.select(
        conditions,
        values,
        default=vehtypes['primaryFuelType']
    )
    vehtypes['beamClass'] = vehtypes['vehicleCategory']

    return vehtypes.drop('primaryFuelType_lower', axis=1, inplace=True)


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


def print_emfac_distributions(emfac_data):
    """
    Calculate and print VMT distributions from EMFAC data by:
    1. Model year
    2. Vehicle class
    3. Fuel type
    4. BEAM class

    Parameters:
    -----------
    emfac_data : pandas DataFrame
        EMFAC data with model_year_group, vehicle_class, fuel, beamClass, and vmt_proportion columns
    """
    # Calculate total VMT proportion for normalization
    total_vmt_proportion = emfac_data['vmt_proportion'].sum()

    # 1. Print model year distribution
    print("\n=== VMT Distribution by Model Year ===")
    print(f"{'Year':<10} {'VMT %':<10}")
    print("-" * 20)

    for year, group in emfac_data.groupby('model_year_group'):
        year_vmt = group['vmt_proportion'].sum()
        percentage = (year_vmt / total_vmt_proportion) * 100
        print(f"{year:<10} {percentage:<10.2f}%")

    # 2. Print vehicle class distribution
    print("\n=== VMT Distribution by Vehicle Class ===")
    print(f"{'Vehicle Class':<20} {'VMT %':<10}")
    print("-" * 30)

    for vehicle_class, group in emfac_data.groupby('vehicle_class'):
        vclass_vmt = group['vmt_proportion'].sum()
        percentage = (vclass_vmt / total_vmt_proportion) * 100
        print(f"{vehicle_class:<20} {percentage:<10.2f}%")

    # 3. Print fuel distribution
    print("\n=== VMT Distribution by Fuel Type ===")
    print(f"{'Fuel':<10} {'VMT %':<10}")
    print("-" * 20)

    for fuel, group in emfac_data.groupby('fuel'):
        fuel_vmt = group['vmt_proportion'].sum()
        percentage = (fuel_vmt / total_vmt_proportion) * 100
        print(f"{fuel:<10} {percentage:<10.2f}%")

    # 4. Print BEAM class distribution
    print("\n=== VMT Distribution by BEAM Class ===")
    print(f"{'BEAM Class':<25} {'VMT %':<10}")
    print("-" * 35)

    for beam_class, group in emfac_data.groupby('beamClass'):
        beam_vmt = group['vmt_proportion'].sum()
        percentage = (beam_vmt / total_vmt_proportion) * 100
        print(f"{beam_class:<25} {percentage:<10.2f}%")

    # 5. Print top combinations by model year and BEAM class
    print("\n=== VMT Distribution by Year and BEAM Class ===")
    print(f"{'Year':<6} {'BEAM Class':<25} {'VMT %':<10}")
    print("-" * 41)

    year_beam_distribution = []
    for (year, beam_class), group in emfac_data.groupby(['model_year_group', 'beamClass']):
        vmt_sum = group['vmt_proportion'].sum()
        percentage = (vmt_sum / total_vmt_proportion) * 100
        year_beam_distribution.append((year, beam_class, percentage))

    # Sort by percentage (descending) and print
    for year, beam_class, percentage in sorted(year_beam_distribution,
                                               key=lambda x: x[2],
                                               reverse=True):
        print(f"{year:<6} {beam_class:<25} {percentage:<10.2f}%")

    # 6. Print top combinations (year, vehicle_class, fuel, beam_class)
    print("\n=== Top 10 VMT Distribution by Year-Class-Fuel-BEAM Combination ===")
    print(f"{'Year':<6} {'Vehicle Class':<20} {'Fuel':<6} {'BEAM Class':<25} {'VMT %':<10}")
    print("-" * 71)

    # Create combined distribution and sort by percentage
    combined_distribution = []
    for (year, vehicle_class, fuel, beam_class), group in emfac_data.groupby(
            ['model_year_group', 'vehicle_class', 'fuel', 'beamClass']):
        vmt_sum = group['vmt_proportion'].sum()
        percentage = (vmt_sum / total_vmt_proportion) * 100
        combined_distribution.append((year, vehicle_class, fuel, beam_class, percentage))

    # Sort by percentage (descending) and print top 10
    for year, vehicle_class, fuel, beam_class, percentage in sorted(combined_distribution,
                                                                    key=lambda x: x[4],
                                                                    reverse=True)[:10]:
        print(f"{year:<6} {vehicle_class:<20} {fuel:<6} {beam_class:<25} {percentage:<10.2f}%")


def print_beam_freight_distributions(beam_data):
    """
    Calculate and print VMT distributions from BEAM freight data by:
    1. BEAM vehicle class
    2. EMFAC fuel type
    3. Combined class and fuel

    Parameters:
    -----------
    beam_data : pandas DataFrame
        BEAM data with beamClass, emfacFuel, and vmt_proportion columns
    """
    # Calculate total VMT for normalization
    total_vmt = beam_data['total_vmt'].sum()

    # 1. Print beamClass distribution
    print("\n=== VMT Distribution by BEAM Vehicle Class ===")
    print(f"{'BEAM Class':<25} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 50)

    for beam_class, group in beam_data.groupby('beamClass'):
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        print(f"{beam_class:<25} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 2. Print emfacFuel distribution
    print("\n=== VMT Distribution by EMFAC Fuel Type ===")
    print(f"{'Fuel':<12} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 37)

    for fuel, group in beam_data.groupby('emfacFuel'):
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        print(f"{fuel:<12} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 3. Print combined distribution (beamClass, emfacFuel)
    print("\n=== VMT Distribution by BEAM Class and Fuel Combination ===")
    print(f"{'BEAM Class':<25} {'Fuel':<10} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 60)

    # Create combined distribution and sort by VMT percentage
    combined_distribution = []
    for (beam_class, fuel), group in beam_data.groupby(['beamClass', 'emfacFuel']):
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        combined_distribution.append((beam_class, fuel, group_total_vmt, vmt_percentage))

    # Sort by percentage (descending) and print all combinations
    for beam_class, fuel, group_total_vmt, vmt_percentage in sorted(combined_distribution,
                                                                    key=lambda x: x[3],
                                                                    reverse=True):
        print(f"{beam_class:<25} {fuel:<10} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 4. Add statistics about the VMT values
    print("\n=== VMT Statistics by BEAM Class ===")
    print(f"{'BEAM Class':<25} {'Min VMT':<12} {'Max VMT':<12} {'Avg VMT':<12} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 86)

    for beam_class, group in beam_data.groupby('beamClass'):
        min_vmt = group['total_vmt'].min()
        max_vmt = group['total_vmt'].max()
        avg_vmt = group['total_vmt'].mean()
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        print(
            f"{beam_class:<25} {min_vmt:<12.4f} {max_vmt:<12.4f} {avg_vmt:<12.4f} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 5. Add fuel-specific VMT statistics
    print("\n=== VMT Statistics by Fuel Type ===")
    print(f"{'Fuel Type':<12} {'Min VMT':<12} {'Max VMT':<12} {'Avg VMT':<12} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 73)

    for fuel, group in beam_data.groupby('emfacFuel'):
        min_vmt = group['total_vmt'].min()
        max_vmt = group['total_vmt'].max()
        avg_vmt = group['total_vmt'].mean()
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        print(
            f"{fuel:<12} {min_vmt:<12.4f} {max_vmt:<12.4f} {avg_vmt:<12.4f} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 6. Add combined BEAM class and fuel VMT statistics
    print("\n=== VMT Statistics by BEAM Class and Fuel Combination ===")
    print(
        f"{'BEAM Class':<25} {'Fuel':<10} {'Min VMT':<12} {'Max VMT':<12} {'Avg VMT':<12} {'Total VMT':<15} {'VMT %':<10}")
    print("-" * 96)

    combined_vmt_stats = []
    for (beam_class, fuel), group in beam_data.groupby(['beamClass', 'emfacFuel']):
        min_vmt = group['total_vmt'].min()
        max_vmt = group['total_vmt'].max()
        avg_vmt = group['total_vmt'].mean()
        group_total_vmt = group['total_vmt'].sum()
        vmt_percentage = (group_total_vmt / total_vmt) * 100
        combined_vmt_stats.append((beam_class, fuel, min_vmt, max_vmt, avg_vmt, group_total_vmt, vmt_percentage))

    # Sort by total VMT percentage (descending) and print
    for beam_class, fuel, min_vmt, max_vmt, avg_vmt, group_total_vmt, vmt_percentage in sorted(combined_vmt_stats,
                                                                                               key=lambda x: x[6],
                                                                                               reverse=True):
        print(
            f"{beam_class:<25} {fuel:<10} {min_vmt:<12.4f} {max_vmt:<12.4f} {avg_vmt:<12.4f} {group_total_vmt:<15.2f} {vmt_percentage:<10.2f}%")

    # 7. Print vehicle types by VMT if available
    if 'vehicleTypeId' in beam_data.columns:
        print("\n=== Top 10 Vehicle Types by Total VMT ===")
        vmt_by_vehicle = {}
        for vehicle_type, group in beam_data.groupby('vehicleTypeId'):
            vmt_by_vehicle[vehicle_type] = group['total_vmt'].sum()

        top_vehicles_by_vmt = sorted(vmt_by_vehicle.items(), key=lambda x: x[1], reverse=True)[:10]
        for vehicle_type, vmt_sum in top_vehicles_by_vmt:
            vmt_percentage = (vmt_sum / total_vmt) * 100
            print(f"{vehicle_type}: {vmt_sum:.2f} VMT ({vmt_percentage:.2f}%)")


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


def map_emfac_to_beam_freight(study_area, scenario_name, work_dir, emfac_data, beam_data):
    """
    Maps EMFAC vehicle classes to BEAM freight data preserving the distribution of:
    1. Model year
    2. Vehicle class
    3. Fuel type

    The function assigns appropriate emfacId to each BEAM freight vehicle while maintaining
    the statistical integrity of VMT distributions across vehicle classes and model years.
    After mapping, it compares the resulting distributions to verify preservation quality.

    Parameters:
    -----------
    study_area : str
        Study area name
    scenario_name : str
        Scenario name
    work_dir : str
        Working directory
    emfac_data : pd.DataFrame
        EMFAC VMT distribution data
    beam_data : pd.DataFrame
        BEAM freight VMT data

    Returns:
    --------
    pd.DataFrame
        The BEAM data with emfacId assigned to each row
    """
    # Check if output file already exists
    output_file = os.path.join(
        work_dir,
        f"emissions/{study_area}_beam_freight_emfac_{scenario_name}.csv"
    )

    if os.path.exists(output_file):
        print(f"Using existing mapping file: {output_file}")
        return pd.read_csv(output_file)

    # Filter EMFAC data to freight classes
    emfac_freight_data = emfac_data[emfac_data["beamClass"].isin(beam_freight_classes)]
    beam_df = beam_data.copy()
    emfac_df = emfac_freight_data.copy()

    # Print distributions for verification
    print(f"=== Map EMFAC To BEAM Freight Population ===")
    print(f"Loaded BEAM file with {len(beam_df)} rows and EMFAC file with {len(emfac_df)} rows.")

    # Step 1: Calculate total BEAM data VMT
    total_beam_vmt = beam_df['total_vmt'].sum()
    print(f"Total BEAM VMT: {total_beam_vmt}")

    # Step 2: Extract VMT proportion in EMFAC data
    emfac_vmt_props = emfac_df.groupby(['beamClass', 'model_year_group', 'fuel'])['total_vmt'].sum().reset_index()
    emfac_vmt_props['vmt_proportion'] = emfac_vmt_props['total_vmt'] / emfac_vmt_props['total_vmt'].sum()

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

        # Get current data
        current_beam = beam_by_class[current_class]
        current_emfac = emfac_df[emfac_df['beamClass'] == current_class]

        if current_emfac.empty:
            print(f"No EMFAC data for {current_class}, skipping...")
            continue

        # Get model year and fuel distributions
        my_fuel_dist = current_emfac.groupby(['model_year_group', 'fuel'])['vmt_proportion'].sum().reset_index()
        my_fuel_dist['vmt_proportion'] = my_fuel_dist['vmt_proportion'] / my_fuel_dist['vmt_proportion'].sum()

        # Process unassigned vehicles in current class
        beam_indices = set(current_beam.index)
        unassigned_in_class = beam_indices.intersection(unassigned_indices)

        # STEP 4.1: Assign vehicles from current class until threshold is met
        while (assigned_vmt[current_class] < target_vmt[current_class]) and unassigned_in_class:
            # Sample a vehicle
            idx = random.choice(list(unassigned_in_class))
            vehicle = beam_df.loc[idx]

            # Try to find matching fuel
            matching_by_fuel = my_fuel_dist[my_fuel_dist['fuel'] == vehicle['emfacFuel']]

            if not matching_by_fuel.empty:
                # Sample model_year_group based on VMT proportion
                sampled_my = np.random.choice(
                    matching_by_fuel['model_year_group'],
                    p=matching_by_fuel['vmt_proportion'] / matching_by_fuel['vmt_proportion'].sum()
                )

                # Get matching EMFAC entries and sample one
                matching_emfac = current_emfac[
                    (current_emfac['fuel'] == vehicle['emfacFuel']) &
                    (current_emfac['model_year_group'] == sampled_my)
                    ]

                sampled_emfac = matching_emfac.sample(weights='vmt_proportion').iloc[0]

                # Assign emfacId
                result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                result_df.loc[idx, 'assigned_class'] = current_class

                # Update tracking
                assigned_vmt[current_class] += vehicle['total_vmt']
                unassigned_indices.remove(idx)
                unassigned_in_class.remove(idx)
            else:
                # No matching fuel found for this vehicle
                print(f"No matching fuel in EMFAC for vehicle {vehicle['vehicleId']} with fuel {vehicle['emfacFuel']}")
                unassigned_in_class.remove(idx)

        # STEP 4.2: Current class exhausted but threshold not met
        if (assigned_vmt[current_class] < target_vmt[current_class]) and not unassigned_in_class:
            print(f"{current_class} exhausted but threshold not met. " +
                  f"Current: {assigned_vmt[current_class]:.2f}, Target: {target_vmt[current_class]:.2f}")

            # Check if there are more classes to process
            if i + 1 < len(beam_freight_classes):
                next_class = beam_freight_classes[i + 1]
                print(f"Flipping vehicles from {next_class} to {current_class}")

                # Get unassigned vehicles from next class
                next_beam = beam_by_class[next_class]
                next_indices = set(next_beam.index)
                unassigned_next = next_indices.intersection(unassigned_indices)

                # Assign from next class until threshold is met
                while (assigned_vmt[current_class] < target_vmt[current_class]) and unassigned_next:
                    idx = random.choice(list(unassigned_next))
                    vehicle = beam_df.loc[idx]

                    # Try to match by fuel
                    matching_by_fuel = my_fuel_dist[my_fuel_dist['fuel'] == vehicle['emfacFuel']]

                    if not matching_by_fuel.empty:
                        sampled_my = np.random.choice(
                            matching_by_fuel['model_year_group'],
                            p=matching_by_fuel['vmt_proportion'] / matching_by_fuel['vmt_proportion'].sum()
                        )

                        matching_emfac = current_emfac[
                            (current_emfac['fuel'] == vehicle['emfacFuel']) &
                            (current_emfac['model_year_group'] == sampled_my)
                            ]

                        sampled_emfac = matching_emfac.sample(weights='vmt_proportion').iloc[0]

                        # Assign and flip class
                        result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                        result_df.loc[idx, 'assigned_class'] = current_class

                        # Update tracking
                        assigned_vmt[current_class] += vehicle['total_vmt']
                        unassigned_indices.remove(idx)
                        unassigned_next.remove(idx)
                    else:
                        # No matching fuel in next class
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
                next_emfac = emfac_df[emfac_df['beamClass'] == next_class]

                if next_emfac.empty:
                    print(f"No EMFAC data for {next_class}, continuing with current class...")
                    continue

                print(f"Flipping remaining {current_class} vehicles to {next_class}")

                # Get distribution for next class
                next_my_fuel_dist = next_emfac.groupby(['model_year_group', 'fuel'])[
                    'vmt_proportion'].sum().reset_index()
                next_my_fuel_dist['vmt_proportion'] = next_my_fuel_dist['vmt_proportion'] / next_my_fuel_dist[
                    'vmt_proportion'].sum()

                # Assign remaining vehicles to next class
                for idx in list(unassigned_in_class):
                    vehicle = beam_df.loc[idx]

                    # Try to match by fuel
                    matching_by_fuel = next_my_fuel_dist[next_my_fuel_dist['fuel'] == vehicle['emfacFuel']]

                    if not matching_by_fuel.empty:
                        sampled_my = np.random.choice(
                            matching_by_fuel['model_year_group'],
                            p=matching_by_fuel['vmt_proportion'] / matching_by_fuel['vmt_proportion'].sum()
                        )

                        matching_emfac = next_emfac[
                            (next_emfac['fuel'] == vehicle['emfacFuel']) &
                            (next_emfac['model_year_group'] == sampled_my)
                            ]

                        sampled_emfac = matching_emfac.sample(weights='vmt_proportion').iloc[0]

                        # Assign and flip class
                        result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
                        result_df.loc[idx, 'assigned_class'] = next_class

                        # Update tracking
                        assigned_vmt[next_class] += vehicle['total_vmt']
                        unassigned_indices.remove(idx)
                        unassigned_in_class.remove(idx)
                    else:
                        # No matching fuel when flipping to next class
                        print(f"When flipping from {current_class} to {next_class}: " +
                              f"No matching fuel in EMFAC for vehicle {vehicle['vehicleId']} with fuel {vehicle['emfacFuel']}")
                        unassigned_in_class.remove(idx)

    # Handle any remaining unassigned vehicles
    if unassigned_indices:
        print(f"\nHandling {len(unassigned_indices)} remaining unassigned vehicles...")

        for idx in list(unassigned_indices):
            vehicle = beam_df.loc[idx]

            # Try to match by fuel first
            matching_by_fuel = emfac_df[emfac_df['fuel'] == vehicle['emfacFuel']]

            if not matching_by_fuel.empty:
                sampled_emfac = matching_by_fuel.sample(weights='vmt_proportion').iloc[0]
            else:
                # If no fuel match, sample any EMFAC entry
                print(
                    f"No matching fuel in EMFAC for remaining vehicle {vehicle['vehicleId']} with fuel {vehicle['emfacFuel']}")
                matching_by_class = emfac_df[emfac_df['beamClass'] == vehicle['beamClass']]
                sampled_emfac = matching_by_class.sample(weights='vmt_proportion').iloc[0]

            # Assign the emfacId
            result_df.loc[idx, 'emfacId'] = sampled_emfac['emfacId']
            result_df.loc[idx, 'assigned_class'] = sampled_emfac['beamClass']

            # Update tracking
            assigned_class = sampled_emfac['beamClass']
            if assigned_class in assigned_vmt:
                assigned_vmt[assigned_class] += vehicle['total_vmt']

            unassigned_indices.remove(idx)

    # Verify all vehicles have been assigned
    unassigned_count = result_df['emfacId'].isna().sum()
    if unassigned_count > 0:
        print(f"Warning: {unassigned_count} vehicles still not assigned an emfacId")
    else:
        print("All vehicles successfully assigned an emfacId")

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


def assign_emfac_id_to_vehicle_types(study_area, scenario_name, emissions_rates, emfac_population, config):
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

    _work_dir = study_area_config["work_dir"]
    carriers_out_file = os.path.join(_work_dir, f"{config["carriers_file"].replace(".csv", "--TrAP.csv")}")
    ft_vehtypes_out_file = os.path.join(_work_dir,f"{config["ft_vehicle_types_file"].replace(".csv", "--TrAP.csv")}")
    pax_vehtypes_out_file = os.path.join(_work_dir,f"{config["pax_vehicle_types_file"].replace(".csv", "--TrAP.csv")}")
    beam_fleet_vmt_file = str(os.path.join(_work_dir,f"emissions/{study_area}_beam_freight_vmt_{scenario_name}.csv"))
    emissions_rates_dir = os.path.join(
        os.path.dirname(os.path.join(_work_dir, f"{config["ft_vehicle_types_file"]}")),
        f"TrAP/{ft_scenario_label}"
    )

    if os.path.exists(carriers_out_file) and os.path.exists(ft_vehtypes_out_file) and os.path.exists(pax_vehtypes_out_file):
        print("All carriers and vehicle types emissions files have already been created")
    else:
        # Create a copy of the original vehicleTypeId and set up a lookup dictionary
        pax_vehicle_types = pd.read_csv(os.path.join(_work_dir, f"{config["pax_vehicle_types_file"]}"), dtype=str)
        car_bike_mask = (pax_vehicle_types['vehicleCategory'].isin([beam_class_car, beam_class_bike]))
        bus_mask = ((pax_vehicle_types['vehicleCategory'] == beam_class_mdp) & (pax_vehicle_types['vehicleTypeId'].str.lower().str.contains('bus')))
        pax_freight_mask = (pax_vehicle_types['vehicleCategory'].isin(beam_freight_classes))
        pax_vehicle_types_filtered = pax_vehicle_types[car_bike_mask | bus_mask]
        pax_vehicle_types_others = pax_vehicle_types[~(car_bike_mask | bus_mask | pax_freight_mask)]

        ft_vehicle_types = pd.read_csv(os.path.join(_work_dir, f"{config["ft_vehicle_types_file"]}"), dtype=str)
        ft_freight_mask = (ft_vehicle_types['vehicleCategory'].isin(beam_freight_classes))
        ft_vehicle_types_filtered = ft_vehicle_types[ft_freight_mask]

        vehicle_types_updated = updated_fuel_types_from_emfac(
            pd.concat([pax_vehicle_types_filtered, ft_vehicle_types_filtered], axis=0)
        )

        # ## Freight ## #
        _carriers_file = str(os.path.join(_work_dir, config["beam"]["carriers_file"]))
        _payloads_file = str(os.path.join(_work_dir, config["beam"]["payloads_file"]))
        carriers_raw = pd.read_csv(str(os.path.join(_work_dir, config["beam"]["carriers_file"])), dtype=str)
        payloads_raw = pd.read_csv(str(os.path.join(_work_dir, config["beam"]["payloads_file"])), dtype=str)
        tour_distances = calculate_tour_distances(payloads_raw)
        carriers = carriers_raw[['tourId', 'vehicleId', 'vehicleTypeId']].copy()
        payloads = payloads_raw[['payloadId', 'tourId', 'payloadType']].copy()
        payloads.loc[:, 'payloadType'] = payloads['payloadType'].astype(str)
        payloads_summary = payloads.groupby(['tourId'])['payloadType'].agg('|'.join).reset_index()
        payloads_merged = pd.merge(payloads_summary, carriers, on='tourId', how='left')
        # Merge payloads with vehicle types
        payloads_vehtypes = pd.merge(
            payloads_merged,
            vehicle_types_updated[['vehicleTypeId', 'beamClass', 'emfacFuel', 'primaryFuelType', 'secondaryFuelType']],
            on='vehicleTypeId',
            how='left'
        )
        discrete_freight_population = payloads_vehtypes.drop_duplicates('vehicleId', keep='first').copy()
        # Calculate total distance across all tours
        total_distance = sum(tour_distances.values())
        # Calculate proportion for each tour
        tour_proportions = {tour_id: distance / total_distance for tour_id, distance in tour_distances.items()}
        discrete_freight_population['total_vmt'] = discrete_freight_population['tourId'].map(tour_distances)
        discrete_freight_population['vmt_proportion'] = discrete_freight_population['tourId'].map(tour_proportions)
        discrete_freight_population["oldVehicleTypeId"] = discrete_freight_population["vehicleTypeId"]
        discrete_freight_population["vehicleTypeId"] = discrete_freight_population['emfacId']
        discrete_freight_population.drop_duplicates(subset='vehicleTypeId', keep='first')
        discrete_freight_population.to_csv(beam_fleet_vmt_file, index=False)
        ft_vehtypes_with_emfac_id = pd.DataFrame(
            discrete_freight_population.apply(
                lambda row: {
                    **ft_vehicle_types_filtered.set_index("vehicleTypeId").to_dict('index')[row["oldVehicleTypeId"]],
                    "vehicleTypeId": row["vehicleTypeId"],
                    "vehicleCategory": row["beamClass"]
                },
                axis=1
            ).tolist()
        )
        ft_vehtypes_with_emfac_id.drop('oldVehicleTypeId', axis=1, inplace=True)
        vehicle_id_to_type_mapping = dict(
            zip(discrete_freight_population['vehicleId'], discrete_freight_population['vehicleTypeId'])
        )
        carriers = carriers_raw.copy()
        carriers['vehicleTypeId'] = carriers.apply(lambda row: vehicle_id_to_type_mapping.get(row['vehicleId']), axis=1)
        carriers.dropna(subset=['vehicleTypeId'], inplace=True)
        # Find the dropped rows by filtering the original dataframe
        dropped_rows = carriers_raw[carriers_raw['vehicleTypeId'].isna()]
        if dropped_rows:
            # Print the dropped rows
            print("Dropped rows:")
            print(dropped_rows)
        print(f"Writing {carriers_out_file}")
        carriers.to_csv(carriers_out_file, index=False)

        # ## Passenger ## #
        # Step 2: Build new passenger vehicle types
        df_merged = pd.merge(vehicle_types_updated, emfac_population,
            left_on=['beamClass', 'emfacFuel'],  # column names in the left dataframe
            right_on=['beamClass', 'fuel'],  # corresponding column names in the right dataframe
            how='left'
        )
        car_mask = df_merged["beamClass"] == beam_class_car
        non_car_mask = (df_merged["beamClass"] == beam_class_bike) | (df_merged["beamClass"] == beam_class_mdp)
        df_merged_car = df_merged[car_mask].copy()
        df_merged_others = df_merged[non_car_mask].copy()
        # Process car class vehicles
        df_merged_car['population_share'] = df_merged_car['population'] / df_merged_car['population'].sum()
        # This function should be defined based on your specific requirements
        # Assuming it's available in the original codebase
        # If not defined elsewhere, you need to implement it here
        df_merged_car['updated_sampleProbabilityString'] = df_merged_car.apply(
            lambda row: row.get('sampleProbabilityString', ''), axis=1
        )
        df_merged_car['updated_sampleProbabilityWithinCategory'] = df_merged_car.apply(
            lambda row: row['sampleProbabilityWithinCategory'] * row['population_share'], axis=1
        )
        # Update vehicleTypeId
        df_merged_car['oldVehicleTypeId'] = df_merged_car.apply(lambda row: f"{row['emfacId']}", axis=1)
        # Update the original dataframe with new probabilities and vehicleTypeId
        df_merged_car['sampleProbabilityString'] = df_merged_car['updated_sampleProbabilityString']
        df_merged_car['sampleProbabilityWithinCategory'] = df_merged_car['updated_sampleProbabilityWithinCategory']
        df_merged_car['vehicleTypeId'] = df_merged_car['oldVehicleTypeId']
        df_merged_car['vehicleCategory'] = df_merged_car['beamClass']
        df_merged_car.drop('oldVehicleTypeId', axis=1, inplace=True)
        # Combine the car and other vehicle types into a single dataframe
        pax_vehtypes_with_emfac_id = pd.concat([df_merged_car[df_merged_others.columns], df_merged_others], axis=0)


        # ## Freight & Passenger ## #
        vehtypes_with_emfac_id = pd.concat([pax_vehtypes_with_emfac_id, ft_vehtypes_with_emfac_id], axis=0)

        # ## Emissions Rates ## #
        # Define the desired column order with 'vehicleTypeId' at the front
        _updated_vehicle_types = vehtypes_with_emfac_id[
            ['vehicleTypeId'] + [col for col in vehtypes_with_emfac_id.columns if col not in {'vehicleTypeId'}]
        ]
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
        for i in range(0, len(_updated_vehicle_types), chunk_size):
            chunk = _updated_vehicle_types.iloc[i:i + chunk_size]
            chunk_results = Parallel(n_jobs=-1, timeout=600)(  # 10-minute timeout
                delayed(process_single_vehicle_type)(
                    veh_type,
                    emissions_rates,
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
                _updated_vehicle_types.loc[
                    _updated_vehicle_types['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'] = relative_rates_filepath

        # Save updated vehicle types
        print(f"Writing:\n{ft_vehtypes_out_file}\n{pax_vehtypes_out_file}")
        ft_freight_mask = (_updated_vehicle_types['vehicleCategory'].isin(beam_freight_classes))
        _updated_ft_vehicle_types = _updated_vehicle_types[ft_freight_mask]
        _updated_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)

        _updated_pax_vehicle_types_others = pax_vehicle_types_others.copy()
        _updated_pax_vehicle_types_others['emissionsRatesFile'] = ""
        _updated_pax_vehicle_types = pd.concat(
            [_updated_vehicle_types[~ft_freight_mask], pax_vehicle_types_others],
            axis=0
        )
        _updated_pax_vehicle_types.to_csv(pax_vehtypes_out_file, index=False)


if __name__ == "__main__":
    # Configuration parameters
    area = "sfbay"
    study_area_config = get_area_config(area)
    work_dir = study_area_config["work_dir"]

    run_batch = "2024-11-06"
    scenario = "2018_Baseline"
    ft_scenario_label = scenario.replace("_", "-")
    pax_scenario_label = scenario.replace("_", "-")
    run_batch_label = run_batch.replace("-","")
    emissions_config = study_area_config["emissions"][scenario]

    # ### Output directories and files ### #
    #
    emfac_pop, emfac_class_map = process_emfac_population(area, scenario, work_dir, emissions_config)
    print("\n=== EMFAC Population ===\n")
    print(f"total_population: {emfac_pop["population"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== EMFAC VMT ===\n")
    emfac_vmt = process_emfac_vmt(area, scenario, work_dir, emfac_class_map, emissions_config)
    print(f"total_vmt: {emfac_vmt["total_vmt"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== CARB Emissions Rates ===\n")
    rates = process_emissions_rates(area, scenario, work_dir, emfac_class_map, emissions_config)
    print(f"rates: {len(rates)}")

    print("\n=== BEAM Fleet ===\n")
    beam_freight_fleet = process_beam_freight(
        area,
        scenario,
        work_dir,
        emissions_config
    )
    print(f"Fleet: {len(beam_freight_fleet)}")

    print("\n=== Map EMFAC To BEAM Freight Population ===\n")
    mapped_beam_freight_fleet = map_emfac_to_beam_freight(area, scenario, work_dir, emfac_vmt, beam_freight_fleet)
    # print_stats(emfac_vmt, mapped_beam_freight_fleet)

    assign_emfac_id_to_vehicle_types(area, scenario, rates, emfac_pop, emissions_config)

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