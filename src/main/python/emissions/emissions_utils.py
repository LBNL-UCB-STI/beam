import pandas as pd
import os
import re

# Mapping dictionaries
emfac_vehicle_class_mapping = {
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

emfac_vehicle_category_mapping = {
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

emfac_fuel_mapping = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'NaturalGas',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectric'
}


def get_regional_emfac_filename(emfac_data_filepath, emfac_regions, label=""):
    folder_path = os.path.dirname(emfac_data_filepath)
    file_name = os.path.basename(emfac_data_filepath)
    emfac_regions_label = '-'.join([fr"{re.escape(region)}" for region in emfac_regions])
    return folder_path + "/" + emfac_regions_label + "_" + label + file_name


def get_regional_emfac_data(emfac_data_filepath, emfac_regions):
    studyarea_x_filepath = get_regional_emfac_filename(emfac_data_filepath, emfac_regions)

    if os.path.exists(studyarea_x_filepath):
        print("Filtered EMFAC exists. Returning stored output: " + studyarea_x_filepath)
        return pd.read_csv(studyarea_x_filepath)
    else:
        # Load the dataset from the uploaded CSV file
        data = pd.read_csv(emfac_data_filepath)
        # Filter the data for each region in emfac_regions
        pattern = '|'.join([fr"\({re.escape(region)}\)" for region in emfac_regions])
        emfac_filtered = data[data["sub_area"].str.contains(pattern, case=False, na=False)]
        emfac_filtered.to_csv(studyarea_x_filepath, index=False)
        print("Done filtering EMFAC. The output has been stored in: " + studyarea_x_filepath)
        return emfac_filtered


def mapping_vehicle_types(emfac_pop_file_, famos_vehicle_types_file_, famos_emfac_file_out_):
    # ## Population ##
    sf_emfac_pop = pd.read_csv(emfac_pop_file_)
    famos_vehicle_types = pd.read_csv(famos_vehicle_types_file_)

    # Prepare the new dataframe based on the format of freight_only_df
    new_columns = famos_vehicle_types.columns
    new_df = pd.DataFrame(columns=new_columns)

    # Iterate over each row in the sf_normalized_df and find the closest match in freight_only_df
    for index, row in sf_emfac_pop.iterrows():
        matched_class_fuel = famos_vehicle_types[
            (famos_vehicle_types['vehicleClass'] == emfac_vehicle_class_mapping[row['vehicle_class']]) &
            (famos_vehicle_types['primaryFuelType'] == emfac_fuel_mapping[row['fuel']])
            ]
        matched_class_phev = famos_vehicle_types[
            (famos_vehicle_types['vehicleClass'] == emfac_vehicle_class_mapping[row['vehicle_class']]) &
            (emfac_fuel_mapping[row['fuel']] == 'PlugInHybridElectric') &
            (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
            (famos_vehicle_types['secondaryFuelType'] == 'Diesel')
            ]
        matched_fuel_only = famos_vehicle_types[
            (famos_vehicle_types['primaryFuelType'] == emfac_fuel_mapping[row['fuel']]) |
            (emfac_fuel_mapping[row['fuel']] == 'PlugInHybridElectric') &
            (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
            (famos_vehicle_types['secondaryFuelType'].isin(['Diesel', 'Gasoline']))
            ]

        if not matched_class_fuel.empty:
            new_row = matched_class_fuel.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_vehicle_category_mapping[new_row['emfacVehicleClass']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif not matched_class_phev.empty:
            new_row = matched_class_phev.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_vehicle_category_mapping[new_row['emfacVehicleClass']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif not matched_fuel_only.empty:
            new_row = matched_fuel_only.iloc[0].copy()
            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_vehicle_category_mapping[new_row['emfacVehicleClass']]

            ##
            new_row['vehicleClass'] = emfac_vehicle_class_mapping[row['vehicle_class']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        elif emfac_fuel_mapping[row['fuel']] in ['NaturalGas', 'Gasoline']:
            matched_other_fuel = famos_vehicle_types[
                famos_vehicle_types['primaryFuelType'].isin(['Diesel', 'Gasoline'])]
            new_row = matched_other_fuel.iloc[0].copy()
            matched_class_other_fuel = matched_other_fuel[
                matched_other_fuel['vehicleClass'] == emfac_vehicle_class_mapping[row['vehicle_class']]
                ]
            if not matched_class_other_fuel.empty:
                new_row = matched_class_other_fuel.iloc[0].copy()

            if emfac_vehicle_class_mapping[row['vehicle_class']] != new_row['vehicleClass']:
                print(f"There is a mismatch between vehicle class in FAMOS vehicle types and EMFAC vehicle types")
                print("===== FAMOS =====")
                print(new_row)
                print("===== EMFAC =====")
                print(row)

            new_row['emfacPopulationSize'] = row['sum_population']
            new_row['emfacPopulationPct'] = row['share_population']
            new_row['emfacVehicleClass'] = row['vehicle_class']
            new_row['vehicleCategory'] = emfac_vehicle_category_mapping[new_row['emfacVehicleClass']]

            ##
            new_row['vehicleClass'] = emfac_vehicle_class_mapping[row['vehicle_class']]
            new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
            new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

        else:
            print(f"This row failed to be added: {row}")

    # Save the new dataframe to a CSV file
    new_df.to_csv(famos_emfac_file_out_, index=False)
    return new_df
