import pandas as pd
import numpy as np
import os
import re

class_2b3 = 'Class 2b&3 Vocational'
class_46 = 'Class 4-6 Vocational'
class_78_v = 'Class 7&8 Vocational'
class_78_t = 'Class 7&8 Tractor'

lhdt = 'LightHeavyDutyTruck'
mhdt = 'MediumHeavyDutyTruck'
hhdt = 'HeavyHeavyDutyTruck'

class_to_category = {
    class_2b3: lhdt,
    class_46: mhdt,
    class_78_v: hhdt,
    class_78_t: hhdt
}

emfac_class_to_famos_class_map = {
    # Class 2b&3 Vocational
    'LHD1': class_2b3,
    'LHD2': class_2b3,

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': class_46,
    'T6 Instate Delivery Class 5': class_46,
    'T6 Instate Delivery Class 6': class_46,
    'T6 Instate Other Class 4': class_46,
    'T6 Instate Other Class 5': class_46,
    'T6 Instate Other Class 6': class_46,
    'T6 CAIRP Class 4': class_46,
    'T6 CAIRP Class 5': class_46,
    'T6 CAIRP Class 6': class_46,
    'T6 OOS Class 4': class_46,
    'T6 OOS Class 5': class_46,
    'T6 OOS Class 6': class_46,
    'T6 Instate Tractor Class 6': class_46,

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': class_78_v,
    'T6 Instate Other Class 7': class_78_v,
    'T7 Single Concrete/Transit Mix Class 8': class_78_v,
    'T7 Single Dump Class 8': class_78_v,
    'T7 Single Other Class 8': class_78_v,
    'T7IS': class_78_v,  # In-State

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': class_78_t,
    'T7 Tractor Class 8': class_78_t,
    'T6 CAIRP Class 7': class_78_t,
    'T6 OOS Class 7': class_78_t,
    'T7 CAIRP Class 8': class_78_t,
    'T7 NNOOS Class 8': class_78_t,
    'T7 NOOS Class 8': class_78_t,
}
emfac_class_to_vehicle_category_map = {
    # Class 2b&3 Vocational
    'LHD1': lhdt,
    'LHD2': lhdt,

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': mhdt,
    'T6 Instate Delivery Class 5': mhdt,
    'T6 Instate Delivery Class 6': mhdt,
    'T6 Instate Other Class 4': mhdt,
    'T6 Instate Other Class 5': mhdt,
    'T6 Instate Other Class 6': mhdt,
    'T6 CAIRP Class 4': mhdt,
    'T6 CAIRP Class 5': mhdt,
    'T6 CAIRP Class 6': mhdt,
    'T6 OOS Class 4': mhdt,
    'T6 OOS Class 5': mhdt,
    'T6 OOS Class 6': mhdt,
    'T6 Instate Tractor Class 6': mhdt,

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': mhdt,
    'T6 Instate Other Class 7': mhdt,

    'T7IS': hhdt,
    'T7 Single Concrete/Transit Mix Class 8': hhdt,
    'T7 Single Dump Class 8': hhdt,
    'T7 Single Other Class 8': hhdt,

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': mhdt,
    'T6 CAIRP Class 7': mhdt,
    'T6 OOS Class 7': mhdt,

    'T7 Tractor Class 8': hhdt,
    'T7 CAIRP Class 8': hhdt,
    'T7 NNOOS Class 8': hhdt,
    'T7 NOOS Class 8': hhdt,
}
passenger_veh_classes = [
    "LDA",  # Passenger Cars
    "LDT1",  # Light-Duty Trucks (GVWR* <6000 lbs and ETW** <= 3750 lbs)
    "LDT2",  # Light-Duty Trucks (GVWR <6000 lbs and ETW 3751-5750 lbs)
    "MDV",  # Medium-Duty Trucks (GVWR 5751-8500 lbs)
    "MCY",  # Motorcycles
]
transit_veh_classes = [
    "SBUS",  # School Buses
    "UBUS",  # Urban Buses
    "Motor Coach",  # Motor Coach
    "OBUS",  # Other Buses
    "All Other Buses"  # All Other Buses
]
freight_vehicle_classes = [
    "LHD1",  # Light-Heavy-Duty Trucks (GVWR 8501-10000 lbs)
    "LHD2",  # Light-Heavy-Duty Trucks (GVWR 10001-14000 lbs)

    "T6 Instate Tractor Class 6",  # Medium-Heavy Duty Tractor Truck (GVWR 19501-26000 lbs)
    "T6 Instate Delivery Class 4",  # Medium-Heavy Duty Delivery Truck (GVWR 14001-16000 lbs)
    "T6 Instate Delivery Class 5",  # Medium-Heavy Duty Delivery Truck (GVWR 16001-19500 lbs)
    "T6 Instate Delivery Class 6",  # Medium-Heavy Duty Delivery Truck (GVWR 19501-26000 lbs)
    "T6 Instate Other Class 4",  # Medium-Heavy Duty Other Truck (GVWR 14001-16000 lbs)
    "T6 Instate Other Class 5",  # Medium-Heavy Duty Other Truck (GVWR 16001-19500 lbs)
    "T6 Instate Other Class 6",  # Medium-Heavy Duty Other Truck (GVWR 19501-26000 lbs)
    "T6 Instate Tractor Class 7",  # Medium-Heavy Duty Tractor Truck (GVWR 26001-33000 lbs)
    "T6 Instate Delivery Class 7",  # Medium-Heavy Duty Delivery Truck (GVWR 26001-33000 lbs)
    "T6 Instate Other Class 7",  # Medium-Heavy Duty Other Truck (GVWR 26001-33000 lbs)

    "T6 OOS Class 4",  # Medium-Heavy Duty Out-of-state Truck (GVWR 14001-16000 lbs)
    "T6 OOS Class 5",  # Medium-Heavy Duty Out-of-state Truck (GVWR 16001-19500 lbs)
    "T6 OOS Class 6",  # Medium-Heavy Duty Out-of-state Truck (GVWR 19501-26000 lbs)
    "T6 OOS Class 7",  # Medium-Heavy Duty Out-of-state Truck (GVWR 26001-33000 lbs)

    "T6 CAIRP Class 4",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1400116000 lbs)
    "T6 CAIRP Class 5",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1600119500 lbs)
    "T6 CAIRP Class 6",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1950126000 lbs)
    "T6 CAIRP Class 7",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 2600133000 lbs)
    "T7 CAIRP Class 8",  # Heavy-Heavy Duty CA International Registration Plan Truck (GVWR 33001 lbs and over)
    "T7 Single Concrete/Transit Mix Class 8",
    # Heavy-Heavy Duty Single Unit Concrete/Transit Mix Truck (GVWR 33001 lbs and over)
    "T7 Single Dump Class 8",  # Heavy-Heavy Duty Single Unit Dump Truck (GVWR 33001 lbs and over)
    "T7 Single Other Class 8",  # Heavy-Heavy Duty Single Unit Other Truck (GVWR 33001 lbs and over)
    "T7 NNOOS Class 8",  # Heavy-Heavy Duty Non-Neighboring Outof-state Truck (GVWR 33001 lbs and over)
    "T7 NOOS Class 8",  # Heavy-Heavy Duty Neighboring Out-ofstate Truck (GVWR 33001 lbs and over)
    "T7 Tractor Class 8",  # Heavy-Heavy Duty Tractor Truck (GVWR 33001 lbs and over)
    "T7IS",  # Heavy-Heavy Duty Truck
]
other_veh_classes = [
    "MH",  # Motor Homes
    "T6 Utility Class 5",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Utility Class 6",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Utility Class 7",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 26001-33000 lbs)

    ## used by public entities such as municipalities, state governments, and other public agencies
    "T6 Public Class 4",  # Medium-Heavy Duty Public Fleet Truck (GVWR 14001-16000 lbs)
    "T6 Public Class 5",  # Medium-Heavy Duty Public Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Public Class 6",  # Medium-Heavy Duty Public Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Public Class 7",  # Medium-Heavy Duty Public Fleet Truck (GVWR 26001-33000 lbs)
    "T7 Public Class 8",  # Heavy-Heavy Duty Public Fleet Truck (GVWR 33001 lbs and over)

    "T6TS",
    # Medium-Heavy Duty Truck; "Transit/Specialized," these trucks are designed for specific types of operations
    "T7 Utility Class 8",  # Heavy-Heavy Duty Utility Fleet Truck (GVWR 33001 lbs and over)
    "T7 Other Port Class 8",  # Heavy-Heavy Duty Drayage Truck at Other Facilities (GVWR 33001 lbs and over)
    "T7 POAK Class 8",  # Heavy-Heavy Duty Drayage Truck in Bay Area (GVWR 33001 lbs and ove
    "T7 POLA Class 8",  # Heavy-Heavy Duty Drayage Truck near South Coast (GVWR 33001 lbs and over)
    "T7 SWCV Class 8",  # Heavy-Heavy Duty Solid Waste Collection Truck (GVWR 33001 lbs and over)
    "PTO",  # Power Take Off
]
emfac_fuel_to_beam_fuel_map = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'NaturalGas',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectricity',
    'H2fc': 'Hydrogen'
}
beam_fuel_to_emfac_fuel_map = {
    'Diesel': 'Dsl',
    'Gasoline': 'Gas',
    'NaturalGas': 'NG',
    'Electricity': 'Elec',
    'PlugInHybridElectricity': 'Phe',
    'Hydrogen': 'H2fc'
}
pollutant_columns = {
    'CH4': 'rate_ch4_gram_float',
    'CO': 'rate_co_gram_float',
    'CO2': 'rate_co2_gram_float',
    'HC': 'rate_hc_gram_float',
    'NH3': 'rate_nh3_gram_float',
    'NOx': 'rate_nox_gram_float',
    'PM': 'rate_pm_gram_float',
    'PM10': 'rate_pm10_gram_float',
    'PM2_5': 'rate_pm2_5_gram_float',
    'ROG': 'rate_rog_gram_float',
    'SOx': 'rate_sox_gram_float',
    'TOG': 'rate_tog_gram_float'
}


