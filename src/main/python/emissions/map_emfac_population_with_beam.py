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

# Function to determine the correct key for the map
def get_fuel_key(row):
    primary_fuel = row['primaryFuelType'].str.lower()

    # Special handling for electricity based on secondary fuel
    if primary_fuel == "electricity":
        suffix = "only" if pd.isna(row['secondaryFuelType']) else "hybrid"
        return f"{primary_fuel}-{suffix}"

    return primary_fuel

def updated_fuel_types_from_emfac(og_vehicletypes_df):
    vehtypes = og_vehicletypes_df.copy()
    # Convert primaryFuelType to lowercase directly
    vehtypes['primaryFuelType_lower'] = vehtypes['primaryFuelType'].str.lower()

    fuel_map = {
        "hydrogen": 'Elec',
        "electricity-na": 'Elec',
        "electricity-notna": 'Phe',
        "gasoline": 'Gas',
        "diesel": 'Dsl',
        "biodiesel": 'Dsl',
        "naturalgas": 'NG'

    }
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
    ft_vehicle_types = updated_fuel_types_from_emfac(ft_vehicle_types_filtered)[
        ['vehicleTypeId', 'beamClass', 'emfacFuel']
    ]

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
    vehicle_w_vmt["beamClassBis"] = ""
    vehicle_w_vmt["emfacFuelBis"] = ""
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
                vehicle_w_vmt.loc[i, "beamClassBis"] = selected_beam_class
                vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id
            else:
                class_matches = emfac_w_vmt[class_mask].copy()
                if not class_matches.empty:
                    sampled_match = class_matches.sample(n=1, weights='vmt_proportion').iloc[0]
                    selected_emfac_id, selected_model_year, selected_fuel = sampled_match[
                        ["emfacId", 'model_year_group', 'fuel']
                    ].values()
                    composite_key = f"{selected_model_year},{veh_class},{selected_fuel}"
                    vehicle_w_vmt.loc[i, "emfacFuelBis"] = selected_fuel
                    vehicle_w_vmt.loc[i, "emfacId"] = selected_emfac_id
                else:
                    sampled_match = emfac_w_vmt.sample(n=1, weights='vmt_proportion').iloc[0]
                    selected_emfac_id, selected_model_year, selected_fuel, selected_beam_class = sampled_match[
                        ["emfacId", 'model_year_group', 'fuel', 'beamClass']
                    ].values()
                    composite_key = f"{selected_model_year},{selected_beam_class},{selected_fuel}"
                    vehicle_w_vmt.loc[i, "emfacFuelBis"] = selected_fuel
                    vehicle_w_vmt.loc[i, "beamClassBis"] = selected_beam_class
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

    # Step 6: Update vehicle properties based on matched EMFAC vehicles
    # Simplify property reconciliation with a priority-based matching approach
    vehicle_w_vmt_original = vehicle_w_vmt.copy()

    for i, row in vehicle_w_vmt.iterrows():
        # Check if substitutions were made
        beam_class_changed = row['beamClassBis'] != "" and row['beamClassBis'] != row['beamClass']
        fuel_changed = row['emfacFuelBis'] != "" and row['emfacFuelBis'] != row['emfacFuel']

        if not (beam_class_changed or fuel_changed):
            continue  # No changes needed for this vehicle

        # Define matching strategies in order of preference
        matching_strategies = []

        if beam_class_changed and fuel_changed:
            # Both changed - try all strategies
            matching_strategies = [
                # Strategy 1: Match both class and fuel (exact match)
                (vehicle_w_vmt_original['beamClass'] == row['beamClass']) &
                (vehicle_w_vmt_original['emfacFuel'] == row['emfacFuel']),

                # Strategy 2: Match fuel only
                vehicle_w_vmt_original['emfacFuel'] == row['emfacFuel'],

                # Strategy 3: Match class only
                vehicle_w_vmt_original['beamClass'] == row['beamClass']
            ]
        elif beam_class_changed:
            # Only class changed
            matching_strategies = [
                # Strategy 1: Match class
                vehicle_w_vmt_original['beamClass'] == row['beamClass'],

                # Strategy 2: Match fuel
                vehicle_w_vmt_original['emfacFuel'] == row['emfacFuel']
            ]
        elif fuel_changed:
            # Only fuel changed
            matching_strategies = [
                # Strategy 1: Match both class and fuel
                (vehicle_w_vmt_original['beamClass'] == row['beamClass']) &
                (vehicle_w_vmt_original['emfacFuel'] == row['emfacFuel']),

                # Strategy 2: Match class only
                vehicle_w_vmt_original['beamClass'] == row['beamClass'],

                # Strategy 3: Match fuel only
                vehicle_w_vmt_original['emfacFuel'] == row['emfacFuel']
            ]

        # Try each strategy until we find a match
        best_match = None
        for strategy in matching_strategies:
            matches = vehicle_w_vmt_original[strategy]
            if not matches.empty:
                best_match = matches.iloc[0]
                break

        # As last resort, take any vehicle if all strategies failed
        if best_match is None and not vehicle_w_vmt_original.empty:
            best_match = vehicle_w_vmt_original.iloc[0]

        # Apply the appropriate properties based on what changed
        if best_match is not None:
            if beam_class_changed:
                vehicle_w_vmt.loc[i, 'beamClass'] = best_match['beamClass']
                vehicle_w_vmt.loc[i, 'vehicleCategory'] = best_match['vehicleCategory']

            if fuel_changed:
                vehicle_w_vmt.loc[i, 'primaryFuelType'] = best_match['primaryFuelType']
                vehicle_w_vmt.loc[i, 'secondaryFuelType'] = best_match['secondaryFuelType']

    # Clean up temporary columns
    vehicle_w_vmt = vehicle_w_vmt.drop(['beamClassBis', 'emfacFuelBis'], axis=1)



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

        vehicle_types_updated = updated_fuel_types_from_emfac(
            pd.concat([pax_vehicle_types_filtered, ft_vehicle_types_filtered], axis=0)
        )

        ft_vehicle_types_filtered = vehicle_types_updated[vehicle_types_updated["beamClass"].isin(beam_freight_classes)]
        pax_vehicle_types_filtered = vehicle_types_updated[vehicle_types_updated["beamClass"].isin(beam_passenger_classes)]

        # ## Freight ## #

        freight_pop_with_emfac_id = map_emfac_to_beam_freight(_scenario, _work_dir, _emfac_vmt, _config)
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