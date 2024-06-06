from emissions_utils import *
import pygris
import numpy as np
# pd.set_option('display.max_columns', 20)
# sf_emfac_pop_file = '~/Workspace/Models/emfac/2018/SF_Normalized_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv'
# famos_vehicle_types_file = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/freight-vehicletypes.csv'
# famos_emfac_file_out = '~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/emfac-freight-vehicletypes.csv'
#
# vehicle_types = mapping_vehicle_types(sf_emfac_pop_file, famos_vehicle_types_file, famos_emfac_file_out)
# # Display the first few rows of the new dataframe to verify
# print(vehicle_types.head())


# File Paths:
emfac_population_file = os.path.expanduser('~/Workspace/Models/emfac/2018/SF_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
mesozones_lookup_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/zonal_id_lookup_final.csv")
county_data_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/sfbay_counties_wgs84.geojson")
cbg_data_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/sfbay_cbgs_wgs84.geojson")
mesozones_to_county_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/geo/mesozones_to_county.csv")
freight_carriers_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-carriers.csv")
freight_payloads_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-payloads.csv")
freight_vehicletypes_file = os.path.expanduser("~/Workspace/Data/FREIGHT/sfbay/beam_freight/2024-01-23/Baseline/freight-vehicletypes--Baseline.csv")

# Summarizing EMFAC population
emfac_population = pd.read_csv(emfac_population_file, dtype=str)
emfac_population['vehicleClass'] = emfac_population['vehicle_class'].map(emfac_vehicle_class_mapping)
emfac_freight_population = emfac_population.dropna(subset=['vehicleClass'])
if len(emfac_freight_population["vehicle_class"].unique()) != len(emfac_vehicle_class_mapping):
    print("Something wrong happened with the mapping")
if not emfac_freight_population['fuel'].isin(emfac_fuel_mapping.keys()).all():
    print("Missing fuel type from dictionary!!!")
emfac_freight_population = emfac_freight_population.assign(
    fuelType=lambda x: x['fuel'].map(emfac_fuel_mapping),
    zone=lambda x: x['sub_area'].str.replace(re.compile(r'\(SF\)'), '', regex=True).str.strip(),
    population=lambda x: x['population'].astype(float)
)
emfac_freight_population = emfac_freight_population.drop(['fuel', 'calendar_year', 'sub_area'], axis=1)
emfac_freight_population = emfac_freight_population.rename(columns={'vehicle_class': 'emfacClass'})

# Mapping counties with Mesozones
mesozones_lookup = pd.read_csv(mesozones_lookup_file, dtype=str)
if not os.path.exists(mesozones_to_county_file):
    county_data = pygris.counties(state='06', year=2018, cb=True, cache=True)
    county_data.to_file(county_data_file, driver='GeoJSON')
    cbg_data = pygris.block_groups(state='06', year=2018, cb=True, cache=True)
    cbg_data.to_file(cbg_data_file, driver='GeoJSON')
    county_data_clipped = county_data[['COUNTYFP', 'NAME']]
    cbg_data_clipped = cbg_data[['GEOID', 'COUNTYFP']]
    cbg_to_county = pd.merge(cbg_data_clipped, county_data_clipped, on="COUNTYFP", how='left')
    mesozones_lookup_clipped = mesozones_lookup[['MESOZONE', 'GEOID']]
    mesozones_to_county = pd.merge(mesozones_lookup_clipped, cbg_to_county, on='GEOID', how='left')
    mesozones_to_county.to_csv(mesozones_to_county_file, index=False)
else:
    mesozones_to_county = pd.read_csv(mesozones_to_county_file, dtype=str)

# TODO For future improvement find a way to map outside study area mesozones. It's a significant effort because
# TODO you will need to also restructure EMFAC in such a way vehicle population from outside study area well represented
if not mesozones_to_county[mesozones_to_county["NAME"].isna()].empty:
    print("Mesozones outside study area do not have a proper GEOID and were not mapped.")
mesozones_to_county_studyarea = mesozones_to_county[mesozones_to_county["NAME"].notna()][["MESOZONE", "NAME"]]

# Mapping freight carriers with counties, payload and vehicle types
freight_carriers = pd.read_csv(freight_carriers_file, dtype=str)[['tourId', 'vehicleId', 'vehicleTypeId', 'warehouseZone']]
freight_carriers_per_county = pd.merge(freight_carriers, mesozones_to_county_studyarea, left_on='warehouseZone', right_on='MESOZONE', how='left')
if not freight_carriers_per_county[freight_carriers_per_county['NAME'].isna()].empty:
    print("Something went wrong with the mapping of freight carrier zones with mesozones. Here the non mapped ones:")
    print(freight_carriers_per_county[freight_carriers_per_county['NAME'].isna()])
freight_carriers_per_county = freight_carriers_per_county[['tourId', 'vehicleId', 'vehicleTypeId', 'NAME']].rename(columns={'NAME': 'zone'})
freight_payloads = pd.read_csv(freight_payloads_file)[['payloadId', 'tourId', 'payloadType']]
freight_payloads_summary = freight_payloads.groupby(['tourId']).agg({'payloadType': lambda x: '|'.join(map(str, x))}).reset_index()
freight_payloads_merged = pd.merge(freight_payloads_summary, freight_carriers_per_county, on='tourId', how='left')
if not freight_payloads_merged[freight_payloads_merged['zone'].isna()].empty:
    print("Something went wrong with the mapping of freight carrier and payloads. Missing tours?")
    print(freight_payloads_merged[freight_payloads_merged['zone'].isna()])
freight_vehicletypes = pd.read_csv(freight_vehicletypes_file)[['vehicleTypeId', 'vehicleClass', 'primaryFuelType', 'secondaryFuelType']]
freight_vehicletypes['fuelType'] = np.where(
    (freight_vehicletypes['primaryFuelType'] == emfac_fuel_mapping["Elec"]) & freight_vehicletypes['secondaryFuelType'].notna(),
    emfac_fuel_mapping['Phe'],
    freight_vehicletypes['primaryFuelType']
)
freight_vehicletypes = freight_vehicletypes[['vehicleTypeId', 'vehicleClass', 'fuelType']]
freight_payloads_vehtypes = pd.merge(freight_payloads_merged, freight_vehicletypes, on='vehicleTypeId', how='left')
if not freight_payloads_vehtypes[freight_payloads_vehtypes['fuelType'].isna()].empty:
    print("Something went wrong with the mapping of freight vehicle types. Missing vehicleTypeId?")
    print(freight_payloads_vehtypes[freight_payloads_vehtypes['fuelType'].isna()])

# emfac_freight_aggpop = emfac_freight_population.groupby(["famos_vehicle_class"]).agg({'population': 'sum'}).rename(columns={'population': 'total_population'})
# emfac_freight_population = emfac_freight_population.merge(emfac_freight_aggpop, on='famos_vehicle_class')
# emfac_freight_population["pct_per_famos_class"] = emfac_freight_population["population"] / emfac_freight_population["total_population"]
# spread_df = emfac_freight_population.pivot(index='Name', columns='Variable', values='Value')

# Sampling vehicle class entries

# Group the emfac DataFrame by vehicleClass and zone
fuel_distributed_following_upstream_logic = [emfac_fuel_mapping['Elec'], emfac_fuel_mapping['Phe'], emfac_fuel_mapping['H2FC']]
emfac_freight_population_filtered = emfac_freight_population[~emfac_freight_population['fuelType'].isin(fuel_distributed_following_upstream_logic)]
grouped_by_class = emfac_freight_population_filtered.groupby(['zone', 'vehicleClass'])
grouped_by_fuel = emfac_freight_population_filtered.groupby(['zone', 'vehicleClass', 'fuelType'])


# List to store the results which will be merged later
results = []

# Loop over each row in the vehtypes DataFrame
for index, row in freight_payloads_vehtypes.iterrows():
    vehicleClass, zone, fuelType, vehicleTypeId = row['vehicleClass'], row['zone'], row['fuelType'], row['vehicleTypeId']

    try:
        group = grouped_by_fuel.get_group((zone, vehicleClass, fuelType))
        weights = group['population'] / group['population'].sum()
        sample = group.sample(n=1, weights=weights)
        emfacClass = sample['emfacClass'].iloc[0].replace(' ', '')
        fuel = emfac_fuel_mapping2[sample['fuelType'].iloc[0]]
        sample['emfacFuelType'] = fuel
        sample['emfacVehicleTypeId'] = vehicleTypeId + "-" + emfacClass + "-" + fuel
        result_row = sample[['emfacVehicleTypeId', 'emfacClass', 'emfacFuelType']].iloc[0]
    except KeyError:
        result_row = None

    try:
        if result_row is None:
            print("ok")
            group2 = grouped_by_class.get_group((zone, vehicleClass))
            weights2 = group2['population'] / group2['population'].sum()
            sample2 = group2.sample(n=1, weights=weights2)
            emfacClass = sample2['emfacClass'].iloc[0].replace(' ', '')
            fuel = emfac_fuel_mapping2[fuelType]
            sample2['emfacFuelType'] = fuel
            sample2['emfacVehicleTypeId'] = vehicleTypeId + "-" + emfacClass + "-" + fuel
            result_row = sample2[['emfacVehicleTypeId', 'emfacClass', 'emfacFuelType']].iloc[0]
            print(result_row)

    except KeyError:
        # Handle the case where no matching group is found
        print(f"This vehicleClass [{vehicleClass}] and zone [{zone}] not found in emfac! \nRow: {row}")
        result_row = pd.Series({'emfacVehicleTypeId': None, 'emfacClass': None, 'emfacFuelType': None}, name=index)

    results.append(pd.Series(result_row, name=index))

# Merge the results back to freight_payloads_vehtypes
final_df = freight_payloads_vehtypes.join(pd.concat(results, axis=1).transpose())
