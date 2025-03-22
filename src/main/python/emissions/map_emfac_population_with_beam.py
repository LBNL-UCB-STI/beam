from _emfac_emissions_mapping import *
import pandas as pd
import pyarrow as pa
import pyarrow.csv as csv
import os
import re
import sys

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name

pd.set_option('display.max_columns', 20)

# Constants for fuel mapping
FT_FUEL_MAPPING_ASSUMPTIONS = {
    'Dsl': 'Diesel',
    'Gas': 'Diesel',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity'
}

PAX_FUEL_MAPPING_ASSUMPTIONS = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity',
    'BioDsl': 'Diesel'
}


def process_freight_mapping(_emfac_year, _filtered_rates, _emfac_population, _ft_carriers, _ft_payloads,
                            _ft_vehicle_types, _ft_carriers_emissions_file, _ft_filtered_out_emissions_file,
                            _ft_vehicle_types_emissions_file, _ft_emissions_rates_relative_filepath, _input_dir):
    """
    Process EMFAC mapping for freight vehicles

    Args:
        _emfac_year: The EMFAC year to use
        _filtered_rates: Filtered emissions rates
        _emfac_population: EMFAC population data
        _ft_carriers: Freight carriers data
        _ft_payloads: Freight payloads data
        _ft_vehicle_types: Freight vehicle types data
        _ft_carriers_emissions_file: Output path for carriers emissions
        _ft_filtered_out_emissions_file: Output path for filtered out emissions
        _ft_vehicle_types_emissions_file: Output path for vehicle types emissions
        _ft_emissions_rates_relative_filepath: Relative filepath for emissions rates
        _input_dir: Input directory for data files
    """
    print("\nMapping EMFAC for freight!")

    # Create vehicle class mapping
    _, ft_emfac_class_map = create_vehicle_class_mapping(_emfac_population["vehicle_class"].unique())

    # Prepare EMFAC rates
    ft_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
        _filtered_rates,
        ft_emfac_class_map
    )
    print(f"EMFAC Freight Rates => rows: {len(ft_emissions_rates_for_mapping)}, "
          f"classes: {len(ft_emissions_rates_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(ft_emissions_rates_for_mapping['emfacFuel'].unique())}")

    # Prepare EMFAC population
    ft_emfac_pop_for_mapping = prepare_emfac_population_for_mapping(
        _emfac_population,
        _emfac_year,
        ft_emfac_class_map,
        FT_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"EMFAC Freight Population => rows: {len(ft_emfac_pop_for_mapping)}, "
          f"classes: {len(ft_emfac_pop_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(ft_emfac_pop_for_mapping['emfacFuel'].unique())}")

    # Prepare freight vehicle population
    ft_population_for_mapping = prepare_ft_vehicle_population_for_mapping(
        _ft_carriers,
        _ft_payloads,
        _ft_vehicle_types,
        FT_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"BEAM Freight Population => rows: {len(ft_population_for_mapping)}, "
          f"classes: {len(ft_population_for_mapping['beamClass'].unique())}, "
          f"fuel: {len(ft_population_for_mapping['beamFuel'].unique())}")

    # Check for unmapped vehicles
    unique_vehicles = set(_ft_carriers["vehicleId"].unique()) - set(ft_population_for_mapping["vehicleId"].unique())
    if len(unique_vehicles) > 0:
        print(f"Failed to map, maybe some vehicles in carriers were not used in payload plans:")
        print(unique_vehicles)

    # Distribute vehicle classes
    print("------------------------------------------------------------------")
    print("Distributing freight vehicle classes from EMFAC across BEAM population...")
    updated_freight_population = distribution_based_vehicle_classes_assignment(
        ft_population_for_mapping,
        ft_emfac_pop_for_mapping
    )

    # Check for missing classes
    missing_classes = set(ft_emfac_pop_for_mapping['emfacClass'].unique()) - set(
        updated_freight_population['emfacClass'].unique())
    missing_fuel = set(ft_emfac_pop_for_mapping['emfacFuel'].unique()) - set(
        updated_freight_population['emfacFuel'].unique())
    if len(missing_classes) > 0 or len(missing_fuel) > 0:
        print(f"Failed to match these classes {missing_classes} and fuel {missing_fuel}")

    # Build new vehicle types
    print("------------------------------------------------------------------")
    print("Building new set of freight vehicle types")
    updated_vehicle_types = build_new_ft_vehtypes(updated_freight_population, _ft_vehicle_types)
    print(
        f"Previous vehicle types had {len(_ft_vehicle_types)} types while the new set has {len(updated_vehicle_types)} types")

    # Assign new vehicle types to carriers
    print("------------------------------------------------------------------")
    print("Assigning new freight vehicle types to carriers")
    updated_carriers = assign_new_ft_vehtypes_to_carriers(_ft_carriers, updated_freight_population,
                                                          _ft_carriers_emissions_file)

    # Check for unassigned vehicles
    unique_vehicles = set(_ft_carriers["vehicleId"].unique()) - set(updated_carriers["vehicleId"].unique())
    if len(unique_vehicles) > 0:
        print(f"Failed to assign vehicle types to these vehicles: {unique_vehicles}")

    # Format EMFAC rates
    print("------------------------------------------------------------------")
    print("Formatting EMFAC freight rates for BEAM")
    ft_emfac_formatted, ft_emfac_filtered_out = format_rates_for_beam(ft_emissions_rates_for_mapping)
    ft_emfac_filtered_out.to_csv(_ft_filtered_out_emissions_file)
    print(
        f"Filtered out freight processes with all zeros emissions, verify output here => {_ft_filtered_out_emissions_file}")

    # Assign emissions rates
    print("------------------------------------------------------------------")
    print("Assigning freight emissions rates to new set of vehicle types")
    ft_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
        ft_emfac_formatted,
        updated_vehicle_types,
        _input_dir + "/vehicle-tech",
        _ft_emissions_rates_relative_filepath
    )

    # Check for types without emissions rates
    print("------------------------------------------------------------------")
    unique_ft_vehicle_types = set(updated_vehicle_types["vehicleTypeId"].unique()) - set(
        ft_vehicle_types_with_emissions_rates["vehicleTypeId"].unique())
    if len(unique_ft_vehicle_types) > 0:
        print(f"Failed to assign emissions rates to these vehicle types: {unique_ft_vehicle_types}")

    # Save updated vehicle types
    print(f"Writing {_ft_vehicle_types_emissions_file}")
    updated_vehicle_types.to_csv(_ft_vehicle_types_emissions_file, index=False)


