from _emfac_emissions_mapping import *
import pandas as pd
import os
import re

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


def process_freight_mapping(
        emfac_year, filtered_rates, emfac_population,
        ft_carriers, ft_payloads, ft_vehicle_types,
        ft_carriers_emissions_file, ft_filtered_out_emissions_file,
        ft_vehicle_types_emissions_file, ft_emissions_rates_relative_filepath,
        input_dir):
    """
    Process EMFAC mapping for freight vehicles

    Args:
        emfac_year: The EMFAC year to use
        filtered_rates: Filtered emissions rates
        emfac_population: EMFAC population data
        ft_carriers: Freight carriers data
        ft_payloads: Freight payloads data
        ft_vehicle_types: Freight vehicle types data
        ft_carriers_emissions_file: Output path for carriers emissions
        ft_filtered_out_emissions_file: Output path for filtered out emissions
        ft_vehicle_types_emissions_file: Output path for vehicle types emissions
        ft_emissions_rates_relative_filepath: Relative filepath for emissions rates
        input_dir: Input directory for data files
    """
    print("\nMapping EMFAC for freight!")

    # Create vehicle class mapping
    _, ft_emfac_class_map = create_vehicle_class_mapping(emfac_population["vehicle_class"].unique())

    # Prepare EMFAC rates
    ft_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
        filtered_rates,
        ft_emfac_class_map
    )
    print(f"EMFAC Freight Rates => rows: {len(ft_emissions_rates_for_mapping)}, "
          f"classes: {len(ft_emissions_rates_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(ft_emissions_rates_for_mapping['emfacFuel'].unique())}")

    # Prepare EMFAC population
    ft_emfac_pop_for_mapping = prepare_emfac_population_for_mapping(
        emfac_population,
        emfac_year,
        ft_emfac_class_map,
        FT_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"EMFAC Freight Population => rows: {len(ft_emfac_pop_for_mapping)}, "
          f"classes: {len(ft_emfac_pop_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(ft_emfac_pop_for_mapping['emfacFuel'].unique())}")

    # Prepare freight vehicle population
    ft_population_for_mapping = prepare_ft_vehicle_population_for_mapping(
        ft_carriers,
        ft_payloads,
        ft_vehicle_types,
        FT_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"BEAM Freight Population => rows: {len(ft_population_for_mapping)}, "
          f"classes: {len(ft_population_for_mapping['beamClass'].unique())}, "
          f"fuel: {len(ft_population_for_mapping['beamFuel'].unique())}")

    # Check for unmapped vehicles
    unique_vehicles = set(ft_carriers["vehicleId"].unique()) - set(ft_population_for_mapping["vehicleId"].unique())
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
    updated_vehicle_types = build_new_ft_vehtypes(updated_freight_population, ft_vehicle_types)
    print(
        f"Previous vehicle types had {len(ft_vehicle_types)} types while the new set has {len(updated_vehicle_types)} types")

    # Assign new vehicle types to carriers
    print("------------------------------------------------------------------")
    print("Assigning new freight vehicle types to carriers")
    updated_carriers = assign_new_ft_vehtypes_to_carriers(ft_carriers, updated_freight_population,
                                                          ft_carriers_emissions_file)

    # Check for unassigned vehicles
    unique_vehicles = set(ft_carriers["vehicleId"].unique()) - set(updated_carriers["vehicleId"].unique())
    if len(unique_vehicles) > 0:
        print(f"Failed to assign vehicle types to these vehicles: {unique_vehicles}")

    # Format EMFAC rates
    print("------------------------------------------------------------------")
    print("Formatting EMFAC freight rates for BEAM")
    ft_emfac_formatted, ft_emfac_filtered_out = format_rates_for_beam(ft_emissions_rates_for_mapping)
    ft_emfac_filtered_out.to_csv(ft_filtered_out_emissions_file)
    print(
        f"Filtered out freight processes with all zeros emissions, verify output here => {ft_filtered_out_emissions_file}")

    # Assign emissions rates
    print("------------------------------------------------------------------")
    print("Assigning freight emissions rates to new set of vehicle types")
    ft_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
        ft_emfac_formatted,
        updated_vehicle_types,
        input_dir + "/vehicle-tech",
        ft_emissions_rates_relative_filepath
    )

    # Check for types without emissions rates
    print("------------------------------------------------------------------")
    unique_ft_vehicle_types = set(updated_vehicle_types["vehicleTypeId"].unique()) - set(
        ft_vehicle_types_with_emissions_rates["vehicleTypeId"].unique())
    if len(unique_ft_vehicle_types) > 0:
        print(f"Failed to assign emissions rates to these vehicle types: {unique_ft_vehicle_types}")

    # Save updated vehicle types
    print(f"Writing {ft_vehicle_types_emissions_file}")
    updated_vehicle_types.to_csv(ft_vehicle_types_emissions_file, index=False)


