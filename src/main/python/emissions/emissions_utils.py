import pandas as pd
import os
import numpy as np

# Mapping dictionaries
emfac_class_to_famos_class_map = {
    # Class 2b&3 Vocational
    'LHD1': 'Class 2b&3 Vocational',
    'LHD2': 'Class 2b&3 Vocational',

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': 'Class 4-6 Vocational',
    'T6 Instate Delivery Class 5': 'Class 4-6 Vocational',
    'T6 Instate Delivery Class 6': 'Class 4-6 Vocational',
    'T6 Instate Other Class 4': 'Class 4-6 Vocational',
    'T6 Instate Other Class 5': 'Class 4-6 Vocational',
    'T6 Instate Other Class 6': 'Class 4-6 Vocational',
    'T6 CAIRP Class 4': 'Class 4-6 Vocational',
    'T6 CAIRP Class 5': 'Class 4-6 Vocational',
    'T6 CAIRP Class 6': 'Class 4-6 Vocational',
    'T6 OOS Class 4': 'Class 4-6 Vocational',
    'T6 OOS Class 5': 'Class 4-6 Vocational',
    'T6 OOS Class 6': 'Class 4-6 Vocational',
    'T6 Instate Tractor Class 6': 'Class 4-6 Vocational',

    'T6 Instate Delivery Class 7': 'Class 7&8 Vocational',
    'T6 Instate Other Class 7': 'Class 7&8 Vocational',
    'T7 Single Concrete/Transit Mix Class 8': 'Class 7&8 Vocational',
    'T7 Single Dump Class 8': 'Class 7&8 Vocational',
    'T7 Single Other Class 8': 'Class 7&8 Vocational',
    'T7IS': 'Class 7&8 Vocational',  # In-State

    'T6 Instate Tractor Class 7': 'Class 7&8 Tractor',
    'T7 Tractor Class 8': 'Class 7&8 Tractor',
    'T6 CAIRP Class 7': 'Class 7&8 Tractor',
    'T6 OOS Class 7': 'Class 7&8 Tractor',
    'T7 CAIRP Class 8': 'Class 7&8 Tractor',
    'T7 NNOOS Class 8': 'Class 7&8 Tractor',
    'T7 NOOS Class 8': 'Class 7&8 Tractor',
}
emfac_class_to_regulatory_class_map = {
    # Class 2b&3 Vocational
    'LHD1': 'Class 2b',
    'LHD2': 'Class 3',

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': 'Class 4',
    'T6 Instate Delivery Class 5': 'Class 5',
    'T6 Instate Delivery Class 6': 'Class 6',
    'T6 Instate Other Class 4': 'Class 4',
    'T6 Instate Other Class 5': 'Class 5',
    'T6 Instate Other Class 6': 'Class 6',
    'T6 CAIRP Class 4': 'Class 4',
    'T6 CAIRP Class 5': 'Class 5',
    'T6 CAIRP Class 6': 'Class 6',
    'T6 OOS Class 4': 'Class 4',
    'T6 OOS Class 5': 'Class 5',
    'T6 OOS Class 6': 'Class 6',
    'T6 Instate Tractor Class 6': 'Class 6',

    'T6 Instate Delivery Class 7': 'Class 7',
    'T6 Instate Other Class 7': 'Class 7',
    'T7 Single Concrete/Transit Mix Class 8': 'Class 8',
    'T7 Single Dump Class 8': 'Class 8',
    'T7 Single Other Class 8': 'Class 8',
    'T7IS': 'Class 7',  # In-State

    'T6 Instate Tractor Class 7': 'Class 7',
    'T7 Tractor Class 8': 'Class 8',
    'T6 CAIRP Class 7': 'Class 7',
    'T6 OOS Class 7': 'Class 7',
    'T7 CAIRP Class 8': 'Class 8',
    'T7 NNOOS Class 8': 'Class 8',
    'T7 NOOS Class 8': 'Class 8',
}
emfac_class_to_vehicle_category_map = {
    # Class 2b&3 Vocational
    'LHD1': 'LightHeavyDutyTruck',
    'LHD2': 'LightHeavyDutyTruck',

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': 'MediumHeavyDutyTruck',
    'T6 Instate Delivery Class 5': 'MediumHeavyDutyTruck',
    'T6 Instate Delivery Class 6': 'MediumHeavyDutyTruck',
    'T6 Instate Other Class 4': 'MediumHeavyDutyTruck',
    'T6 Instate Other Class 5': 'MediumHeavyDutyTruck',
    'T6 Instate Other Class 6': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 4': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 5': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 6': 'MediumHeavyDutyTruck',
    'T6 OOS Class 4': 'MediumHeavyDutyTruck',
    'T6 OOS Class 5': 'MediumHeavyDutyTruck',
    'T6 OOS Class 6': 'MediumHeavyDutyTruck',
    'T6 Instate Tractor Class 6': 'MediumHeavyDutyTruck',

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': 'MediumHeavyDutyTruck',
    'T6 Instate Other Class 7': 'MediumHeavyDutyTruck',

    'T7IS': 'HeavyHeavyDutyTruck',
    'T7 Single Concrete/Transit Mix Class 8': 'HeavyHeavyDutyTruck',
    'T7 Single Dump Class 8': 'HeavyHeavyDutyTruck',
    'T7 Single Other Class 8': 'HeavyHeavyDutyTruck',

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 7': 'MediumHeavyDutyTruck',
    'T6 OOS Class 7': 'MediumHeavyDutyTruck',

    'T7 Tractor Class 8': 'HeavyHeavyDutyTruck',
    'T7 CAIRP Class 8': 'HeavyHeavyDutyTruck',
    'T7 NNOOS Class 8': 'HeavyHeavyDutyTruck',
    'T7 NOOS Class 8': 'HeavyHeavyDutyTruck',
}
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
    import re
    folder_path = os.path.dirname(emfac_data_filepath)
    file_name = os.path.basename(emfac_data_filepath)
    emfac_regions_label = '-'.join([fr"{re.escape(region)}" for region in emfac_regions])
    return folder_path + "/" + emfac_regions_label + "_" + label + file_name