emissions_processes = ["RUNEX", "IDLEX", "STREX", "DIURN", "HOTSOAK", "RUNLOSS", "PMTW", "PMBW"]


def get_regional_emfac_filename(emfac_data_filepath, emfac_regions, label=""):
    folder_path = os.path.dirname(emfac_data_filepath)
    file_name = os.path.basename(emfac_data_filepath)
    emfac_regions_label = '-'.join([fr"{re.escape(region)}" for region in emfac_regions])
    return folder_path + "/" + emfac_regions_label + "_" + label + file_name


def get_regional_emfac_data(emfac_data_filepath, emfac_regions):
    studyarea_x_filepath = get_regional_emfac_filename(emfac_data_filepath, emfac_regions)

    if os.path.exists(os.path.expanduser(studyarea_x_filepath)):
        print("Filtered EMFAC exists. Returning stored output: " + studyarea_x_filepath)
        return pd.read_csv(studyarea_x_filepath), studyarea_x_filepath
    else:
        # Load the dataset from the uploaded CSV file
        data = pd.read_csv(emfac_data_filepath)
        # Filter the data for each region in emfac_regions
        pattern = '|'.join([fr"\({re.escape(region)}\)" for region in emfac_regions])
        emfac_filtered = data[data["sub_area"].str.contains(pattern, case=False, na=False)]
        emfac_filtered.to_csv(studyarea_x_filepath, index=False)
        print("Done filtering EMFAC. The output has been stored in: " + studyarea_x_filepath)
        return emfac_filtered, studyarea_x_filepath


