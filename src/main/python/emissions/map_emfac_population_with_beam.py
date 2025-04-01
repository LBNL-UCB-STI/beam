import json
import logging
import os
import os.path
import shutil
import sys
from typing import Dict, Any, Optional
from collections import defaultdict

import pandas as pd
import pyarrow as pa
import pyarrow.csv as csv
from joblib import Parallel, delayed

from _emfac_and_emissions_rates_processing import process_emfac_population
from _emfac_and_emissions_rates_processing import process_emfac_vmt
from _emfac_and_emissions_rates_processing import process_emissions_rates
from _emfac_beam_ft_matching import generate_emfac_mapped_freight_fleet
from _emfac_beam_pax_mapping import generate_emfac_mapped_passenger_fleet

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import BeamClasses
from python.utils.study_area_config import get_fuel_key
from python.utils.files_utils import sanitize_name

pd.set_option('display.max_columns', 20)


def create_emfac_id(row):
    model_year_group_st = sanitize_name(row['model_year_group']).replace("_","")
    vehicle_class_st = sanitize_name(row['vehicle_class']).replace("_","")
    fuel_st = sanitize_name(row['fuel']).replace("_","")
    return f"{model_year_group_st}-{vehicle_class_st}-{fuel_st}"

def prepare_emissions_data_for_mapping(area, scenario, work_dir, config):
    def categorize_model_year(year):
        # https://pubs.acs.org/doi/full/10.1021/acs.est.9b04763
        if year <= 1993: return '1993'
        elif year <= 2006: return '2006'
        else: return '2018'
    def format_emissions_data(emfac_types: pd.DataFrame) -> pd.DataFrame:
        result_df = emfac_types.copy()
        result_df['mappedClass'] = result_df['vehicle_class'].map(config["class_mapping"]["emfac"])
        print_unmapped(result_df, 'mappedClass', 'vehicle_class')
        result_df.dropna(subset=['mappedClass'], inplace=True)
        result_df['mappedFuel'] = result_df['fuel'].map(config["fuel_mapping"]["emfac"])
        print_unmapped(result_df, 'mappedFuel', 'fuel')
        result_df.dropna(subset=['mappedFuel'], inplace=True)
        result_df['model_year_group'] = result_df['model_year'].apply(categorize_model_year)
        result_df[['county', 'area']] = result_df['sub_area'].str.extract(r'^([^()]+)\s*\(([^)]+)\)')
        result_df['county'] = result_df['county'].str.strip().str.lower()
        result_df['area'] = result_df['area'].str.strip()
        result_df['emfacId'] = result_df.apply(create_emfac_id, axis=1)
        return result_df

    emfac_pop = process_emfac_population(area, scenario, work_dir, config, format_emissions_data)
    print("\n=== EMFAC Population ===\n")
    print(f"total_population: {emfac_pop["population"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== EMFAC VMT ===\n")
    emfac_vmt = process_emfac_vmt(area, scenario, work_dir, config, format_emissions_data)
    print(f"total_vmt: {emfac_vmt["total_vmt"].sum() / 1_000_000:.1f}M")
    #
    print("\n=== CARB Emissions Rates ===\n")
    rates = process_emissions_rates(area, scenario, work_dir, config, format_emissions_data)
    print(f"rates: {len(rates):,}")

    return emfac_pop, emfac_vmt, rates


