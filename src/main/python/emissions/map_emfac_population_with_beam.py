import os
import sys
import math
import pandas as pd

from generate_california_emissions_rates import *
from _emfac_emissions_mapping import *

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config

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


def process_emfac_vmt(study_area, scenario_name, config, work_dir):
    """
    Process EMFAC VMT data by model year, adding proportional calculations.

    Args:
        config: Configuration dictionary containing filtering and file path information

    Returns:
        pandas.DataFrame: Processed and grouped VMT data with proportion calculations
    """
    _emfac_vmt_output_file = os.path.join(
        work_dir,
        f"emissions/{study_area}_emfac_vmt_{scenario_name}.csv"
    )
    if os.path.exists(_emfac_vmt_output_file):
        _emfac_vmt = pd.read_csv(_emfac_vmt_output_file)
    else:
        include_nan = config["filters"]["include_nan"]
        calendar_year = config["filters"]["calendar_year"]
        air_basin_area = config["filters"]["sub_area"]
        _emfac_vmt_by_model_year_file = os.path.join(
            work_dir,
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

            for area in air_basin_area:
                # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({area}\\)', regex=True) | (
                        df['sub_area'] == area)

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

def process_beam_freight(study_area, scenario_name, config, work_dir, fuel_assumption_mapping):
    beam_fleet_vmt_file = str(os.path.join(
        work_dir,
        f"emissions/{study_area}_beam_fleet_vmt_{scenario_name}.csv"
    ))

    if os.path.exists(beam_fleet_vmt_file):
        fleet_df = pd.read_csv(beam_fleet_vmt_file)
    else:
        _carriers_file = str(os.path.join(work_dir, config["beam"]["carriers_file"]))
        _payloads_file = str(os.path.join(work_dir, config["beam"]["payloads_file"]))
        _ft_vehicle_types_file = str(os.path.join(work_dir, config["beam"]["ft_vehicle_types_file"]))
        #_pax_vehicle_types_file = str(os.path.join(work_dir, config["pax_vehicle_types_file"]))

        carriers = pd.read_csv(_carriers_file)
        payloads_raw = pd.read_csv(_payloads_file)
        ft_vehicletypes = pd.read_csv(_ft_vehicle_types_file)
        #_pax_vehicle_types = pd.read_csv(_pax_vehicle_types_file)

        carriers_formatted = carriers[['tourId', 'vehicleId', 'vehicleTypeId']]
        payloads = payloads_raw[['payloadId', 'tourId', 'payloadType']].copy()
        ft_vehicletypes = ft_vehicletypes[['vehicleTypeId', 'primaryFuelType', 'secondaryFuelType']].copy()
        tour_distances = calculate_tour_distances(payloads_raw)

        ft_vehicletypes['beamClass'] = ft_vehicletypes['vehicleTypeId'].apply(get_vehicle_class_from_freight)

        # Summarize data
        payloads.loc[:, 'payloadType'] = payloads['payloadType'].astype(str)
        payloads_summary = payloads.groupby(['tourId'])['payloadType'].agg('|'.join).reset_index()

        # Merge payload summary with carriers
        payloads_merged = pd.merge(payloads_summary, carriers_formatted, on='tourId', how='left')

        # Load and process vehicle types
        ft_vehicletypes['beamFuel'] = np.where(
            (ft_vehicletypes['primaryFuelType'] == fuel_emfac2beam_map["Elec"]) &
            ft_vehicletypes['secondaryFuelType'].notna(),
            fuel_emfac2beam_map['Phe'],
            ft_vehicletypes['primaryFuelType']
        )

        def handle_missing_fuel(x):
            try:
                return fuel_assumption_mapping[fuel_beam2emfac_map[x.lower()]]
            except KeyError:
                warnings.warn(f"Fuel type '{x}' not found in mapping. Using original value.")
                return x

        ft_vehicletypes['mappedFuel'] = ft_vehicletypes['beamFuel'].map(handle_missing_fuel)

        # Merge payloads with vehicle types
        payloads_vehtypes = pd.merge(
            payloads_merged,
            ft_vehicletypes[['vehicleTypeId', 'beamClass', 'beamFuel', 'mappedFuel']],
            on='vehicleTypeId',
            how='left'
        )

        # Check for missing fuel types
        if payloads_vehtypes['beamFuel'].isna().any():
            print("Warning: Missing fuel types for some vehicle IDs")
            print(payloads_vehtypes[payloads_vehtypes['beamFuel'].isna()])

        fleet_df = payloads_vehtypes.drop_duplicates('vehicleId', keep='first')

        # Calculate total distance across all tours
        total_distance_all_tours = sum(tour_distances.values())
        # Calculate proportion for each tour
        tour_proportions = {tour_id: distance / total_distance_all_tours
                            for tour_id, distance in tour_distances.items()}

        fleet_df['total_vmt'] = fleet_df['tourId'].map(tour_distances)
        fleet_df['vmt_proportion'] = fleet_df['tourId'].map(tour_proportions)
        fleet_df.to_csv(beam_fleet_vmt_file, index=False)

    return fleet_df



