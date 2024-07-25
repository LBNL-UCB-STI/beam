from emissions_utils import *
pd.set_option('display.max_columns', 20)

# HEADER
# ### File Paths ###
# mesozones_lookup_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/zonal_id_lookup_final.csv")
# county_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_counties_wgs84.geojson")
# cbg_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_cbgs_wgs84.geojson")
# taz_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_tazs_epsg26910.geojson")
# mesozones_to_county_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/mesozones_to_county.csv")
emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_population_20240612233346.csv')
emfac_emissions_file = os.path.expanduser('~/Workspace/Models/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv')

ft_iteration = "2024-01-23"
area = "sfbay"
##
# emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2050, 2050, "HOPhighp2", 2045, "LowTech"
emfac_year, ft_year, ft_scenario, pax_year, pax_scenario = 2018, 2018, "Baseline", 2018, "Baseline"
##
input_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{ft_iteration}")
carriers_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/carriers--{str(ft_year)}-{ft_scenario}.csv"
payloads_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/payloads--{str(ft_year)}-{ft_scenario}.csv"
ft_vehicle_types_file = f"{input_dir}/vehicle-tech/ft-vehicletypes--{str(ft_year)}-{ft_scenario}.csv"
pax_vehicle_types_file = f"{input_dir}/vehicle-tech/pax-vehicletypes--{str(pax_year)}-{pax_scenario}.csv"

# ##################

# output
ft_filtered_out_emissions_file = f"{input_dir}/vehicle-tech/ft-filtered-out--{str(ft_year)}-{ft_scenario}-TrAP.csv"
ft_vehicle_types_emissions_file = f"{input_dir}/vehicle-tech/ft-vehicletypes--{str(ft_year)}-{ft_scenario}-TrAP.csv"
ft_carriers_emissions_file = f"{input_dir}/{str(ft_year)}_{ft_scenario}/carriers--{str(ft_year)}-{ft_scenario}-TrAP.csv"
ft_emissions_rates_relative_filepath = f"TrAP/{str(ft_year)}-FT-{ft_scenario}"

pax_filtered_out_emissions_file = f"{input_dir}/vehicle-tech/pax-filtered-out-TrAP.csv"
pax_vehicle_types_emissions_file = f"{input_dir}/vehicle-tech/pax-vehicletypes--{str(pax_year)}-{pax_scenario}-TrAP.csv"
pax_emissions_rates_relative_filepath = f"TrAP/{str(pax_year)}-Pax-{pax_scenario}"

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

ft_fuel_mapping_assumptions = {
    'Dsl': 'Diesel',
    'Gas': 'Diesel',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity'
}
#
pax_fuel_mapping_assumptions = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Electricity',
    'BioDsl': 'Diesel'
}




#  ######### MAIN ##########

print(f"Scenario {area}, {str(ft_year)}-{ft_scenario} from {ft_iteration}..")
# all the readings:
ft_payloads = pd.read_csv(payloads_file)
ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
ft_carriers = pd.read_csv(carriers_file, dtype=str)

# ['Dsl', 'Elec', 'Gas', 'Phe', 'NG']
print("Processing emfac population and rates")
emfac_population = pd.read_csv(emfac_population_file, low_memory=False, dtype=str)
emfac_population['population'] = pd.to_numeric(emfac_population['population'], errors='coerce')
pax_emfac_class_map, ft_emfac_class_map = create_vehicle_class_mapping(emfac_population["vehicle_class"].unique())


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
filtered_rates = emissions_rates[
        emissions_rates["sub_area"].str.contains(fr"\({re.escape(region_to_emfac_area[area])}\)", case=False, na=False) &
        (emissions_rates["calendar_year"] == emfac_year)
    ]


# ### PASSENGER ###
print("\nMapping EMFAC for passengers!")
# EMFAC Rates
pax_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
    filtered_rates,
    pax_emfac_class_map
)
print(f"EMFAC Passenger Rates => rows: {len(pax_emissions_rates_for_mapping)}, "
      f"classes: {len(pax_emissions_rates_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(pax_emissions_rates_for_mapping['emfacFuel'].unique())}")

# EMFAC Population
emfac_passenger_population_for_mapping = prepare_emfac_population_for_mapping(
    emfac_population,
    emfac_year,
    pax_emfac_class_map,
    pax_fuel_mapping_assumptions
)
print(f"EMFAC Passenger Population => rows: {len(emfac_passenger_population_for_mapping)}, "
      f"classes: {len(emfac_passenger_population_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(emfac_passenger_population_for_mapping['emfacFuel'].unique())}")

# Passenger Population
pax_population_for_mapping = prepare_pax_vehicle_population_for_mapping(
    pax_vehicle_types,
    pax_fuel_mapping_assumptions
)
print(f"BEAM Passenger Population => rows: {len(pax_population_for_mapping)}, "
      f"classes: {len(pax_population_for_mapping['beamClass'].unique())}, "
      f"fuel: {len(pax_population_for_mapping['beamFuel'].unique())}")

print("------------------------------------------------------------------")
print("Distributing passenger vehicle classes from EMFAC across BEAM population...")
updated_passenger_vehicle_types = build_new_pax_vehtypes(
    emfac_passenger_population_for_mapping,
    pax_population_for_mapping
)
print(f"Previous vehicle types had {len(pax_population_for_mapping)} types "
      f"while the new set has {len(updated_passenger_vehicle_types)} types")

print("------------------------------------------------------------------")
print("Formatting Passenger EMFAC rates for BEAM")
pax_emfac_formatted, pax_emfac_filtered_out = format_rates_for_beam(pax_emissions_rates_for_mapping)
pax_emfac_filtered_out.to_csv(pax_filtered_out_emissions_file)
print(f"Filtered out passenger processes with all zeros emissions, verify output here => {pax_filtered_out_emissions_file}")