def assign_emission_rates_to_vehicle_types(scenario, emissions_rates, emfac_pop, emfac_vmt, work_dir, config):
    """
    Process freight and passenger vehicle emissions by assigning EMFAC IDs and emissions rates.

    This function:
    1. Builds new freight vehicle types and assigns them to carriers
    2. Creates or loads passenger vehicle types
    3. Assigns emissions rates to all vehicle types

    Args:
        scenario (str): Scenario name
        emissions_rates (DataFrame): DataFrame containing emissions rates
        emfac_pop (DataFrame): DataFrame containing EMFAC population data
        emfac_vmt (DataFrame): DataFrame containing EMFAC VMT data
        work_dir (str): Working directory for file operations
        config (dict): Configuration dictionary

    Returns:
        None: Files are saved to disk
    """
    # ######
    def format_beam_vehicle_types(vehicle_types: pd.DataFrame) -> pd.DataFrame:
        # Validate inputs
        result_df = vehicle_types.copy()
        result_df['fuel_key'] = result_df.apply(get_fuel_key, axis=1)
        result_df['mappedFuel'] = result_df['fuel_key'].map(config["fuel_mapping"]["beam"])
        na_count = result_df['mappedFuel'].isna().sum()
        if na_count > 0:
            logging.warning(f"{na_count} vehicle types could not be mapped to EMFAC fuel types")
        result_df['mappedClass'] = result_df['vehicleCategory']
        return result_df
    # ######

    print("\n=== Map EMFAC To BEAM Population ===\n")

    # Define output file paths
    carriers_out_file = os.path.join(work_dir, f"{config['beam']['carriers_file'].replace('.csv', '--TrAP.csv')}")
    ft_vehtypes_out_file = os.path.join(work_dir,
                                        f"{config['beam']['ft_vehicle_types_file'].replace('.csv', '--TrAP.csv')}")
    pax_vehtypes_out_file = os.path.join(work_dir,
                                         f"{config['beam']['pax_vehicle_types_file'].replace('.csv', '--TrAP.csv')}")
    emissions_rates_dir = os.path.join(
        os.path.dirname(os.path.join(work_dir, f"{config['beam']['ft_vehicle_types_file']}")),
        f"TrAP/{scenario.replace('_', '-')}"
    )

    # Process freight vehicles
    if os.path.exists(carriers_out_file) and os.path.exists(ft_vehtypes_out_file):
        logging.info("All carriers and freight vehicle types emissions files have already been created")
        logging.info(f"    carriers: {carriers_out_file}")
        logging.info(f"    freight vehicle types: {ft_vehtypes_out_file}")
        new_ft_vehicle_types = pd.read_csv(ft_vehtypes_out_file)
    else:
        new_carriers, new_ft_vehicle_types = generate_emfac_mapped_freight_fleet(
            emfac_vmt, BeamClasses.get_freight_classes(), work_dir, config, format_beam_vehicle_types
        )
        logging.info(f"Saving updated files to:\n  {carriers_out_file}\n  {ft_vehtypes_out_file}")
        new_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)
        new_carriers.to_csv(carriers_out_file, index=False)

    # Process passenger vehicles
    if os.path.exists(pax_vehtypes_out_file):
        logging.info("Passenger vehicle types emissions files have already been created:")
        logging.info(f"    passenger vehicle types: {pax_vehtypes_out_file}")
        new_pax_vehicle_types = pd.read_csv(pax_vehtypes_out_file)
        temp = pd.read_csv(os.path.join(work_dir, f"{config['beam']['pax_vehicle_types_file']}"))
        other_pax_vehicle_types = temp[temp["vehicleCategory"].isin(
            BeamClasses.get_freight_classes() + new_pax_vehicle_types["vehicleCategory"].unique().tolist())]
    else:
        # Generate passenger vehicle types
        new_pax_vehicle_types, other_pax_vehicle_types = generate_emfac_mapped_passenger_fleet(
            emfac_pop,
            car_class=BeamClasses.CLASS_CAR,
            bike_class=BeamClasses.CLASS_BIKE,
            transit_class=BeamClasses.CLASS_MDP,
            filter_out_classes=BeamClasses.get_freight_classes(),
            work_dir=work_dir,
            config=config,
            format_func=format_beam_vehicle_types,
        )

    # Prepare for emissions rates processing
    vehtypes_with_emfac_id = pd.concat([new_ft_vehicle_types, new_pax_vehicle_types], ignore_index=True)

    # Prepare directory for emissions rates files
    try:
        if os.path.exists(emissions_rates_dir):
            shutil.rmtree(emissions_rates_dir)
        os.makedirs(emissions_rates_dir, exist_ok=True)
        logging.info(f"Ready to write new data to the directory {emissions_rates_dir}")
    except Exception as e:
        logging.error(f"Failed to prepare directory {emissions_rates_dir}: {e}")

    # Process vehicle emissions in parallel with chunking
    chunk_size = 100
    results = []
    for i in range(0, len(vehtypes_with_emfac_id), chunk_size):
        chunk = vehtypes_with_emfac_id.iloc[i:i + chunk_size]
        chunk_results = Parallel(n_jobs=-1, timeout=600)(
            delayed(process_single_vehicle_type)(
                veh_type,
                emissions_rates,
                f"{emissions_rates_dir}/"
            ) for _, veh_type in chunk.iterrows()
        )
        results.extend(chunk_results)
        del chunk_results  # Free memory

    # Update emissions rate file paths in vehicle types
    path_parts = emissions_rates_dir.split('/')
    trap_index = path_parts.index("TrAP")
    shortened_path = '/'.join(path_parts[trap_index:])
    for veh_type_id in results:
        if veh_type_id:
            relative_rates_filepath = f"{shortened_path}/{veh_type_id}.csv"
            vehtypes_with_emfac_id.loc[
                vehtypes_with_emfac_id['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'
            ] = relative_rates_filepath

    # Save updated vehicle types
    logging.info(f"Writing:\n{ft_vehtypes_out_file}\n{pax_vehtypes_out_file}")

    # Save freight vehicle types
    ft_freight_mask = (vehtypes_with_emfac_id['vehicleCategory'].isin(BeamClasses.get_freight_classes()))
    updated_ft_vehicle_types = vehtypes_with_emfac_id[ft_freight_mask]
    updated_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)

    # Save passenger vehicle types
    updated_pax_vehicle_types_others = other_pax_vehicle_types.copy()
    updated_pax_vehicle_types_others['emissionsRatesFile'] = ""
    updated_pax_vehicle_types = pd.concat(
        [vehtypes_with_emfac_id[~ft_freight_mask], other_pax_vehicle_types],
        axis=0
    )
    updated_pax_vehicle_types.to_csv(pax_vehtypes_out_file, index=False)

