from emissions_utils import *

sf_emfac_pop_file = '~/Workspace/Models/emfac/2018/SF_Normalized_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv'
famos_vehicle_types_file = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/freight-vehicletypes.csv'
famos_emfac_file_out = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/emfac-freight-vehicletypes.csv'

vehicle_types = mapping_vehicle_types(sf_emfac_pop_file, famos_vehicle_types_file, famos_emfac_file_out)
# Display the first few rows of the new dataframe to verify
print(vehicle_types.head())


freight_carriers = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-carriers.csv")[['tourId', 'vehicleId', 'vehicleTypeId']]
freight_payloads = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-payloads.csv")[['payloadId', 'tourId', 'payloadType']]
freight_vehicletypes = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-vehicletypes--Baseline.csv")[['vehicleTypeId', 'vehicleClass']]
freight_payloads_summary = freight_payloads.groupby(['tourId']).agg({'payloadType': lambda x: '|'.join(map(str, x))}).reset_index()
freight_payloads_merged = pd.merge(freight_payloads_summary, freight_carriers, on='tourId', how='left')
freight_payloads_merged = pd.merge(freight_payloads_merged, freight_vehicletypes, on='vehicleTypeId', how='left')

emfac_population = pd.read_csv('~/Workspace/Models/emfac/2018/SF_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
emfac_population['famos_vehicle_class'] = emfac_population['vehicle_class'].map(emfac_vehicle_class_mapping)
emfac_freight_population = emfac_population.dropna(subset=['famos_vehicle_class'])

if len(emfac_freight_population["vehicle_class"].unique()) == len(emfac_vehicle_class_mapping):
    print("Mapping is fine!")
else:
    print("Something wrong happened with the mapping")

emfac_freight_aggpop = emfac_freight_population.groupby(["famos_vehicle_class"]).agg({'population': 'sum'}).rename(columns={'population': 'total_population'})
emfac_freight_population = emfac_freight_population.merge(emfac_freight_aggpop, on='famos_vehicle_class')
emfac_freight_population["pct_per_famos_class"] = emfac_freight_population["population"] / emfac_freight_population["total_population"]