def process_passenger_mapping(_emfac_year, _filtered_rates, _emfac_population, _pax_vehicle_types,
                              _pax_filtered_out_emissions_file, _pax_vehicle_types_emissions_file,
                              _pax_emissions_rates_relative_filepath, _input_dir):
    """
    Process EMFAC mapping for passenger vehicles

    Args:
        _emfac_year: The EMFAC year to use
        _filtered_rates: Filtered emissions rates
        _emfac_population: EMFAC population data
        _pax_vehicle_types: Passenger vehicle types data
        _pax_filtered_out_emissions_file: Output path for filtered out emissions
        _pax_vehicle_types_emissions_file: Output path for vehicle types emissions
        _pax_emissions_rates_relative_filepath: Relative filepath for emissions rates
        _input_dir: Input directory for data files
    """
    print("\nMapping EMFAC for passengers!")

    # Create vehicle class mapping
    pax_emfac_class_map, _ = create_vehicle_class_mapping(_emfac_population["vehicle_class"].unique())

    # Prepare EMFAC rates
    pax_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
        _filtered_rates,
        pax_emfac_class_map
    )
    print(f"EMFAC Passenger Rates => rows: {len(pax_emissions_rates_for_mapping)}, "
          f"classes: {len(pax_emissions_rates_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(pax_emissions_rates_for_mapping['emfacFuel'].unique())}")

    # Prepare EMFAC population
    emfac_passenger_population_for_mapping = prepare_emfac_population_for_mapping(
        _emfac_population,
        _emfac_year,
        pax_emfac_class_map,
        PAX_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"EMFAC Passenger Population => rows: {len(emfac_passenger_population_for_mapping)}, "
          f"classes: {len(emfac_passenger_population_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(emfac_passenger_population_for_mapping['emfacFuel'].unique())}")

    # Prepare passenger population
    pax_population_for_mapping = prepare_pax_vehicle_population_for_mapping(
        _pax_vehicle_types,
        PAX_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"BEAM Passenger Population => rows: {len(pax_population_for_mapping)}, "
          f"classes: {len(pax_population_for_mapping['beamClass'].unique())}, "
          f"fuel: {len(pax_population_for_mapping['beamFuel'].unique())}")

    # Build new vehicle types
    print("------------------------------------------------------------------")
    print("Distributing passenger vehicle classes from EMFAC across BEAM population...")
    updated_passenger_vehicle_types = build_new_pax_vehtypes(
        emfac_passenger_population_for_mapping,
        pax_population_for_mapping
    )
    print(f"Previous vehicle types had {len(pax_population_for_mapping)} types "
          f"while the new set has {len(updated_passenger_vehicle_types)} types")

    # Format EMFAC rates
    print("------------------------------------------------------------------")
    print("Formatting Passenger EMFAC rates for BEAM")
    pax_emfac_formatted, pax_emfac_filtered_out = format_rates_for_beam(pax_emissions_rates_for_mapping)
    pax_emfac_filtered_out.to_csv(_pax_filtered_out_emissions_file)
    print(
        f"Filtered out passenger processes with all zeros emissions, verify output here => {_pax_filtered_out_emissions_file}")

    # Assign emissions rates
    print("------------------------------------------------------------------")
    print("Assigning Passenger emissions rates to new set of vehicle types")
    pax_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
        pax_emfac_formatted,
        updated_passenger_vehicle_types,
        _input_dir + "/vehicle-tech",
        _pax_emissions_rates_relative_filepath
    )

    # Add back unmapped vehicle types
    print("------------------------------------------------------------------")
    print("Adding back Passenger vehicle types not mapped with EMFAC")
    index_population = set(pax_population_for_mapping.index)
    index_vehicle_types = set(_pax_vehicle_types.index)
    missing_rows = index_vehicle_types - index_population
    missing_df = _pax_vehicle_types.loc[list(missing_rows)]
    missing_df["emissionsRatesFile"] = ""
    pax_emfac_vehicletypes = pd.concat([pax_vehicle_types_with_emissions_rates[missing_df.columns], missing_df], axis=0)
    pax_emfac_vehicletypes.to_csv(_pax_vehicle_types_emissions_file, index=False)

    print("Done mapping EMFAC for passengers!")