def generate_emfac_beam_class_mapping(_study_area, _scenario_name, _work_dir, _config, to_filter_out):
    """
    Creates vehicle class mapping and saves it to a JSON file if it doesn't exist.
    If the file exists, loads and returns the existing mapping.

    Args:
        _study_area: Stud Area
        _scenario_name: Scenario Name
        _work_dir:
        _config: Configuration dictionary
        to_filter_out:

    Returns:
        dict: The vehicle class mapping (either newly created or loaded from existing file)
    """
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

    table = csv.read_csv(
        os.path.join(_work_dir, _config["emfac"]["emfac_pop_by_model_year_file"]),
        read_options=pa.csv.ReadOptions(use_threads=True)
    )
    df = table.to_pandas()

    for vehicle in df["vehicle_class"].unique():
        if 'Utility' in vehicle or 'Public' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'Port' in vehicle or 'POLA' in vehicle or 'POAK' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'SWCV' in vehicle or 'PTO' in vehicle or 'T6TS' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif vehicle in ['LDA', 'LDT1', 'LDT2', 'MDV']:
            mapping[vehicle] = BeamClasses.CLASS_CAR
        elif vehicle in ['MCY']:
            mapping[vehicle] = BeamClasses.CLASS_BIKE
        elif vehicle in ['UBUS']:
            mapping[vehicle] = BeamClasses.CLASS_MDP
        elif 'LHD' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_2B3_VOCATIONAL
        elif 'Class 4' in vehicle or 'Class 5' in vehicle or 'Class 6' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_456_VOCATIONAL
        elif 'Class 7' in vehicle or 'Class 8' in vehicle:
            if 'Tractor' in vehicle or 'CAIRP' in vehicle:
                mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
            else:
                mapping[vehicle] = BeamClasses.CLASS_78_VOCATIONAL
        elif "T7IS" in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
        else:
            mapping[vehicle] = "NotMatched"

    # Print category groupings
    class_groups = defaultdict(list)
    for vehicle, vehicle_class in mapping.items():
        if vehicle_class in to_filter_out:
            mapping[vehicle] = "NotMatched"
        class_groups[mapping[vehicle]].append(vehicle)
    for vehicle_class, vehicles in class_groups.items():
        print(f"Category: {vehicle_class}")
        for vehicle in vehicles:
            print(f"  - {vehicle}")

    return {k: v for k, v in mapping.items() if v != "NotMatched"}