print("------------------------------------------------------------------")
print("Assigning Passenger emissions rates to new set of vehicle types")
ft_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
    pax_emfac_formatted,
    updated_passenger_vehicle_types,
    input_dir + "/vehicle-tech",
    pax_emissions_rates_relative_filepath
)

# Create a new dataframe with the missing rows
print("------------------------------------------------------------------")
print("Adding back Passenger vehicle types not mapped with EMFAC")
index_population = set(pax_population_for_mapping.index)
index_vehicle_types = set(pax_vehicle_types.index)
missing_rows = index_vehicle_types - index_population
missing_df = pax_vehicle_types.loc[list(missing_rows)]
missing_df["emissionsRatesFile"] = ""
pax_emfac_vehicletypes = pd.concat([ft_vehicle_types_with_emissions_rates[missing_df.columns], missing_df], axis=0)
pax_emfac_vehicletypes.to_csv(pax_vehicle_types_emissions_file, index=False)

print("Done mapping EMFAC for passengers!")



# **************
# FREIGHT
# **************

print("\nMapping EMFAC for freight!")
# EMFAC Rates
ft_emissions_rates_for_mapping = prepare_emfac_emissions_for_mapping(
    filtered_rates,
    ft_emfac_class_map
)
print(f"EMFAC Freight Rates => rows: {len(ft_emissions_rates_for_mapping)}, "
      f"classes: {len(ft_emissions_rates_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(ft_emissions_rates_for_mapping['emfacFuel'].unique())}")

ft_emfac_pop_for_mapping = prepare_emfac_population_for_mapping(
    emfac_population,
    emfac_year,
    ft_emfac_class_map,
    ft_fuel_mapping_assumptions
)
print(f"EMFAC Freight Population => rows: {len(ft_emfac_pop_for_mapping)}, "
      f"classes: {len(ft_emfac_pop_for_mapping['emfacClass'].unique())}, "
      f"fuel: {len(ft_emfac_pop_for_mapping['emfacFuel'].unique())}")

#
ft_population_for_mapping = prepare_ft_vehicle_population_for_mapping(
    ft_carriers,
    ft_payloads,
    ft_vehicle_types,
    ft_fuel_mapping_assumptions
)
print(f"BEAM Freight Population => rows: {len(ft_population_for_mapping)}, "
      f"classes: {len(ft_population_for_mapping['beamClass'].unique())}, "
      f"fuel: {len(ft_population_for_mapping['beamFuel'].unique())}")
unique_vehicles = set(ft_carriers["vehicleId"].unique()) - set(ft_population_for_mapping["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to map, maybe some vehicles in carriers were not used in payload plans:")
    print(unique_vehicles)


###
print("------------------------------------------------------------------")
print("Distributing freight vehicle classes from EMFAC across BEAM population...")
updated_freight_population = distribution_based_vehicle_classes_assignment(
    ft_population_for_mapping,
    ft_emfac_pop_for_mapping
)
missing_classes = set(ft_emfac_pop_for_mapping['emfacClass'].unique()) - set(updated_freight_population['emfacClass'].unique())
missing_fuel = set(ft_emfac_pop_for_mapping['emfacFuel'].unique()) - set(updated_freight_population['emfacFuel'].unique())
if len(missing_classes) > 0 or len(missing_fuel) > 0:
    print(f"Failed to match these classes {missing_classes} and fuel {missing_fuel}")


###
print("------------------------------------------------------------------")
print("Building new set of freight vehicle types")
updated_vehicle_types = build_new_ft_vehtypes(updated_freight_population, ft_vehicle_types)
print(f"Previous vehicle types had {len(ft_vehicle_types)} types while the new set has {len(updated_vehicle_types)} types")
updated_vehicle_types[updated_vehicle_types["emfacId"].str.contains("T7-NNOOS")]
###
print("------------------------------------------------------------------")
print("Assigning new freight vehicle types to carriers")
updated_carriers = assign_new_ft_vehtypes_to_carriers(ft_carriers, updated_freight_population, ft_carriers_emissions_file)
unique_vehicles = set(ft_carriers["vehicleId"].unique()) - set(updated_carriers["vehicleId"].unique())
if len(unique_vehicles) > 0:
    print(f"Failed to assign vehicle types to these vehicles: {unique_vehicles}")


###
print("------------------------------------------------------------------")
print("Formatting EMFAC freight rates for BEAM")
ft_emfac_formatted, ft_emfac_filtered_out = format_rates_for_beam(ft_emissions_rates_for_mapping)
ft_emfac_filtered_out.to_csv(ft_filtered_out_emissions_file)
print(f"Filtered out freight processes with all zeros emissions, verify output here => {ft_filtered_out_emissions_file}")

###
print("------------------------------------------------------------------")
print("Assigning freight emissions rates to new set of vehicle types")
ft_vehicle_types_with_emissions_rates = assign_emissions_rates_to_vehtypes(
    ft_emfac_formatted,
    updated_vehicle_types,
    input_dir + "/vehicle-tech",
    ft_emissions_rates_relative_filepath
)

print("------------------------------------------------------------------")
unique_ft_vehicle_types = set(updated_vehicle_types["vehicleTypeId"].unique()) - set(ft_vehicle_types_with_emissions_rates["vehicleTypeId"].unique())
if len(unique_ft_vehicle_types) > 0:
    print(f"Failed to assign emissions rates to these vehicle types: {unique_ft_vehicle_types}")

print(f"Writing {ft_vehicle_types_emissions_file}")
updated_vehicle_types.to_csv(ft_vehicle_types_emissions_file, index=False)

print("End")