def combine_csv_files(input_files, output_file):
    # Read and combine CSV files vertically
    combined_df = pd.concat([pd.read_csv(f) for f in input_files], ignore_index=True)

    def categorize_model_year(year):
        if year <= 1993:
            return 'MY<=1993'
        elif 1994 <= year <= 1999:
            return '1994-1999'
        elif 2000 <= year <= 2003:
            return '2000-2003'
        elif 2004 <= year <= 2006:
            return '2004-2006'
        elif 2007 <= year <= 2009:
            return '2007-2009'
        elif 2010 <= year <= 2013:
            return '2010-2013'
        elif 2014 <= year <= 2015:
            return '2014-2015'
        else:  # year >= 2016
            return 'MY>=2016'

    # Create a new column with the categorized model years
    combined_df['model_year_group'] = combined_df['model_year'].apply(categorize_model_year)

    # Write the combined dataframe to a new CSV file
    combined_df.to_csv(output_file, index=False)

    print(f"Combined CSV file has been created: {output_file}")
    return combined_df  # Return the dataframe for further processing if needed

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

if __name__ == "__main__":
    # Configuration parameters
    area = "sfbay"
    study_area_config = get_area_config(area)

    # Scenario parameters
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "HOPhighp2", 2045, "LowTech"
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2018, 2018, "Baseline", 2018, "Baseline"
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "Refhighp6", 2045, "LowTech"
    run_config = {
        "emfac_year": 2018,

        "run_batch": "2024-01-23",

        "ft_scenario": "2018_Baseline",
        "run_ft": True, # Run Freight Emissions Mapping

        "pax_scenario": "2018_Baseline",
        "run_pax": False # Run Passenger Emissions Mapping
    }

    ft_scenario_label = run_config["ft_scenario"].replace("_", "-")
    pax_scenario_label = run_config["pax_scenario"].replace("_", "-")
    work_dir = study_area_config["work_dir"]

    # ### Input directories and files ### #
    # Emissions
    emfac_pop_file = f"{work_dir}/emissions/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_population_20240612233346.csv"
    emfac_vmt_file = f"{work_dir}/emissions/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_vmt_20240612233346.csv"
    emfac_rates_file = f"{work_dir}/emissions/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv"
    emfac_rates_by_model_year_file = f"{work_dir}/emissions/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050_byMY.csv"
    black_carbon_rates_file = f"{work_dir}/emissions/black_carbon/emfac_bc_rate_three_ver_2018.csv"
    road_dust_pm_rates_file = f"{work_dir}/emissions/road_dust/carb_road_dust_rate_2018.csv"
    # Freight Population
    ft_plans_dir = f"{work_dir}/beam-ft/{run_config["run_batch"]}"
    carriers_file = f"{ft_plans_dir}/{run_config["ft_scenario"]}/carriers--{ft_scenario_label}.csv"
    payloads_file = f"{ft_plans_dir}/{run_config["ft_scenario"]}/payloads--{ft_scenario_label}.csv"
    ft_vehicle_types_file = f"{ft_plans_dir}/vehicle-tech/ft-vehicletypes--{ft_scenario_label}.csv"
    # Passenger Population
    pax_plans_dir = f"{work_dir}/beam-pax/{run_config["run_batch"]}"
    pax_vehicle_types_file = f"{pax_plans_dir}/vehicle-tech/pax-vehicletypes--{pax_scenario_label}.csv"

    # ### Output directories and files ### #
    # Freight Population
    ft_filtered_out_emissions_file = f"{ft_plans_dir}/vehicle-tech/ft-filtered-out--{ft_scenario_label}-TrAP.csv"
    ft_vehicle_types_emissions_file = f"{ft_plans_dir}/vehicle-tech/ft-vehicletypes--{ft_scenario_label}-TrAP.csv"
    ft_carriers_emissions_file = f"{ft_plans_dir}/{run_config["ft_scenario"]}/carriers--{ft_scenario_label}-TrAP.csv"
    ft_emissions_rates_relative_filepath = f"TrAP/FT-{str(ft_scenario_label)}"
    # Passenger Population
    pax_filtered_out_emissions_file = f"{pax_plans_dir}/vehicle-tech/pax-filtered-out-TrAP.csv"
    pax_vehicle_types_emissions_file = f"{pax_plans_dir}/vehicle-tech/pax-vehicletypes--{pax_scenario_label}-TrAP.csv"
    pax_emissions_rates_relative_filepath = f"TrAP/PAX-{str(pax_scenario_label)}"

    # ### Prep emissions rates ### #
    if not os.path.exists(emfac_rates_by_model_year_file):
        if os.path.exists(emfac_rates_file):
            table = csv.read_csv(emfac_rates_file, read_options=pa.csv.ReadOptions(use_threads=True))
            df = table.to_pandas()
            group_col = ['calendar_year', 'season_month', 'sub_area', 'vehicle_class', 'fuel', 'temperature',
                         'relative_humidity', 'process', 'speed_time', 'pollutant', 'MY_group']
            # Group by MY_group and calculate statistics
            emission_rates = df.groupby(group_col).agg({'emission_rate': 'mean'})
        else:
            print(f"Error: Emissions rates file '{emfac_rates_file}' not found.")
            sys.exit(1)
    else:
        table = csv.read_csv(emfac_rates_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        emission_rates = table.to_pandas()



    # Load common data
    emfac_population = pd.read_csv(emfac_population_file, low_memory=False, dtype=str)
    emfac_population['population'] = pd.to_numeric(emfac_population['population'], errors='coerce')

    emissions_rates = pd.read_csv(emfac_emissions_file, low_memory=False, dtype={
        'calendar_year': int,
        'season_month': str,
        'sub_area': str,
        'vehicle_class': str,
        'fuel': str,
        'temperature': float,
        'relative_humidity': float,
        'process': str,
        'speed_time': float,
        'pollutant': str,
        'emission_rate': float
    })

    # Filter rates for the specific area and year
    filtered_rates = emissions_rates[
        emissions_rates["sub_area"].str.contains(fr"\({re.escape(region_to_carb_area[area])}\)", case=False, na=False) &
        (emissions_rates["calendar_year"] == emfac_year)
        ]

    # Process passenger mapping if enabled
    if run_config["run_pax"]:
        # Print scenario info
        print(f"Scenario {area}, {str(ft_year)}-{ft_scenario} from {ft_iteration}..")
        pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
        process_passenger_mapping(
            emfac_year, filtered_rates, emfac_population,
            pax_vehicle_types,
            pax_filtered_out_emissions_file, pax_vehicle_types_emissions_file,
            pax_emissions_rates_relative_filepath, input_dir
        )

    # Process freight mapping if enabled
    if run_config["run_ft"]:
        ft_payloads = pd.read_csv(payloads_file)
        ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
        ft_carriers = pd.read_csv(carriers_file, dtype=str)
        process_freight_mapping(
            emfac_year, filtered_rates, emfac_population,
            ft_carriers, ft_payloads, ft_vehicle_types,
            ft_carriers_emissions_file, ft_filtered_out_emissions_file,
            ft_vehicle_types_emissions_file, ft_emissions_rates_relative_filepath,
            input_dir
        )

    print("End")