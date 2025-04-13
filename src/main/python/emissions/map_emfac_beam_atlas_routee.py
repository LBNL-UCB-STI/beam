import json
import logging
import os
import os.path
import shutil
import sys
from typing import Dict, Any, Optional

import pandas as pd
from joblib import Parallel, delayed

from _emfac_beam_ft_matching import generate_emfac_mapped_freight_fleet
from _emfac_beam_pax_mapping import generate_emfac_mapped_passenger_vehicle_types
from _emfac_beam_pax_mapping import generate_fleet_from_vehicle_types
from _emissions_rates_processing import process_emfac_population
from _emissions_rates_processing import process_emfac_vmt
from _emissions_rates_processing import process_emissions_rates
from _emissions_utils import generate_emfac_beam_class_mapping

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import BeamClasses
from python.utils.study_area_config import get_fuel_key
from python.utils.files_utils import sanitize_name
from python.utils.files_utils import check_files

pd.set_option('display.max_columns', 20)


def create_emfac_id(row):
    model_year_group_st = sanitize_name(row['model_year_group']).replace("_","")
    vehicle_class_st = sanitize_name(row['vehicle_class']).replace("_","")
    fuel_st = sanitize_name(row['fuel']).replace("_","")
    return f"{model_year_group_st}{vehicle_class_st}{fuel_st}"


def categorize_model_year(year, bin_years=None):
    """
    Categorize a model year into bins based on a list of cutoff years.

    Parameters:
    -----------
    year : int or float
        The model year to categorize
    bin_years : list, optional
        A sorted list of cutoff years. Default is [1993, 2006, 2018]
        Each year in the input will be categorized to the nearest bin year
        that is greater than or equal to it.

    Returns:
    --------
    str
        The bin year as a string

    Example:
    --------
    >>> categorize_model_year(2000, [1993, 2006, 2018])
    '2006'
    >>> categorize_model_year(2010, [1993, 2006, 2018])
    '2018'
    >>> categorize_model_year(1990, [1993, 2006, 2018])
    '1993'
    """
    # Default bin years if none provided
    if bin_years is None:
        bin_years = [1993, 2006, 2018]

    # Ensure bin_years is sorted
    bin_years = sorted(bin_years)

    # Handle years before the first bin
    if year <= bin_years[0]:
        return str(bin_years[0])

    # Find the appropriate bin
    for i in range(len(bin_years) - 1):
        if year <= bin_years[i + 1]:
            return str(bin_years[i + 1])

    # If year is greater than all bins, return the last bin
    return str(bin_years[-1])


def prepare_emissions_data_for_mapping(area, scenario, work_dir, config):
    mapping_config = config["mapping"]
    def format_emissions_data(emfac_types: pd.DataFrame) -> pd.DataFrame:
        result_ft_df = emfac_types.copy()
        result_ft_df['mappedClass'] = result_ft_df['vehicle_class'].map(mapping_config["class"]["emfac-ft"])
        result_ft_df.dropna(subset=['mappedClass'], inplace=True)
        result_ft_df['mappedFuel'] = result_ft_df['fuel'].map(mapping_config["fuel"]["emfac-ft"])
        result_ft_df.dropna(subset=['mappedFuel'], inplace=True)

        result_pax_df = emfac_types.copy()
        result_pax_df['mappedClass'] = result_pax_df['vehicle_class'].map(mapping_config["class"]["emfac-pax"])
        result_pax_df.dropna(subset=['mappedClass'], inplace=True)
        result_pax_df['mappedFuel'] = result_pax_df['fuel'].map(mapping_config["fuel"]["emfac-pax"])
        result_pax_df.dropna(subset=['mappedFuel'], inplace=True)

        result_bus_df = emfac_types.copy()
        result_bus_df['mappedClass'] = result_bus_df['vehicle_class'].map(mapping_config["class"]["emfac-bus"])
        result_bus_df.dropna(subset=['mappedClass'], inplace=True)
        result_bus_df['mappedFuel'] = result_bus_df['fuel'].map(mapping_config["fuel"]["emfac-bus"])
        result_bus_df.dropna(subset=['mappedFuel'], inplace=True)

        result_df = pd.concat([result_ft_df, result_pax_df, result_bus_df])

        result_df['model_year_group'] = result_df['model_year'].apply(
            lambda x: categorize_model_year(x, mapping_config["fleet"]["model_year_bins"])
        )
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
    emfac_fleet = pd.merge(emfac_pop, emfac_vmt[["emfacId", "total_vmt", "vmt_proportion"]], on='emfacId', how='left')
    #
    print("\n=== CARB Emissions Rates ===\n")
    emfac_rates = process_emissions_rates(area, scenario, work_dir, config, format_emissions_data)
    print(f"rates: {len(emfac_rates):,}")

    return emfac_fleet, emfac_rates


