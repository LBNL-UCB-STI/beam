import os.path
import shutil
import logging
import pandas as pd
import sys
import os
from typing import Dict, Any, List, Optional, Tuple
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

pd.set_option('display.max_columns', 20)


def format_vehicle_types_for_emfac_mapping(vehicle_types: pd.DataFrame,
                                           fuel_map: Dict[str, str]) -> pd.DataFrame:
    """
    Prepare vehicle types for EMFAC mapping by standardizing class and fuel information.

    This function takes a DataFrame of vehicle types and performs the following:
    1. Derives fuel keys from existing vehicle records using get_fuel_key
    2. Maps these fuel keys to standard EMFAC fuel types using the provided fuel_map
    3. Sets the beamClass equal to vehicleCategory for consistency
    4. Returns a simplified DataFrame with only the essential columns for mapping

    Args:
        vehicle_types (pandas.DataFrame): DataFrame containing vehicle type records
            with required columns for fuel key calculation
        fuel_map (dict): Mapping dictionary that converts fuel keys to EMFAC fuel types

    Returns:
        pandas.DataFrame: Simplified DataFrame with columns 'vehicleTypeId', 'beamClass',
            and 'emfacFuel', ready for EMFAC mapping

    Raises:
        ValueError: If required columns are missing from vehicle_types DataFrame
    """
    # Validate inputs
    required_columns = ['vehicleTypeId', 'vehicleCategory']
    missing_columns = [col for col in required_columns if col not in vehicle_types.columns]
    if missing_columns:
        raise ValueError(f"Missing required columns in vehicle_types DataFrame: {missing_columns}")

    # Create a copy to avoid modifying the input DataFrame
    result_df = vehicle_types.copy()

    # Apply fuel key mapping
    result_df['fuel_key'] = result_df.apply(get_fuel_key, axis=1)
    result_df['emfacFuel'] = result_df['fuel_key'].map(fuel_map)

    # Check for NA values in emfacFuel
    na_count = result_df['emfacFuel'].isna().sum()
    if na_count > 0:
        logging.warning(f"{na_count} vehicle types could not be mapped to EMFAC fuel types")

    # Set beamClass from vehicleCategory
    result_df['beamClass'] = result_df['vehicleCategory']

    # Return only the essential columns
    return result_df[['vehicleTypeId', 'beamClass', 'emfacFuel']].copy()


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
            emfac_vmt, BeamClasses.get_freight_classes(), format_vehicle_types_for_emfac_mapping, work_dir, config
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
            format_func=format_vehicle_types_for_emfac_mapping,
            work_dir=work_dir,
            config=config
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
    assign_emission_rates_to_vehicle_types(scenario, rates, emfac_pop, emfac_vmt, study_area_config["work_dir"], config)

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