def process_passenger_mapping(
        emfac_year, filtered_rates, emfac_population,
        pax_vehicle_types,
        pax_filtered_out_emissions_file, pax_vehicle_types_emissions_file,
        pax_emissions_rates_relative_filepath, input_dir):
    """
    Process EMFAC mapping for passenger vehicles

    Args:
        emfac_year: The EMFAC year to use
        filtered_rates: Filtered emissions rates
        emfac_population: EMFAC population data
        pax_vehicle_types: Passenger vehicle types data
        pax_filtered_out_emissions_file: Output path for filtered out emissions
        pax_vehicle_types_emissions_file: Output path for vehicle types emissions
        pax_emissions_rates_relative_filepath: Relative filepath for emissions rates
        input_dir: Input directory for data files
    """
    print("\nMapping EMFAC for passengers!")

    # Create vehicle class mapping
    pax_emfac_class_map, _ = create_vehicle_class_mapping(emfac_population["vehicle_class"].unique())

    # Prepare EMFAC rates
    pax_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
        filtered_rates,
        pax_emfac_class_map
    )
    print(f"EMFAC Passenger Rates => rows: {len(pax_emissions_rates_for_mapping)}, "
          f"classes: {len(pax_emissions_rates_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(pax_emissions_rates_for_mapping['emfacFuel'].unique())}")

    # Prepare EMFAC population
    emfac_passenger_population_for_mapping = prepare_emfac_population_for_mapping(
        emfac_population,
        emfac_year,
        pax_emfac_class_map,
        PAX_FUEL_MAPPING_ASSUMPTIONS
    )
    print(f"EMFAC Passenger Population => rows: {len(emfac_passenger_population_for_mapping)}, "
          f"classes: {len(emfac_passenger_population_for_mapping['emfacClass'].unique())}, "
          f"fuel: {len(emfac_passenger_population_for_mapping['emfacFuel'].unique())}")

    # Prepare passenger population
    pax_population_for_mapping = prepare_pax_vehicle_population_for_mapping(
        pax_vehicle_types,
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
    pax_emfac_filtered_out.to_csv(pax_filtered_out_emissions_file)
    print(
        f"Filtered out passenger processes with all zeros emissions, verify output here => {pax_filtered_out_emissions_file}")

    # Assign emissions rates
    print("------------------------------------------------------------------")
    print("Assigning Passenger emissions rates to new set of vehicle types")
    pax_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
        pax_emfac_formatted,
        updated_passenger_vehicle_types,
        input_dir + "/vehicle-tech",
        pax_emissions_rates_relative_filepath
    )

    # Add back unmapped vehicle types
    print("------------------------------------------------------------------")
    print("Adding back Passenger vehicle types not mapped with EMFAC")
    index_population = set(pax_population_for_mapping.index)
    index_vehicle_types = set(pax_vehicle_types.index)
    missing_rows = index_vehicle_types - index_population
    missing_df = pax_vehicle_types.loc[list(missing_rows)]
    missing_df["emissionsRatesFile"] = ""
    pax_emfac_vehicletypes = pd.concat([pax_vehicle_types_with_emissions_rates[missing_df.columns], missing_df], axis=0)
    pax_emfac_vehicletypes.to_csv(pax_vehicle_types_emissions_file, index=False)

    print("Done mapping EMFAC for passengers!")