def assign_emission_rates_to_vehicle_types(scenario, emissions_rates, emfac_fleet, work_dir, config):
    """
    Process freight and passenger vehicle emissions by assigning EMFAC IDs and emissions rates.

    This function:
    1. Builds new freight vehicle types and assigns them to carriers
    2. Creates or loads passenger vehicle types
    3. Assigns emissions rates to all vehicle types

    Args:
        scenario (str): Scenario name
        emissions_rates (DataFrame): DataFrame containing emissions rates
        emfac_fleet (DataFrame): DataFrame containing EMFAC population and VMT data
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
        result_df['mappedFuel'] = result_df['fuel_key'].map(config["mapping"]["fuel"]["beam"])
        na_count = result_df['mappedFuel'].isna().sum()
        if na_count > 0:
            logging.warning(f"{na_count} vehicle types could not be mapped to EMFAC fuel types")
        result_df['mappedClass'] = result_df['vehicleCategory']
        return result_df
    # ######

    print("\n=== Map EMFAC To BEAM Population ===\n")

    # Define output file paths
    carriers_out_file = os.path.join(work_dir, f"{config['beam']['carriers_file'].replace('.csv', '--EM.csv')}")
    ft_vehtypes_out_file = os.path.join(work_dir,
                                        f"{config['beam']['ft_vehicle_types_file'].replace('.csv', '--EM.csv')}")
    pax_vehtypes_out_file = os.path.join(work_dir,
                                         f"{config['beam']['pax_vehicle_types_file'].replace('.csv', '--EM.csv')}")
    emissions_rates_dir = os.path.join(
        os.path.dirname(os.path.join(work_dir, f"{config['beam']['ft_vehicle_types_file']}")),
        f"emissions/{scenario.replace('_', '-')}"
    )


    # Process freight vehicles
    if check_files([carriers_out_file, ft_vehtypes_out_file], config["override_fleet"]):
        logging.info("All carriers and freight vehicle types emissions files have already been created")
        logging.info(f"    carriers: {carriers_out_file}")
        logging.info(f"    freight vehicle types: {ft_vehtypes_out_file}")
        new_ft_vehicle_types = pd.read_csv(ft_vehtypes_out_file)
    else:
        new_carriers, new_ft_vehicle_types = generate_emfac_mapped_freight_fleet(
            emfac_fleet, BeamClasses.get_freight_classes(), work_dir, config, format_beam_vehicle_types
        )
        logging.info(f"Saving updated files to:\n  {carriers_out_file}\n  {ft_vehtypes_out_file}")
        new_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)
        new_carriers.to_csv(carriers_out_file, index=False)

    # Process passenger vehicles
    if check_files([pax_vehtypes_out_file], config["override_fleet"]):
        logging.info("Passenger vehicle types emissions files have already been created:")
        logging.info(f"    passenger vehicle types: {pax_vehtypes_out_file}")
        new_pax_vehicle_types = pd.read_csv(pax_vehtypes_out_file)
        temp = pd.read_csv(os.path.join(work_dir, f"{config['beam']['pax_vehicle_types_file']}"))
        other_pax_vehicle_types = temp[temp["vehicleCategory"].isin(
            BeamClasses.get_freight_classes() + new_pax_vehicle_types["vehicleCategory"].unique().tolist())]
    else:
        # Generate passenger vehicle types
        new_pax_vehicle_types, other_pax_vehicle_types = generate_emfac_mapped_passenger_vehicle_types(
            emfac_fleet,
            car_class=BeamClasses.CLASS_CAR,
            bike_class=BeamClasses.CLASS_BIKE,
            transit_class=BeamClasses.CLASS_MDP,
            filter_out_classes=BeamClasses.get_freight_classes(),
            work_dir=work_dir,
            config=config,
            format_func=format_beam_vehicle_types,
        )

    vehicles_output = os.path.join(work_dir, f"{config['beam']['pax_vehicles_file'].replace('.csv', '--EM.csv')}")
    if not check_files([vehicles_output], config["override_fleet"]):
        pax_vehicles = generate_fleet_from_vehicle_types(
            new_pax_vehicle_types,
            car_class=BeamClasses.CLASS_CAR,
            bike_class=BeamClasses.CLASS_BIKE,
            work_dir=work_dir,
            config=config
        )
        vehicles_output = os.path.join(work_dir, f"{vehicles_output}")
        pax_vehicles.to_csv(vehicles_output, index=False)

    # Prepare for emissions rates processing
    vehtypes_with_emfac_id = pd.concat([new_ft_vehicle_types, new_pax_vehicle_types], ignore_index=True)
    vehtypes_with_emfac_id = vehtypes_with_emfac_id.fillna("")

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
    em_index = path_parts.index("emissions")
    shortened_path = '/'.join(path_parts[em_index:])
    for veh_type_id, emfac_id in results:
        if veh_type_id:
            relative_rates_filepath = f"{shortened_path}/{emfac_id}.csv"
            vehtypes_with_emfac_id.loc[
                vehtypes_with_emfac_id['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'
            ] = relative_rates_filepath

    # Save updated vehicle types
    logging.info(f"Writing:\n{ft_vehtypes_out_file}\n{pax_vehtypes_out_file}")

    # Save freight vehicle types
    ft_freight_mask = (vehtypes_with_emfac_id['vehicleCategory'].isin(BeamClasses.get_freight_classes()))
    updated_ft_vehicle_types = vehtypes_with_emfac_id[ft_freight_mask].copy()
    updated_ft_vehicle_types.drop(['emfacId', 'oldVehicleTypeId', 'vehicleClass'], axis=1, inplace=True)
    updated_ft_vehicle_types.to_csv(ft_vehtypes_out_file, index=False)

    # Save passenger vehicle types
    updated_pax_vehicle_types_others = other_pax_vehicle_types.copy()
    updated_pax_vehicle_types_others['emissionsRatesFile'] = ""
    updated_pax_vehicle_types = pd.concat(
        [vehtypes_with_emfac_id[~ft_freight_mask].copy(), other_pax_vehicle_types],
        axis=0
    )
    updated_pax_vehicle_types.drop(['emfacId', 'oldVehicleTypeId', 'vehicleClass'], axis=1, inplace=True)
    updated_pax_vehicle_types.to_csv(pax_vehtypes_out_file, index=False)


def process_single_vehicle_type(
        veh_type: Dict[str, Any],
        emissions_rates: pd.DataFrame,
        rates_prefix_filepath: str
) -> Optional[tuple[str, str]]:
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
        emfac_id = veh_type['emfacId']

        # Filter emissions_rates for the current vehicle type
        veh_emissions = emissions_rates[emissions_rates['emfacId'] == emfac_id].copy()

        if veh_emissions.empty:
            logging.warning(f"No emissions data found for vehicle type {veh_type_id}")
            return None

        # Generate the file path
        file_path = f"{rates_prefix_filepath}{emfac_id}.csv"

        # Save the emissions rates to a CSV file only if it doesn't exist
        if not os.path.exists(file_path):
            print(f"Writing emissions data to {file_path}")
            logging.info(f"Writing emissions data to {file_path}")
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            veh_emissions.to_csv(file_path, index=False)
            print(f"Created new file: {file_path}")
        else:
            print(f"File already generated: {file_path}")

        return veh_type_id, emfac_id

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
    run_batch = "20240123"
    scenario = "2018-Baseline"
    study_area_config = get_area_config(area)
    config = study_area_config["emissions"][scenario]
    config["run"]["output_dir"] = f"emissions/{run_batch}"
    beam_config = config["beam"]
    beam_config["carriers_file"] = f"beam-ft/{run_batch}/{scenario}/carriers--{scenario}.csv"
    beam_config["payloads_file"] = f"beam-ft/{run_batch}/{scenario}/payloads--{scenario}.csv"
    beam_config["ft_vehicle_types_file"] = f"vehicle-tech/vehicleTypes--frism--{scenario}.csv"
    beam_config["pax_vehicle_types_file"] = f"vehicle-tech/vehicleTypes--atlas--2017-Baseline.csv"
    beam_config["pax_vehicles_file"] = f"beam-pax/vehicles--atlas--2017-Baseline.csv.gz"

    emfac_pop_by_model_year_file = config["rates"]["emfac"]["emfac_pop_by_model_year_file"]
    vehicle_class_output_file = f"{config["run"]["output_dir"]}/{area}_vehicle_class_mapping_{scenario}.json"
    emfac_class_map = generate_emfac_beam_class_mapping(
        emfac_pop_by_model_year_file = os.path.join(study_area_config["work_dir"], emfac_pop_by_model_year_file),
        vehicle_class_output_file = os.path.join(study_area_config["work_dir"], vehicle_class_output_file),
        to_filter_out=[BeamClasses.CLASS_2B3_VOCATIONAL]
    )

    config["mapping"]["class"]["emfac"] = emfac_class_map
    # Write Config file to keep track of runs
    # Write it onl after all modification to config are completed
    emissions_work_dir = os.path.join(study_area_config["work_dir"], config["run"]["emissions_dir"])
    os.makedirs(emissions_work_dir, exist_ok=True)
    with open(os.path.join(study_area_config["work_dir"], f"{emissions_work_dir}/{area}_emissions_config_{scenario}.json"), 'w') as f:
        json.dump(study_area_config, f, indent=2)

    # #################################################################

    print(f"\n{'='*50}")
    print(f"  EMISSIONS PROCESSING - {area.upper()} REGION")
    print(f"  Run Batch: {run_batch}")
    print(f"  Scenario: {scenario}")
    print(f"{'='*50}\n")

    work_dir = study_area_config["work_dir"]
    emfac_fleet, emfac_rates = prepare_emissions_data_for_mapping(area, scenario, work_dir, config)
    assign_emission_rates_to_vehicle_types(scenario, emfac_rates, emfac_fleet, work_dir, config)
    print(f"  DONE")

    # #################################################################


if __name__ == "__main__":
    run()