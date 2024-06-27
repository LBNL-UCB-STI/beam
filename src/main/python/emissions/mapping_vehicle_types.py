from emissions_utils import *
import pygris
import numpy as np
# pd.set_option('display.max_columns', 20)
#
# vehicle_types = mapping_vehicle_types(sf_emfac_pop_file, famos_vehicle_types_file, famos_emfac_file_out)
# # Display the first few rows of the new dataframe to verify
# print(vehicle_types.head())


# ### File Paths ###
emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/2018/Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
#emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/2018/SF_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
mesozones_lookup_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/zonal_id_lookup_final.csv")
county_data_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/sfbay_counties_wgs84.geojson")
cbg_data_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/sfbay_cbgs_wgs84.geojson")
mesozones_to_county_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/mesozones_to_county.csv")
freight_carriers_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-carriers.csv")
freight_payloads_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-payloads.csv")
freight_vehicletypes_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-vehicletypes--Baseline.csv")
# ##################

# output
formatted_statewide_emfac_population_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/CA-emfac-freight-population.csv")
famos_emfac_freight_population_mapping_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/famos-emfac-freight-population-mapping.csv")

# ### Assumptions Mapping FAMOS to EMFAC vehicle types ###
fuel_assumption_mapping = {
    'Dsl': 'Diesel',
    'Gas': 'Diesel',
    'NG': 'Diesel',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2FC': 'Hydrogen'
}

# ### Summarizing EMFAC population ###
formatted_emfac_population = prepare_emfac_population_for_mapping(
    emfac_population_file,
    formatted_statewide_emfac_population_file,
    emfac_class_to_famos_class_map,
    fuel_assumption_mapping
)

# ### Mapping counties with Mesozones ###
freight_carriers = pd.read_csv(freight_carriers_file, dtype=str)[['tourId', 'vehicleId', 'vehicleTypeId', 'warehouseZone']]

# This for zonal mapping
# freight_carriers_formatted = unpacking_famos_population_mesozones(
#     freight_carriers,
#     mesozones_to_county_file,
#     mesozones_lookup_file
# )

# Instead use statewide mapping
formatted_freight_carriers = freight_carriers[['tourId', 'vehicleId', 'vehicleTypeId']]

formatted_famos_population = prepare_famos_population_for_mapping(
    formatted_freight_carriers,
    freight_payloads_file,
    freight_vehicletypes_file
)

famos_emfac_freight_population_mapping = map_famos_emfac_freight_population(
    formatted_famos_population,
    formatted_emfac_population,
    famos_emfac_freight_population_mapping_file,
    is_statewide=True
)