def get_regional_emfac_data(emfac_data_filepath, emfac_regions):
    import re
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


def prepare_emfac_population_for_mapping(emfac_population_file,
                                         formatted_emfac_population_file,
                                         emfac_set_vehicle_class_mapping,
                                         fuel_assumption_mapping):
    # import re
    emfac_population = pd.read_csv(emfac_population_file, dtype=str)
    emfac_population['mappedVehicleClass'] = emfac_population['vehicle_class'].map(emfac_set_vehicle_class_mapping)
    formatted_emfac_population = emfac_population.dropna(subset=['mappedVehicleClass'])
    if len(formatted_emfac_population["vehicle_class"].unique()) != len(emfac_set_vehicle_class_mapping):
        print("Something wrong happened with the mapping")
    if not formatted_emfac_population['fuel'].isin(emfac_fuel_to_beam_fuel_map.keys()).all():
        print("Missing fuel type from dictionary!!!")
    formatted_emfac_population = formatted_emfac_population.assign(
        mappedFuelType=lambda x: x['fuel'].map(fuel_assumption_mapping),
        # zone=lambda x: x['sub_area'].str.replace(re.compile(r'\(SF\)'), '', regex=True).str.strip(),
        population=lambda x: x['population'].astype(float)
    )
    formatted_emfac_population = formatted_emfac_population.drop(['calendar_year', 'sub_area'], axis=1)
    formatted_emfac_population = formatted_emfac_population.rename(columns={'vehicle_class': 'emfacClass'})
    formatted_emfac_population.to_csv(formatted_emfac_population_file, index=False)

    return formatted_emfac_population


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


def prepare_famos_population_for_mapping(freight_carriers_formatted, freight_payloads_file, freight_vehicletypes_file):
    freight_payloads = pd.read_csv(freight_payloads_file)[['payloadId', 'tourId', 'payloadType']]
    freight_payloads_summary = freight_payloads.groupby(['tourId']).agg(
        {'payloadType': lambda x: '|'.join(map(str, x))}).reset_index()
    freight_payloads_merged = pd.merge(freight_payloads_summary, freight_carriers_formatted, on='tourId', how='left')
    freight_vehicletypes = pd.read_csv(freight_vehicletypes_file)[
        ['vehicleTypeId', 'vehicleClass', 'primaryFuelType', 'secondaryFuelType']]
    freight_vehicletypes['fuelType'] = np.where(
        (freight_vehicletypes['primaryFuelType'] == emfac_fuel_to_beam_fuel_map["Elec"]) & freight_vehicletypes[
            'secondaryFuelType'].notna(),
        emfac_fuel_to_beam_fuel_map['Phe'],
        freight_vehicletypes['primaryFuelType']
    )
    freight_vehicletypes = freight_vehicletypes[['vehicleTypeId', 'vehicleClass', 'fuelType']]
    freight_payloads_vehtypes = pd.merge(freight_payloads_merged, freight_vehicletypes, on='vehicleTypeId', how='left')
    if not freight_payloads_vehtypes[freight_payloads_vehtypes['fuelType'].isna()].empty:
        print("Something went wrong with the mapping of freight vehicle types. Missing vehicleTypeId?")
        print(freight_payloads_vehtypes[freight_payloads_vehtypes['fuelType'].isna()])
    formatted_famos_population = freight_payloads_vehtypes.drop_duplicates('vehicleId', keep='first')

    return formatted_famos_population