# This function is obsolete. It is because I tried to map vehicles types in emfac with famos without taking into
# consideration vehicle distribution, activities and payload types.
# EMFAC vehicle types are rich in characteristics like cement trucks and out of state trucks. Matching them can only be
# by looking into payload plans
def map_emfac_vehicle_types_to_famos(emfac_pop_file_, famos_vehicle_types_file_, famos_emfac_file_out_):
    # ## Population ##
    emfac_pop = pd.read_csv(emfac_pop_file_)
    famos_vehicle_types = pd.read_csv(famos_vehicle_types_file_)

    # Prepare the new dataframe based on the format of freight_only_df
    new_columns = famos_vehicle_types.columns
    new_df = pd.DataFrame(columns=new_columns)

    # Iterate over each row in the sf_normalized_df and find the closest match in freight_only_df
    for index, row in emfac_pop.iterrows():
        matched_class_fuel = famos_vehicle_types[
            (famos_vehicle_types['vehicleClass'] == emfac_class_to_famos_class_map[row['vehicle_class']]) &
            (famos_vehicle_types['primaryFuelType'] == emfac_fuel_to_beam_fuel_map[row['fuel']])
            ]
        matched_class_phev = famos_vehicle_types[
            (famos_vehicle_types['vehicleClass'] == emfac_class_to_famos_class_map[row['vehicle_class']]) &
            (emfac_fuel_to_beam_fuel_map[row['fuel']] == 'PlugInHybridElectric') &
            (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
            (famos_vehicle_types['secondaryFuelType'] == 'Diesel')
            ]
        matched_fuel_only = famos_vehicle_types[
            (famos_vehicle_types['primaryFuelType'] == emfac_fuel_to_beam_fuel_map[row['fuel']]) |
            (emfac_fuel_to_beam_fuel_map[row['fuel']] == 'PlugInHybridElectric') &
            (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
            (famos_vehicle_types['secondaryFuelType'].isin(['Diesel', 'Gasoline']))
            ]

        if not matched_class_fuel.empty:
            # If both emfac vehicle class and fuel types are in famos, then match them
            new_row = matched_class_fuel.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_class_to_vehicle_category_map[new_row['emfacVehicleClass']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif not matched_class_phev.empty:
            # If both emfac vehicle class and PHEV type are in famos, then match them
            new_row = matched_class_phev.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_class_to_vehicle_category_map[new_row['emfacVehicleClass']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif not matched_fuel_only.empty:
            # If only fuel type is in famos, then match fuel and update vehicle cLass
            new_row = matched_fuel_only.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_class_to_vehicle_category_map[new_row['emfacVehicleClass']]

            ##
            new_row['vehicleClass'] = emfac_class_to_famos_class_map[row['vehicle_class']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif emfac_fuel_to_beam_fuel_map[row['fuel']] in ['NaturalGas', 'Gasoline']:
            # If only fuel type is in either NatGas or Gas, then create new fuel entry and then check if class matches.
            # If class doesn't match then update vehicle class.
            matched_other_fuel = famos_vehicle_types[
                famos_vehicle_types['primaryFuelType'].isin(['Diesel', 'Gasoline'])]
            new_row = matched_other_fuel.iloc[0].copy()
            matched_class_other_fuel = matched_other_fuel[
                matched_other_fuel['vehicleClass'] == emfac_class_to_famos_class_map[row['vehicle_class']]
                ]
            if not matched_class_other_fuel.empty:
                new_row = matched_class_other_fuel.iloc[0].copy()

            if emfac_class_to_famos_class_map[row['vehicle_class']] != new_row['vehicleClass']:
                print(f"There is a mismatch between vehicle class in FAMOS vehicle types and EMFAC vehicle types")
                print("===== FAMOS =====")
                print(new_row)
                print("===== EMFAC =====")
                print(row)

            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_class_to_vehicle_category_map[new_row['emfacVehicleClass']]

            ##
            new_row['vehicleClass'] = emfac_class_to_famos_class_map[row['vehicle_class']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        else:
            print(f"This row failed to be added: {row}")

    # Save the new dataframe to a CSV file
    new_df.to_csv(famos_emfac_file_out_, index=False)
    return new_df


def prepare_emfac_population_for_mapping(emfac_population, fuel_assumption_mapping):
    # Map vehicle classes and drop rows with unmapped classes
    emfac_population['mappedVehicleClass'] = emfac_population['vehicle_class'].map(emfac_class_to_famos_class_map)
    formatted_emfac_population = emfac_population.dropna(subset=['mappedVehicleClass'])

    # Validation checks
    if len(formatted_emfac_population["vehicle_class"].unique()) != len(emfac_class_to_famos_class_map):
        print("Warning: Mismatch in vehicle class mapping")
    if not formatted_emfac_population['fuel'].isin(emfac_fuel_to_beam_fuel_map.keys()).all():
        print("Warning: Missing fuel type from dictionary")

    result = (formatted_emfac_population
              .assign(mappedFuelType=lambda x: x['fuel'].map(fuel_assumption_mapping),
                    population=lambda x: x['population'].astype(float))
              .drop(['calendar_year', 'sub_area'], axis=1)
              .rename(columns={'vehicle_class': 'emfacClass', 'fuel': 'emfacFuel'}))
    result["emfacId"] = result[['emfacClass', 'emfacFuel']].agg('-'.join, axis=1)
    result["emfacId"] = result['emfacId'].str.replace(' ', '-')
    # Apply transformations
    return result


def unpacking_famos_population_mesozones(freight_carriers, mesozones_to_county_file, mesozones_lookup_file):
    import pygris
    # ### Mapping counties with Mesozones ###
    if not os.path.exists(mesozones_to_county_file):
        county_data = pygris.counties(state='06', year=2018, cb=True, cache=True)
        cbg_data = pygris.block_groups(state='06', year=2018, cb=True, cache=True)
        county_data_clipped = county_data[['COUNTYFP', 'NAME']]
        cbg_data_clipped = cbg_data[['GEOID', 'COUNTYFP']]
        cbg_to_county = pd.merge(cbg_data_clipped, county_data_clipped, on="COUNTYFP", how='left')
        mesozones_lookup = pd.read_csv(mesozones_lookup_file, dtype=str)
        mesozones_lookup_clipped = mesozones_lookup[['MESOZONE', 'GEOID']]
        mesozones_to_county = pd.merge(mesozones_lookup_clipped, cbg_to_county, on='GEOID', how='left')
        mesozones_to_county.to_csv(mesozones_to_county_file, index=False)
    else:
        mesozones_to_county = pd.read_csv(mesozones_to_county_file, dtype=str)

    # TODO For future improvement find a way to map outside study area mesozones. It's a significant effort because
    # TODO need to also restructure EMFAC in such a way vehicle population from outside study area well represented
    if not mesozones_to_county[mesozones_to_county["NAME"].isna()].empty:
        print("Mesozones outside study area do not have a proper GEOID and were not mapped.")
    mesozones_to_county_studyarea = mesozones_to_county[mesozones_to_county["NAME"].notna()][["MESOZONE", "NAME"]]

    # ### Mapping freight carriers with counties, payload and vehicle types ###
    freight_carriers_by_zone = pd.merge(freight_carriers, mesozones_to_county_studyarea, left_on='warehouseZone',
                                           right_on='MESOZONE', how='left')
    if not freight_carriers_by_zone[freight_carriers_by_zone['NAME'].isna()].empty:
        print(
            "Something went wrong with the mapping of freight carrier zones with mesozones. Here the non mapped ones:")
        print(freight_carriers_by_zone[freight_carriers_by_zone['NAME'].isna()])
    freight_carriers_by_zone = freight_carriers_by_zone[['tourId', 'vehicleId', 'vehicleTypeId', 'NAME']].rename(
        columns={'NAME': 'zone'})

    return freight_carriers_by_zone


def prepare_famos_population_for_mapping(freight_carriers, freight_payloads_raw, freight_vehicletypes):
    freight_carriers_formatted = freight_carriers[['tourId', 'vehicleId', 'vehicleTypeId']]
    freight_payloads = freight_payloads_raw[['payloadId', 'tourId', 'payloadType']].copy()
    freight_vehicletypes = freight_vehicletypes[
        ['vehicleTypeId', 'vehicleClass', 'primaryFuelType', 'secondaryFuelType']].copy()

    # Summarize data
    freight_payloads.loc[:, 'payloadType'] = freight_payloads['payloadType'].astype(str)
    freight_payloads_summary = freight_payloads.groupby('tourId')['payloadType'].agg('|'.join).reset_index()

    # Merge payload summary with carriers
    freight_payloads_merged = pd.merge(freight_payloads_summary, freight_carriers_formatted, on='tourId', how='left')

    # Load and process vehicle types
    freight_vehicletypes['fuelType'] = np.where(
        (freight_vehicletypes['primaryFuelType'] == emfac_fuel_to_beam_fuel_map["Elec"]) &
        freight_vehicletypes['secondaryFuelType'].notna(),
        emfac_fuel_to_beam_fuel_map['Phe'],
        freight_vehicletypes['primaryFuelType']
    )

    # Merge payloads with vehicle types
    freight_payloads_vehtypes = pd.merge(
        freight_payloads_merged,
        freight_vehicletypes[['vehicleTypeId', 'vehicleClass', 'fuelType']],
        on='vehicleTypeId',
        how='left'
    )

    # Check for missing fuel types
    if freight_payloads_vehtypes['fuelType'].isna().any():
        print("Warning: Missing fuel types for some vehicle IDs")
        print(freight_payloads_vehtypes[freight_payloads_vehtypes['fuelType'].isna()])

    # Remove duplicates and return
    return freight_payloads_vehtypes.drop_duplicates('vehicleId', keep='first')


def map_famos_emfac_freight_population(formatted_famos_population, formatted_emfac_population, is_statewide):
    # Define grouping keys based on whether the data is statewide or not
    group_keys = ['mappedVehicleClass', 'mappedFuelType'] if is_statewide else ['zone', 'mappedVehicleClass',
                                                                                'mappedFuelType']
    class_keys = ['mappedVehicleClass'] if is_statewide else ['zone', 'mappedVehicleClass']

    # Group the EMFAC population data
    grouped_by_mappedFuelType = formatted_emfac_population.groupby(group_keys)
    grouped_by_mappedVehicleClass = formatted_emfac_population.groupby(class_keys)

    def process_row(row):
        vehicleClass, fuelType, vehicleTypeId = row['vehicleClass'], row['fuelType'], row['vehicleTypeId']

        try:
            # Try to get a group matching both vehicle class and fuel type
            group_key = (vehicleClass, fuelType) if is_statewide else (row['zone'], vehicleClass, fuelType)
            group = grouped_by_mappedFuelType.get_group(group_key)

            # Sample from the group based on population weights
            weights = group['population'] / group['population'].sum()
            sample = group.sample(n=1, weights=weights).iloc[0]

            emfacClass = sample['emfacClass'].replace(' ', '')
            fuel = sample['fuel']

        except KeyError:
            try:
                # If no match found, try to get a group matching only vehicle class
                class_key = (vehicleClass,) if is_statewide else (row['zone'], vehicleClass)
                group = grouped_by_mappedVehicleClass.get_group(class_key)

                # Sample from the group based on population weights
                weights = group['population'] / group['population'].sum()
                sample = group.sample(n=1, weights=weights).iloc[0]

                emfacClass = sample['emfacClass'].replace(' ', '')
                fuel = beam_fuel_to_emfac_fuel_map[fuelType]

            except KeyError:
                # If still no match found, return None values
                print(f"This vehicleClass [{vehicleClass}] not found in EMFAC! \nRow: {row}")
                return pd.Series({'emfacVehicleTypeId': None, 'emfacClass': None, 'emfacFuelType': None})

        # Construct the EMFAC vehicle type ID
        emfacVehicleTypeId = f"{vehicleTypeId}-{emfacClass}-{fuel}"

        return pd.Series({
            'emfacVehicleTypeId': emfacVehicleTypeId,
            'emfacClass': emfacClass,
            'emfacFuelType': fuel
        })

    # Apply the processing function to each row and combine results
    results = formatted_famos_population.apply(process_row, axis=1)

    # Join the results back to the original DataFrame
    return pd.concat([formatted_famos_population, results], axis=1)


def distribution_based_vehicle_classes_assignment(famos_df, emfac_df):
    # Remove 'Class 2b&3 Vocational' from EMFAC data
    emfac_df = emfac_df[emfac_df['mappedVehicleClass'] != class_2b3]

    def sample_emfac(the_class, the_fuel):
        emfac_grouped = emfac_df[(emfac_df['mappedVehicleClass'] == the_class) & (emfac_df['mappedFuelType'] == the_fuel)]
        if emfac_grouped.empty:
            emfac_grouped = emfac_df[emfac_df['mappedVehicleClass'] == the_class]
        return emfac_grouped.sample(n=1, weights='population')['emfacId'].iloc[0]

    total_emfac = emfac_df["population"].sum()
    class_46_share = emfac_df[emfac_df['mappedVehicleClass'] == class_46]["population"].sum() / total_emfac
    class_78_v_share = emfac_df[emfac_df['mappedVehicleClass'] == class_78_v]["population"].sum() / total_emfac
    total_famos = len(famos_df)
    class_46_target = int(class_46_share * total_famos)
    class_78_v_target = int(class_78_v_share * total_famos)

    class_46_count = 0
    class_78_v_count = 0

    def sample_emfac_class(row):
        nonlocal class_46_count, class_78_v_count

        if class_46_count < class_46_target:
            if row['vehicleClass'] == class_46:
                class_46_count += 1
                return sample_emfac(class_46, row['fuelType'])

            if row['vehicleClass'] == class_78_v:
                class_46_count += 1
                return sample_emfac(class_46, row['fuelType'])

            if row['vehicleClass'] == class_78_t:
                class_46_count += 1
                return sample_emfac(class_46, row['fuelType'])

        if class_78_v_count < class_78_v_target:
            if row['vehicleClass'] == class_78_v:
                class_78_v_count += 1
                return sample_emfac(class_78_v, row['fuelType'])

            if row['vehicleClass'] == class_78_t:
                class_78_v_count += 1
                return sample_emfac(class_78_v, row['fuelType'])

        return sample_emfac(class_78_t, row['fuelType'])

    famos_df['vehicleClassBis'] = famos_df['vehicleClass'].map({class_46: 1, class_78_v: 2, class_78_t: 3})
    famos_df['emfacId'] = famos_df.sort_values('vehicleClassBis').apply(sample_emfac_class, axis=1)
    famos_df["oldVehicleTypeId"] = famos_df["vehicleTypeId"]
    famos_df['vehicleTypeId'] = famos_df.apply(
        lambda row: sanitize_filename(f"{row['emfacId']}-{row['oldVehicleTypeId'].split('-')[-1]}"),
        axis=1
    )
    merged = pd.merge(famos_df, emfac_df, on="emfacId", how="left").drop(["vehicleClassBis"], axis=1)
    return merged


def pivot_rates_for_beam(df_raw):
    unique_speed_time = df_raw.speed_time.unique()
    if len(unique_speed_time) > 0 and not np.isnan(unique_speed_time[0]):
        index_ = ["vehicle_class", "fuel", 'sub_area', 'process', 'speed_time']
    else:
        index_ = ["vehicle_class", "fuel", 'sub_area', 'process']
    pivot_df = df_raw.pivot_table(index=index_, columns='pollutant', values='emission_rate', aggfunc='first', fill_value=0).reset_index()
    pivot_df = pivot_df.rename(columns=pollutant_columns)
    # Add missing columns with default values
    for col in pollutant_columns.values():
        if col not in pivot_df.columns:
            pivot_df[col] = 0.0
    pivot_df.insert(0, 'speed_mph_float_bins', "")
    pivot_df.insert(1, 'time_minutes_float_bins', "")
    return pivot_df


def numerical_column_to_binned(df_raw, numerical_colname, binned_colname, edge_values):
    pivot_df = pivot_rates_for_beam(df_raw).sort_values(by='speed_time', ascending=True)
    df_raw_last_row = pivot_df.iloc[-1].copy()
    df_raw_last_row['speed_time'] = edge_values[1]
    pivot_df = pd.concat([pivot_df, pd.DataFrame([df_raw_last_row])], ignore_index=True)
    col_sorted = sorted(pivot_df[numerical_colname].unique())
    col_bins = [edge_values[0]] + col_sorted
    col_labels = [f"[{col_bins[i]}, {col_bins[i + 1]})" for i in range(len(col_bins) - 1)]
    pivot_df[binned_colname] = pd.cut(pivot_df[numerical_colname], bins=col_bins, labels=col_labels, right=True)
    return pivot_df


def process_rates_group(df, row):
    mask = (
            (df["sub_area"] == row["sub_area"]) &
            (df["vehicle_class"] == row["vehicle_class"]) &
            (df["fuel"] == row["fuel"])
    )
    df_subset = df[mask]
    df_output_list = []
    for process in emissions_processes:
        df_temp = df_subset[df_subset['process'] == process]
        if not df_temp.empty:
            if process in ['RUNEX', 'PMBW']:
                df_temp = numerical_column_to_binned(df_temp, 'speed_time', 'speed_mph_float_bins', [0.0, 200.0])
            elif process == 'STREX':
                df_temp = numerical_column_to_binned(df_temp, 'speed_time', 'time_minutes_float_bins', [0.0, 3600.0])
            else:
                df_temp = pivot_rates_for_beam(df_temp)
            df_output_list.append(df_temp)

    return pd.concat(df_output_list, ignore_index=True)


def format_rates_for_beam(emissions_rates, calendar_year=2018, season="Annual", humidity=40, temperature=65):
    from joblib import Parallel, delayed

    # Filter and format emissions data
    df_filtered = emissions_rates[
        (emissions_rates["calendar_year"] == calendar_year) &
        (emissions_rates["season_month"] == season) &
        ((emissions_rates["relative_humidity"] == humidity) | (emissions_rates["relative_humidity"].isna())) &
        ((emissions_rates["temperature"] == temperature) | (emissions_rates["temperature"].isna()))
    ].drop(['calendar_year', 'season_month', 'relative_humidity', 'temperature'], axis=1)

    # Pivot table to spread pollutants into columns
    print("Formatting emissions...")

    # Assuming emissions_rates is already loaded into a DataFrame `df`
    group_by_cols = ["sub_area", "vehicle_class", "fuel"]
    df_unique = df_filtered[group_by_cols].drop_duplicates().reset_index(drop=True)

    # Parallel processing
    df_output_list = Parallel(n_jobs=-1)(delayed(process_rates_group)(df_filtered, row) for index, row in df_unique.iterrows())

    # Formatting for merge
    df_output = pd.concat(df_output_list, ignore_index=True)

    # Extract county information
    df_output['county'] = df_output['sub_area'].str.extract(r'^([^()]+)').squeeze().str.strip().str.lower()

    # Create emfacId
    df_output["emfacId"] = df_output['vehicle_class'].str.replace(' ', '-') + "-" + df_output['fuel']

    # Drop unnecessary columns
    columns_to_drop = ["sub_area", "vehicle_class", "fuel", "speed_time"]
    df_output = df_output.drop(columns_to_drop, axis=1)

    # Reorder columns to ensure 'county' is at the front
    columns = df_output.columns.tolist()
    columns = ['county'] + [col for col in columns if col != 'county']
    df_output = df_output[columns]
    return df_output


def process_single_vehicle_type(veh_type, taz_emissions_rates, emissions_rates_dir, emissions_rates_file_prefix):
    veh_type_id = veh_type['vehicleTypeId']
    emfac_id = veh_type['emfacId']

    # Filter taz_emissions_rates for the current vehicle type
    veh_emissions = taz_emissions_rates[taz_emissions_rates['emfacId'] == emfac_id].copy()

    if not veh_emissions.empty:
        # Remove the emfacId column as it's no longer needed
        veh_emissions = veh_emissions.drop('emfacId', axis=1)

        # Generate the file name
        file_name = emissions_filename(veh_type_id)
        file_path = os.path.join(emissions_rates_dir, file_name)

        print("Writing " + file_path)
        # Save the emissions rates to a CSV file
        veh_emissions.to_csv(file_path, index=False)

        return veh_type_id, emissions_rates_file_prefix + file_name
    else:
        print(f"Warning: No emissions data found for vehicle type {veh_type_id}")
        return veh_type_id, None


def sanitize_filename(filename):
    # First, replace forward slashes with dashes
    sanitized = filename.replace('/', '-')
    # Then remove or replace any other non-alphanumeric characters (except dashes)
    sanitized = re.sub(r'[^\w\-]', '-', sanitized)
    # Replace any sequence of dashes with a single dash
    sanitized = re.sub(r'-+', '-', sanitized)
    # Remove leading and trailing dashes
    sanitized = sanitized.strip('-')
    return sanitized


def emissions_filename(filename):
    return f"""emissions-rates--{sanitize_filename(filename)}.csv"""


def process_vehicle_types_emissions(taz_emissions_rates, vehicle_types, output_dir, emissions_rates_file_prefix):
    from joblib import Parallel, delayed
    emissions_rates_dir = os.path.abspath(output_dir)
    os.makedirs(emissions_rates_dir, exist_ok=True)

    print("Mapping to vehicle types ...")
    # Use parallel processing with error handling and chunking
    chunk_size = 100  # Adjust this value based on your data size and available memory
    results = []

    for i in range(0, len(vehicle_types), chunk_size):
        chunk = vehicle_types.iloc[i:i + chunk_size]

        chunk_results = Parallel(n_jobs=-1, timeout=600)(  # 10-minute timeout
            delayed(process_single_vehicle_type)(
                veh_type,
                taz_emissions_rates,
                emissions_rates_dir,
                emissions_rates_file_prefix
            ) for _, veh_type in chunk.iterrows()
        )

        results.extend(chunk_results)

        # Clear some memory
        del chunk_results

    # Update the vehicle_types DataFrame with the new emissionsRatesFile information
    for veh_type_id, emissions_file in results:
        if emissions_file:
            vehicle_types.loc[vehicle_types['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'] = emissions_file

    print("Completed!")
    return vehicle_types


def build_vehicle_types_for_emissions(updated_famos_population, famos_vehicle_types):
    # Create a copy of the original vehicleTypeId and set up a lookup dictionary
    famos_vehicle_types_dict = famos_vehicle_types.set_index("vehicleTypeId").to_dict('index')

    # Remove duplicates based on vehicleTypeId, keeping the first occurrence
    unique_vehicle_types = updated_famos_population.drop_duplicates(subset='vehicleTypeId', keep='first')

    def process_row(row):
        new_row = famos_vehicle_types_dict[row["oldVehicleTypeId"]].copy()
        new_row["vehicleTypeId"] = row["vehicleTypeId"]
        new_row['vehicleClass'] = row['mappedVehicleClass']
        new_row['vehicleCategory'] = class_to_category.get(row['mappedVehicleClass'], 'Unknown')
        new_row["emfacId"] = row['emfacId']
        return new_row

    # Apply process_row to the unique vehicle types
    result_df = pd.DataFrame(unique_vehicle_types.apply(process_row, axis=1).tolist())

    # Define the desired column order with 'vehicleTypeId' at the front
    columns_order = ['vehicleTypeId'] + [
        col for col in result_df.columns if col not in {'vehicleTypeId'}
    ]

    # Reorder the columns
    result_df = result_df[columns_order]

    return result_df


def update_carrier_file(carrier_df, updated_famos_population):
    vehicle_id_to_type_mapping = dict(zip(updated_famos_population['vehicleId'],
                                          updated_famos_population['vehicleTypeId']))

    def update_vehicle_type(row):
        type_id = vehicle_id_to_type_mapping.get(row['vehicleId'])
        if type_id is None:
            print(f"Warning: No updated type found for vehicleId {row['vehicleId']}")
            return row['vehicleTypeId']  # Keep the original type
        return type_id

    carrier_df['vehicleTypeId'] = carrier_df.apply(update_vehicle_type, axis=1)

    return carrier_df