# def combine_csv_files(input_files, output_file):
#     # Read and combine CSV files vertically
#     combined_df = pd.concat([pd.read_csv(f) for f in input_files], ignore_index=True)
#
#     # Write the combined dataframe to a new CSV file
#     combined_df.to_csv(output_file, index=False)
#
#     print(f"Combined CSV file has been created: {output_file}")
#     return combined_df  # Return the dataframe for further processing if needed


if __name__ == "__main__":
    # Configuration parameters
    area = "sfbay"
    ft_iteration = "2024-01-23"
    runFT = True  # Run Freight Emissions Mapping
    runPAX = False  # Run Passenger Emissions Mapping

    # Scenario parameters
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "HOPhighp2", 2045, "LowTech"
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2018, 2018, "Baseline", 2018, "Baseline"
    # emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "Refhighp6", 2045, "LowTech"
    emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "HOPhighp6", 2045, "LowTech"

    # File paths
    emfac_population_file = os.path.expanduser(
        '~/Workspace/Models/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_population_20240612233346.csv')
    emfac_emissions_file = os.path.expanduser(
        '~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv')

    # Input directories and files
    input_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{ft_iteration}")
    carriers_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/carriers--{str(ft_year)}-{ft_scenario}.csv"
    payloads_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/payloads--{str(ft_year)}-{ft_scenario}.csv"
    ft_vehicle_types_file = f"{input_dir}/vehicle-tech/ft-vehicletypes--{str(ft_year)}-{ft_scenario}.csv"
    pax_vehicle_types_file = f"{input_dir}/vehicle-tech/pax-vehicletypes--{str(pax_year)}-{pax_scenario}.csv"

    # Output files
    ft_filtered_out_emissions_file = f"{input_dir}/vehicle-tech/ft-filtered-out--{str(ft_year)}-{ft_scenario}-TrAP.csv"
    ft_vehicle_types_emissions_file = f"{input_dir}/vehicle-tech/ft-vehicletypes--{str(ft_year)}-{ft_scenario}-TrAP.csv"
    ft_carriers_emissions_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/carriers--{str(ft_year)}-{ft_scenario}-TrAP.csv"
    ft_emissions_rates_relative_filepath = f"TrAP/{str(ft_year)}-FT-{ft_scenario}"

    pax_filtered_out_emissions_file = f"{input_dir}/vehicle-tech/pax-filtered-out-TrAP.csv"
    pax_vehicle_types_emissions_file = f"{input_dir}/vehicle-tech/pax-vehicletypes--{str(pax_year)}-{pax_scenario}-TrAP.csv"
    pax_emissions_rates_relative_filepath = f"TrAP/{str(pax_year)}-Pax-{pax_scenario}"

    # Print scenario info
    print(f"Scenario {area}, {str(ft_year)}-{ft_scenario} from {ft_iteration}..")

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
    if runPAX:
        pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
        process_passenger_mapping(
            emfac_year, filtered_rates, emfac_population,
            pax_vehicle_types,
            pax_filtered_out_emissions_file, pax_vehicle_types_emissions_file,
            pax_emissions_rates_relative_filepath, input_dir
        )

    # Process freight mapping if enabled
    if runFT:
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

    # combine_csv_files(
    # [
    #     os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018.csv'),
    # os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2025.csv'),
    # os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2030.csv'),
    # os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2040.csv'),
    # os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2050.csv')
    # ]
    # , emfac_emissions_file)
    print("End")