def map_famos_emfac_freight_population(formatted_famos_population,
                                       formatted_emfac_population,
                                       famos_emfac_freight_population_mapping_file,
                                       is_statewide):

    if is_statewide:
        grouped_by_mappedFuelType = formatted_emfac_population.groupby(['mappedVehicleClass', 'mappedFuelType'])
        grouped_by_mappedVehicleClass = formatted_emfac_population.groupby(['mappedVehicleClass'])
    else:
        grouped_by_mappedFuelType = formatted_emfac_population.groupby(['zone', 'mappedVehicleClass', 'mappedFuelType'])
        grouped_by_mappedVehicleClass = formatted_emfac_population.groupby(['zone', 'mappedVehicleClass'])

    # List to store the results which will be merged later
    results = []

    # Loop over each row in the vehtypes DataFrame
    for index, row in formatted_famos_population.iterrows():
        vehicleClass, fuelType, vehicleTypeId = row['vehicleClass'], row['fuelType'], row['vehicleTypeId']

        try:
            if is_statewide:
                group = grouped_by_mappedFuelType.get_group((vehicleClass, fuelType))
            else:
                group = grouped_by_mappedFuelType.get_group((row['zone'], vehicleClass, fuelType))
            weights = group['population'] / group['population'].sum()
            sample = group.sample(n=1, weights=weights)
            emfacClass = sample['emfacClass'].iloc[0].replace(' ', '')
            fuel = sample['fuel'].iloc[0]
            sample['emfacFuelType'] = fuel
            sample['emfacVehicleTypeId'] = vehicleTypeId + "-" + emfacClass + "-" + fuel
            result_row = sample[['emfacVehicleTypeId', 'emfacClass', 'emfacFuelType']].iloc[0]
        except KeyError:
            result_row = None

        try:
            if result_row is None:
                if is_statewide:
                    group2 = grouped_by_mappedVehicleClass.get_group((vehicleClass))
                else:
                    group2 = grouped_by_mappedVehicleClass.get_group((row['zone'], vehicleClass))
                weights2 = group2['population'] / group2['population'].sum()
                sample2 = group2.sample(n=1, weights=weights2)
                emfacClass = sample2['emfacClass'].iloc[0].replace(' ', '')
                fuel = beam_fuel_to_emfac_fuel_map[fuelType]
                sample2['emfacFuelType'] = fuel
                sample2['emfacVehicleTypeId'] = vehicleTypeId + "-" + emfacClass + "-" + fuel
                result_row = sample2[['emfacVehicleTypeId', 'emfacClass', 'emfacFuelType']].iloc[0]

        except KeyError:
            # Handle the case where no matching group is found
            print(f"This vehicleClass [{vehicleClass}] not found in emfac! \nRow: {row}")
            result_row = pd.Series({'emfacVehicleTypeId': None, 'emfacClass': None, 'emfacFuelType': None}, name=index)

        results.append(pd.Series(result_row, name=index))

    # Merge the results back to famos_freight_population
    famos_emfac_freight_population_mapping = formatted_famos_population.join(pd.concat(results, axis=1).transpose())
    famos_emfac_freight_population_mapping.to_csv(famos_emfac_freight_population_mapping_file, index=False)

    return famos_emfac_freight_population_mapping


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


def format_rates_for_beam(df):
    from joblib import Parallel, delayed

    # Pivot table to spread pollutants into columns
    print("Parallel processing ...")

    # Assuming emissions_rates is already loaded into a DataFrame `df`
    group_by_cols = ["sub_area", "vehicle_class", "fuel"]
    df_unique = df[group_by_cols].drop_duplicates().reset_index(drop=True)

    # Parallel processing
    df_output_list = Parallel(n_jobs=-1)(delayed(process_rates_group)(df, row) for index, row in df_unique.iterrows())

    print("Completed!")
    # Concatenate all collected DataFrames at once
    return pd.concat(df_output_list, ignore_index=True)
