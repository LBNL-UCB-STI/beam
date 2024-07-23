from emissions_utils import *
pd.set_option('display.max_columns', 20)

# ### File Paths ###
# mesozones_lookup_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/zonal_id_lookup_final.csv")
# county_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_counties_wgs84.geojson")
# cbg_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_cbgs_wgs84.geojson")
# taz_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_tazs_epsg26910.geojson")
# mesozones_to_county_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/mesozones_to_county.csv")
emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_population_20240612233346.csv')
emfac_emissions_file = os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv')

freight_iteration = "2024-01-23"
area = "sfbay"
##
# 2018, 2050
emfac_year = 2018
# 2018, 2050
# Baseline, HOPhighp2
freight_year = 2018
freight_scenario = "Baseline"
# 2018, 2045
# Baseline, LowTech
passenger_year = 2018
passenger_scenario = "Baseline"
##
input_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{freight_iteration}/{str(freight_year)}_{freight_scenario}")
freight_carriers_file = f"{input_dir}/freight-carriers--{str(freight_year)}-{freight_scenario}.csv"
freight_payloads_file = f"{input_dir}/freight-payloads--{str(freight_year)}-{freight_scenario}.csv"
freight_vehicle_types_file = f"{input_dir}/ft-vehicletypes--{str(freight_year)}-{freight_scenario}.csv"
passenger_vehicle_types_file = f"{input_dir}/pax-vehicletypes--{str(passenger_year)}-{passenger_scenario}.csv"

# ##################

# output
freight_vehicle_types_emissions_file = f"{input_dir}/freight-vehicletypes--{str(freight_year)}-{freight_scenario}-emissions.csv"
freight_carriers_emissions_file = f"{input_dir}/freight-carriers--{str(freight_year)}-{freight_scenario}-emissions.csv"
emissions_rates_relative_filepath = f"emissions-rates/{str(freight_year)}-FT-{freight_scenario}"

passenger_vehicle_types_emissions_file = f"{input_dir}/passenger-vehicletypes--{str(passenger_year)}-{passenger_scenario}-emissions.csv"
passenger_emissions_rates_relative_filepath = f"emissions-rates/{str(passenger_year)}-Pax-{passenger_scenario}"



# combine_csv_files(
# [
#     os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018.csv'),
# os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2025.csv'),
# os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2030.csv'),
# os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2040.csv'),
# os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2050.csv')
# ]
# , emfac_emissions_file)
#
freight_fuel_mapping_assumptions = {
    'Dsl': 'Diesel',
    'Gas': 'Diesel',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity'
}
#
passenger_fuel_mapping_assumptions = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity',
    'BioDsl': 'Diesel'
}

print(f"Processing {area}, {str(freight_year)}-{freight_scenario} from {freight_iteration}..")
# all the readings:
freight_payloads = pd.read_csv(freight_payloads_file)
freight_vehicle_types = pd.read_csv(freight_vehicle_types_file)
passenger_vehicle_types = pd.read_csv(passenger_vehicle_types_file)
freight_carriers = pd.read_csv(freight_carriers_file, dtype=str)
emissions_rates = pd.read_csv(emfac_emissions_file, low_memory=False, dtype=str)
# Load the dataset from the uploaded CSV file
emissions_rates['calendar_year'] = pd.to_numeric(emissions_rates['calendar_year'], errors='coerce')
emissions_rates['relative_humidity'] = pd.to_numeric(emissions_rates['relative_humidity'], errors='coerce')
emissions_rates['temperature'] = pd.to_numeric(emissions_rates['temperature'], errors='coerce')
emissions_rates['speed_time'] = pd.to_numeric(emissions_rates['speed_time'], errors='coerce')
emissions_rates['emission_rate'] = pd.to_numeric(emissions_rates['emission_rate'], errors='coerce')
emissions_rates = emissions_rates[
        emissions_rates["sub_area"].str.contains(fr"\({re.escape(region_to_emfac_area[area])}\)", case=False, na=False) &
        (emissions_rates["calendar_year"] == emfac_year)
    ]
# ['Dsl', 'Elec', 'Gas', 'Phe', 'NG']
emfac_population = pd.read_csv(emfac_population_file, low_memory=False, dtype=str)
emfac_population['population'] = pd.to_numeric(emfac_population['population'], errors='coerce')
# freight_carriers_formatted = unpacking_freight_population_mesozones(
#     freight_carriers,
#     mesozones_to_county_file,
#     mesozones_lookup_file
# )

# ### PASSENGER ###
print("Mapping EMFAC for passengers!")
# EMFAC Rates
passenger_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
    emissions_rates,
    passenger_emfac_class_map
)
print(f"EMFAC Passenger Rates => rows: {len(passenger_emissions_rates_for_mapping)}, "
      f"classes: {len(passenger_emissions_rates_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(passenger_emissions_rates_for_mapping['emfacFuel'].unique())}")

# EMFAC Population
emfac_passenger_population_for_mapping = prepare_emfac_population_for_mapping(
    emfac_population,
    emfac_year,
    passenger_emfac_class_map,
    passenger_fuel_mapping_assumptions
)
print(f"EMFAC Passenger Population => rows: {len(emfac_passenger_population_for_mapping)}, "
      f"classes: {len(emfac_passenger_population_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(emfac_passenger_population_for_mapping['emfacFuel'].unique())}")

# Passenger Population
passenger_population_for_mapping = prepare_passenger_population_for_mapping(
    passenger_vehicle_types,
    passenger_fuel_mapping_assumptions
)
print(f"BEAM Passenger Population => rows: {len(passenger_population_for_mapping)}, "
      f"classes: {len(passenger_population_for_mapping['beamClass'].unique())}, "
      f"fuel: {len(passenger_population_for_mapping['beamFuel'].unique())}")

