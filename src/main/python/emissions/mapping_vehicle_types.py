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

famos_iteration = "2024-01-23"
area = "sfbay"
# 2018, 2050
year = 2050
# Baseline, HOPhighp2, HOPhighp6, Refhighp2, Refhighp6
scenario = "HOPhighp2"
input_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{famos_iteration}/{str(year)}_{scenario}")
freight_carriers_file = f"{input_dir}/freight-carriers--{str(year)}-{scenario}.csv"
freight_payloads_file = f"{input_dir}/freight-payloads--{str(year)}-{scenario}.csv"
vehicle_types_file = f"{input_dir}/freight-vehicletypes--{str(year)}-{scenario}.csv"

# ##################

# output
vehicle_types_emissions_file = f"{input_dir}/freight-vehicletypes--{str(year)}-{scenario}-emissions.csv"
freight_carriers_emissions_file = f"{input_dir}/freight-carriers--{str(year)}-{scenario}-emissions.csv"
emissions_rates_output_dir = f"{input_dir}/emissions-rates"

#
fuel_mapping_assumptions = {
    'Dsl': 'Diesel',
    'Gas': 'Diesel',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity'
}

# all the readings:
famos_payloads = pd.read_csv(freight_payloads_file)
famos_vehicle_types = pd.read_csv(vehicle_types_file)
famos_carriers = pd.read_csv(freight_carriers_file, dtype=str)
# freight_carriers_formatted = unpacking_famos_population_mesozones(
#     freight_carriers,
#     mesozones_to_county_file,
#     mesozones_lookup_file
# )

print("Processing..")
emissions_rates = pd.read_csv(emfac_emissions_file, low_memory=False, dtype=str)
emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
    emissions_rates,
    area,
    year
)
print(f"EMFAC Rates => rows: {len(emissions_rates_for_mapping)}, "
      f"classes: {len(emissions_rates_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(emissions_rates_for_mapping['emfacFuel'].unique())}")


# ### Summarizing EMFAC population ###
# ['Dsl', 'Elec', 'Gas', 'Phe', 'NG']
emfac_population = pd.read_csv(emfac_population_file, low_memory=False, dtype=str)
emfac_population_for_mapping = prepare_emfac_population_for_mapping(
    emfac_population,
    year,
    fuel_mapping_assumptions
)
print(f"EMFAC Population => rows: {len(emfac_population_for_mapping)}, "
      f"classes: {len(emfac_population_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(emfac_population_for_mapping['emfacFuel'].unique())}")

# ### Mapping counties with Mesozones ###
famos_population_for_mapping = prepare_famos_population_for_mapping(
    famos_carriers,
    famos_payloads,
    famos_vehicle_types,
    fuel_mapping_assumptions
)
print(f"FAMOS Population => rows: {len(famos_population_for_mapping)}, "
      f"classes: {len(famos_population_for_mapping['famosClass'].unique())}, "
      f"fuel: {len(famos_population_for_mapping['famosFuel'].unique())}")
unique_vehicles = set(famos_carriers["vehicleId"].unique()) - set(famos_population_for_mapping["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to map, maybe some vehicles in carriers were not used in payload plans:")
    print(unique_vehicles)

###
print("------------------------------------------------------------------")
print("Distributing vehicle classes from EMFAC across FAMOS population...")
updated_famos_population = distribution_based_vehicle_classes_assignment(
    famos_population_for_mapping,
    emfac_population_for_mapping
)
missing_classes = set(emfac_population_for_mapping['emfacClass'].unique()) - set(updated_famos_population['emfacClass'].unique())
missing_fuel = set(emfac_population_for_mapping['emfacFuel'].unique()) - set(updated_famos_population['emfacFuel'].unique())
if len(missing_classes) > 0 or len(missing_fuel) > 0:
    print(f"Failed to match these classes {missing_classes} and fuel {missing_fuel}")


###
print("------------------------------------------------------------------")
print("Building new set of vehicle types")
updated_vehicle_types = build_new_vehtypes(updated_famos_population, famos_vehicle_types)
print(f"Previous vehicle types had {len(famos_vehicle_types)} types while the new set has {len(updated_vehicle_types)} types")

###
print("------------------------------------------------------------------")
print("Assigning new vehicle types to carriers")
updated_freight_carriers = assign_new_vehtypes_to_carriers(famos_carriers, updated_famos_population, freight_carriers_emissions_file)
unique_vehicles = set(famos_carriers["vehicleId"].unique()) - set(updated_freight_carriers["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to assign vehicle types to these vehicles: {unique_vehicles}")

###
print("------------------------------------------------------------------")
print("Assigning emissions rates to new set of vehicle types")
final_vehicle_types = assign_emissions_rates_to_vehtypes(
    emissions_rates_for_mapping,
    updated_vehicle_types,
    emissions_rates_output_dir
)

print("------------------------------------------------------------------")
unique_vehicle_types = set(updated_vehicle_types["vehicleTypeId"].unique()) - set(final_vehicle_types["vehicleTypeId"].unique())
if len(unique_vehicle_types) > 0:
    print(f"Failed to assign emissions rates to these vehicle types: {unique_vehicle_types}")

print(f"Writing {vehicle_types_emissions_file}")
updated_vehicle_types.to_csv(vehicle_types_emissions_file, index=False)

print("End")
