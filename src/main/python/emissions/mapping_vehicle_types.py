from emissions_utils import *
pd.set_option('display.max_columns', 20)
#
# sf_emfac_pop_file = '~/Workspace/Models/emfac/2018/SF_Normalized_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv'
# famos_vehicle_types_file = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-vehicletypes.csv'
# famos_emfac_file_out = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/emfac-freight-vehicletypes.csv'
#
# vehicle_types = map_emfac_vehicle_types_to_famos(sf_emfac_pop_file, famos_vehicle_types_file, famos_emfac_file_out)
# # Display the first few rows of the new dataframe to verify
# print(vehicle_types.head())

# ### File Paths ###
emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/2018/Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
emfac_emissions_file = os.path.expanduser('~/Workspace/Models/emfac/2018/imputed_MTC_emission_rate_agg_NH3_added.csv')
mesozones_lookup_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/zonal_id_lookup_final.csv")
# county_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_counties_wgs84.geojson")
# cbg_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_cbgs_wgs84.geojson")
taz_data_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/sfbay_tazs_epsg26910.geojson")
mesozones_to_county_file = os.path.expanduser("~/Workspace/Simulation/sfbay/geo/mesozones_to_county.csv")
freight_carriers_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/freight-carriers.csv")
freight_payloads_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/freight-payloads.csv")
freight_vehicletypes_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/freight-vehicletypes--Baseline.csv")
# ##################

# output
formatted_emfac_population_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/emfac-freight-population.csv")
formatted_famos_population_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/famos-freight-population.csv")
formatted_emissions_rates_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/emfac-emissions-rates.csv")
output_rates_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/emissions_rates")
freight_vehicletypes_emissions_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/freight-vehicletypes-emissions--Baseline.csv")
freight_carriers_emissions_file = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/freight-carriers-emissions.csv")


# ### Summarizing EMFAC population ###
print("prepare_emfac_population_for_mapping")
emfac_population = pd.read_csv(emfac_population_file, dtype=str)
# ['Dsl', 'Elec', 'Gas', 'Phe', 'NG']

formatted_emfac_population = prepare_emfac_population_for_mapping(
    emfac_population,
    {
        'Dsl': 'Diesel',
        'Gas': 'Diesel',
        'NG': 'Diesel',
        'Elec': 'Electricity',
        'Phe': 'PlugInHybridElectricity',
        'H2FC': 'Electricity'
    }
)
# formatted_emfac_population.to_csv(formatted_emfac_population_file, index=False)
# sampled_df = formatted_emfac_population.sample(n=10, random_state=42)
# sampled_df = sampled_df.reset_index(drop=True)
# sampled_df.to_csv(os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/emfac-freight-population-sample.csv"),
#                   index=False)


# ### Mapping counties with Mesozones ###
print("prepare_famos_population_for_mapping")
freight_carriers = pd.read_csv(freight_carriers_file, dtype=str)
# This for zonal mapping
# freight_carriers_formatted = unpacking_famos_population_mesozones(
#     freight_carriers,
#     mesozones_to_county_file,
#     mesozones_lookup_file
# )

# Instead use statewide mapping
famos_payloads = pd.read_csv(freight_payloads_file)
famos_vehicle_types = pd.read_csv(freight_vehicletypes_file)

formatted_famos_population = prepare_famos_population_for_mapping(
    freight_carriers,
    famos_payloads,
    famos_vehicle_types
)
# formatted_famos_population.to_csv(formatted_famos_population_file, index=False)
# sampled_df = formatted_famos_population.sample(n=10, random_state=42)
# sampled_df = sampled_df.reset_index(drop=True)
# sampled_df.to_csv(os.path.expanduser("~/Workspace/Simulation/sfbay/beam-freight/2024-01-23/Baseline/famos-freight-population-sample.csv"),
#                   index=False)


###
print("distribution_based_vehicle_classes_assignment")
updated_famos_population = distribution_based_vehicle_classes_assignment(
    formatted_famos_population,
    formatted_emfac_population
)

###
print("update_carrier_file")
if os.path.exists(freight_carriers_emissions_file):
    updated_freight_carriers = pd.read_csv(freight_carriers_emissions_file)
else:
    updated_freight_carriers = update_carrier_file(freight_carriers, updated_famos_population)
    updated_freight_carriers.to_csv(freight_carriers_emissions_file, index=False)

###
print("build_vehicle_types_for_emissions")
updated_vehicle_types = build_vehicle_types_for_emissions(updated_famos_population, famos_vehicle_types)

# vehicle_types_famos_emfac_freight_population_mapping = map_famos_emfac_freight_population(
#     formatted_famos_population,
#     formatted_emfac_population,
#     is_statewide=True
# )

# ## EMISSIONS RATES ##
print("format_rates_for_beam")
if os.path.exists(formatted_emissions_rates_file):
    emissions_rates_formatted = pd.read_csv(formatted_emissions_rates_file)
else:
    emissions_rates, _ = get_regional_emfac_data(emfac_emissions_file, ["SF"])
    emissions_rates_formatted = format_rates_for_beam(emissions_rates)
    emissions_rates_formatted.to_csv(formatted_emissions_rates_file, index=False)


print("process_emfac_emissions")
updated_vehicle_types = process_vehicle_types_emissions(
    emissions_rates_formatted,
    updated_vehicle_types,
    output_rates_dir,
    "emissions-rates/baseline/"
)
updated_vehicle_types.to_csv(freight_vehicletypes_emissions_file, index=False)


# base_name, ext = os.path.splitext(emissions_file_path)
# mask = (
#         (df_output["sub_area"] == "San Francisco (SF)") &
#         (df_output["vehicle_class"] == "T7IS") &
#         (df_output["fuel"] == "Gas")
# )
# beam_ville_rates = df_output[mask]
# beam_ville_rates = beam_ville_rates.drop(["sub_area", "vehicle_class", "fuel"], axis=1)
# beam_ville_rates.to_csv(base_name + "_beamville" + ext, index=False)