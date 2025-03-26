import math
import os.path
import random
import shutil
import numpy as np

# from _emfac_emissions_mapping import *
from _emfac_and_emissions_rates_processing import *
from _emfac_beam_ft_matching import generate_emfac_mapped_freight_fleet

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import BeamClasses
from python.utils.study_area_config import get_fuel_key

pd.set_option('display.max_columns', 20)


def format_vehicle_types_for_emfac_mapping(vehicle_types, fuel_map):
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

    Note:
        This function prints a warning if any vehicles have NA values in the
        emfacFuel column after mapping
    """
    vehicle_types['fuel_key'] = vehicle_types.apply(get_fuel_key, axis=1)
    vehicle_types['emfacFuel'] = vehicle_types['fuel_key'].map(fuel_map)

    # Check for NA values in emfacFuel
    na_count = vehicle_types['emfacFuel'].isna().sum()
    if na_count > 0:
        print(f"Warning: {na_count} NA values in emfacFuel")

    vehicle_types['beamClass'] = vehicle_types['vehicleCategory']
    return vehicle_types[['vehicleTypeId', 'beamClass', 'emfacFuel']].copy()

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

    if os.path.exists(carriers_out_file) and os.path.exists(ft_vehtypes_out_file):
        print("All carriers and freight vehicle types emissions files have already been created:")
        print(f"    carriers: {carriers_out_file}")
        print(f"    freight vehicle types: {ft_vehtypes_out_file}")
    else:
        new_carriers, new_ft_vehicle_types = generate_emfac_mapped_freight_fleet(
            _emfac_vmt, _work_dir, _config, BeamClasses.get_freight_classes(), format_vehicle_types_for_emfac_mapping
        )

    if os.path.exists(pax_vehtypes_out_file):
        print("Passenger vehicle types emissions files have already been created:")
        print(f"    passenger vehicle types: {pax_vehtypes_out_file}")
    else:
        # ## Passenger ## #
        _emfac_pop_for_pax = _emfac_pop[_emfac_pop["beamClass"].isin(BeamClasses.get_passenger_classes())]
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
        ft_freight_mask = (vehtypes_with_emfac_id['vehicleCategory'].isin(BeamClasses.get_freight_classes()))
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