if __name__ == "__main__":
    # Configuration parameters
    area = "sfbay"
    study_area_config = get_area_config(area)
    work_dir = study_area_config["work_dir"]

    run_batch = "2024-11-06"
    scenario = "2018_Baseline"
    ft_scenario_label = scenario.replace("_", "-")
    pax_scenario_label = scenario.replace("_", "-")
    emissions_config = study_area_config["emissions"][scenario]


    # ### Input directories and files ### #
    # Emissions
    # Freight Population
    ft_plans_dir = f"{work_dir}/beam-ft/{run_batch}"
    # Passenger Population
    pax_plans_dir = f"{work_dir}/beam-pax/{run_batch}"


    # ### Output directories and files ### #
    # Freight Population
    ft_filtered_out_emissions_file = f"{ft_plans_dir}/vehicle-tech/ft-filtered-out--{ft_scenario_label}-TrAP.csv"
    ft_vehicle_types_emissions_file = f"{ft_plans_dir}/vehicle-tech/ft-vehicletypes--{ft_scenario_label}-TrAP.csv"
    ft_carriers_emissions_file = f"{ft_plans_dir}/{scenario}/carriers--{ft_scenario_label}-TrAP.csv"
    ft_emissions_rates_relative_filepath = f"TrAP/FT-{str(ft_scenario_label)}"
    # Passenger Population
    pax_filtered_out_emissions_file = f"{pax_plans_dir}/vehicle-tech/pax-filtered-out-TrAP.csv"
    pax_vehicle_types_emissions_file = f"{pax_plans_dir}/vehicle-tech/pax-vehicletypes--{pax_scenario_label}-TrAP.csv"
    pax_emissions_rates_relative_filepath = f"TrAP/PAX-{str(pax_scenario_label)}"

    # print("\n=== EMFAC VMT ===\n")
    # emfac_vmt = process_emfac_vmt(area, scenario, emissions_config)
    # print(f"total_vmt: {emfac_vmt["total_vmt"].sum()}")
    #
    # # ### Prep emissions rates ### #
    # print("\n=== EMFAC Vehicle Classes ===\n")
    # pax_emfac_class_map, ft_emfac_class_map = create_vehicle_class_mapping(emfac_vmt["vehicle_class"].unique())
    #
    # print("\n=== CARB Emissions Rates ===\n")
    # rates = process_emissions_rates(area, scenario, emissions_config, pax_emfac_class_map | ft_emfac_class_map)
    # print(f"rates: {len(rates)}")

    print("\n=== BEAM Fleet ===\n")
    beam_fleet = process_beam_freight(area, scenario, emissions_config, work_dir, FT_FUEL_MAPPING_ASSUMPTIONS)
    print(f"Fleet: {len(beam_fleet)}")


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
    # # Process freight mapping if enabled
    # if run_config["run_ft"]:
    #     ft_payloads = pd.read_csv(payloads_file)
    #     ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
    #     ft_carriers = pd.read_csv(carriers_file, dtype=str)
    #     process_freight_mapping(
    #         emfac_year, filtered_rates, emfac_population,
    #         ft_carriers, ft_payloads, ft_vehicle_types,
    #         ft_carriers_emissions_file, ft_filtered_out_emissions_file,
    #         ft_vehicle_types_emissions_file, ft_emissions_rates_relative_filepath,
    #         input_dir
    #     )

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