def process_single_vehicle_type(
        veh_type: Dict[str, Any],
        emissions_rates: pd.DataFrame,
        rates_prefix_filepath: str
) -> Optional[str]:
    """
    Process and save emissions rates for a single vehicle type.

    Filters the emissions rates for a specific vehicle type identified by its
    vehicleTypeId, removes the emfacId column, and saves the filtered data
    to a CSV file in the specified directory.

    Args:
        veh_type (Dict[str, Any]): Dictionary containing vehicle type information,
            must include 'vehicleTypeId' key
        emissions_rates (pd.DataFrame): DataFrame containing emissions rates data
            with 'emfacId' column matching vehicleTypeId values
        rates_prefix_filepath (str): Directory path prefix where the CSV file
            will be saved

    Returns:
        Optional[str]: The vehicleTypeId if processing was successful, None if
            no emissions data was found or an error occurred

    Raises:
        IOError: If there is an error writing the CSV file
    """
    try:
        veh_type_id = veh_type['vehicleTypeId']

        # Filter emissions_rates for the current vehicle type
        veh_emissions = emissions_rates[emissions_rates['emfacId'] == veh_type_id].copy()

        if veh_emissions.empty:
            logging.warning(f"No emissions data found for vehicle type {veh_type_id}")
            return None

        # Remove the emfacId column as it's no longer needed
        veh_emissions = veh_emissions.drop('emfacId', axis=1)

        # Generate the file path
        file_path = f"{rates_prefix_filepath}{veh_type_id}.csv"

        # Ensure directory exists
        os.makedirs(os.path.dirname(file_path), exist_ok=True)

        logging.info(f"Writing emissions data to {file_path}")

        # Save the emissions rates to a CSV file
        veh_emissions.to_csv(file_path, index=False)

        return veh_type_id

    except KeyError as e:
        logging.error(f"Missing required key in vehicle type data: {e}")
        return None
    except IOError as e:
        logging.error(f"Error writing emissions data file: {e}")
        return None
    except Exception as e:
        logging.error(f"Unexpected error processing vehicle type: {e}")
        return None

def print_unmapped(df, mapped_col, col_to_be_mapped):
    unmapped_classes = df[df[mapped_col].isna()][col_to_be_mapped].unique()
    if len(unmapped_classes) > 0:
        unmapped_classes_message = f"The following {col_to_be_mapped} were not mapped to {mapped_col}:\n"
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


def run():
    # Configuration parameters
    area = "sfbay"
    run_batch = "2024-11-06"
    run_batch_label = run_batch.replace("-", "")
    scenario = "2018_Baseline"
    scenario_label = scenario.replace("_", "-")

    study_area_config = get_area_config(area)
    config = study_area_config["emissions"][scenario]
    beam_config = config["beam"]
    beam_config["carriers_file"] = f"beam-ft/{run_batch}/{scenario}/carriers--{scenario_label}.csv"
    beam_config["payloads_file"] = f"beam-ft/{run_batch}/{scenario}/payloads--{scenario_label}.csv"
    beam_config["ft_vehicle_types_file"] = f"vehicle-tech/ft-vehicletypes--{run_batch_label}--{scenario_label}.csv"
    beam_config["pax_vehicle_types_file"] = f"vehicle-tech/pax-vehicletypes--{scenario_label}.csv"
    emfac_class_map = generate_emfac_beam_class_mapping(
        area, scenario, study_area_config["work_dir"], config, to_filter_out=[BeamClasses.CLASS_2B3_VOCATIONAL]
    )
    config["class_mapping"]["emfac"] = emfac_class_map
    # Write Config file to keep track of runs
    # Write it onl after all modification to config are completed
    with open(os.path.join(study_area_config["work_dir"], f"emissions/{area}_emissions_config_{scenario}.json"), 'w') as f:
        json.dump(study_area_config, f, indent=2)

    # #################################################################

    print(f"\n{'='*50}")
    print(f"  EMISSIONS PROCESSING - {area.upper()} REGION")
    print(f"  Run Batch: {run_batch}")
    print(f"  Scenario: {scenario}")
    print(f"{'='*50}\n")

    emfac_pop, emfac_vmt, rates = prepare_emissions_data_for_mapping(area, scenario, study_area_config["work_dir"], config)

    assign_emission_rates_to_vehicle_types(scenario, rates, emfac_pop, emfac_vmt, study_area_config["work_dir"], config)

    print(f"  DONE")

    # #################################################################


if __name__ == "__main__":
    run()