print("------------------------------------------------------------------")
print("Distributing passenger vehicle classes from EMFAC across BEAM population...")
updated_passenger_vehicle_types = build_new_passenger_vehtypes(
    emfac_passenger_population_for_mapping,
    passenger_population_for_mapping
)
print(f"Previous vehicle types had {len(passenger_population_for_mapping)} types "
      f"while the new set has {len(updated_passenger_vehicle_types)} types")

print("------------------------------------------------------------------")
print("Formatting Passenger EMFAC rates for BEAM")
passenger_emfac_formatted = format_rates_for_beam(passenger_emissions_rates_for_mapping)

print("------------------------------------------------------------------")
print("Assigning Passenger emissions rates to new set of vehicle types")
vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
    passenger_emfac_formatted,
    updated_passenger_vehicle_types,
    input_dir,
    passenger_emissions_rates_relative_filepath
)

# Create a new dataframe with the missing rows
print("------------------------------------------------------------------")
print("Adding back Passenger vehicle types not mapped with EMFAC")
index_population = set(passenger_population_for_mapping.index)
index_vehicle_types = set(passenger_vehicle_types.index)
missing_rows = index_vehicle_types - index_population
missing_df = passenger_vehicle_types.loc[list(missing_rows)]
missing_df["emissionsRatesFile"] = ""
emfac_passenger_vehicletypes = pd.concat([vehicle_types_with_emissions_rates[missing_df.columns], missing_df], axis=0)
emfac_passenger_vehicletypes.to_csv(passenger_vehicle_types_emissions_file, index=False)

print("Done mapping EMFAC for passengers!")



# **************
# FREIGHT
# **************

print("Mapping EMFAC for freight!")
# EMFAC Rates
freight_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
    emissions_rates,
    freight_emfac_class_map
)
print(f"EMFAC Freight Rates => rows: {len(freight_emissions_rates_for_mapping)}, "
      f"classes: {len(freight_emissions_rates_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(freight_emissions_rates_for_mapping['emfacFuel'].unique())}")

emfac_freight_population_for_mapping = prepare_emfac_population_for_mapping(
    emfac_population,
    emfac_year,
    freight_emfac_class_map,
    freight_fuel_mapping_assumptions
)
print(f"EMFAC Freight Population => rows: {len(emfac_freight_population_for_mapping)}, "
      f"classes: {len(emfac_freight_population_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(emfac_freight_population_for_mapping['emfacFuel'].unique())}")

#
freight_population_for_mapping = prepare_freight_population_for_mapping(
    freight_carriers,
    freight_payloads,
    freight_vehicle_types,
    freight_fuel_mapping_assumptions
)
print(f"BEAM Freight Population => rows: {len(freight_population_for_mapping)}, "
      f"classes: {len(freight_population_for_mapping['beamClass'].unique())}, "
      f"fuel: {len(freight_population_for_mapping['beamFuel'].unique())}")
unique_vehicles = set(freight_carriers["vehicleId"].unique()) - set(freight_population_for_mapping["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to map, maybe some vehicles in carriers were not used in payload plans:")
    print(unique_vehicles)


###
print("------------------------------------------------------------------")
print("Distributing freight vehicle classes from EMFAC across BEAM population...")
updated_freight_population = distribution_based_vehicle_classes_assignment(
    freight_population_for_mapping,
    emfac_freight_population_for_mapping
)
missing_classes = set(emfac_freight_population_for_mapping['emfacClass'].unique()) - set(updated_freight_population['emfacClass'].unique())
missing_fuel = set(emfac_freight_population_for_mapping['emfacFuel'].unique()) - set(updated_freight_population['emfacFuel'].unique())
if len(missing_classes) > 0 or len(missing_fuel) > 0:
    print(f"Failed to match these classes {missing_classes} and fuel {missing_fuel}")


###
print("------------------------------------------------------------------")
print("Building new set of freight vehicle types")
updated_vehicle_types = build_new_freight_vehtypes(updated_freight_population, freight_vehicle_types)
print(f"Previous vehicle types had {len(freight_vehicle_types)} types while the new set has {len(updated_vehicle_types)} types")

###
print("------------------------------------------------------------------")
print("Assigning new freight vehicle types to carriers")
updated_freight_carriers = assign_new_freight_vehtypes_to_carriers(freight_carriers, updated_freight_population, freight_carriers_emissions_file)
unique_vehicles = set(freight_carriers["vehicleId"].unique()) - set(updated_freight_carriers["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to assign vehicle types to these vehicles: {unique_vehicles}")


###
print("------------------------------------------------------------------")
print("Formatting EMFAC freight rates for BEAM")
emfac_formatted = format_rates_for_beam(freight_emissions_rates_for_mapping)

###
print("------------------------------------------------------------------")
print("Assigning freight emissions rates to new set of vehicle types")
vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
    emfac_formatted,
    updated_vehicle_types,
    input_dir,
    emissions_rates_relative_filepath
)

print("------------------------------------------------------------------")
unique_vehicle_types = set(updated_vehicle_types["vehicleTypeId"].unique()) - set(vehicle_types_with_emissions_rates["vehicleTypeId"].unique())
if len(unique_vehicle_types) > 0:
    print(f"Failed to assign emissions rates to these vehicle types: {unique_vehicle_types}")

print(f"Writing {freight_vehicle_types_emissions_file}")
updated_vehicle_types.to_csv(freight_vehicle_types_emissions_file, index=False)

print("End")
