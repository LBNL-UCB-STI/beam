from emissions_utils import *


model_dir = '~/Workspace/Models/emfac/2018'

# Mapping dictionaries
vehicle_class_mapping = {
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
    'T6 Public Class 4': 'Class 4-6 Vocational',
    'T6 Public Class 5': 'Class 4-6 Vocational',
    'T6 Public Class 6': 'Class 4-6 Vocational',
    'T6 CAIRP Class 4': 'Class 4-6 Vocational',
    'T6 CAIRP Class 5': 'Class 4-6 Vocational',
    'T6 CAIRP Class 6': 'Class 4-6 Vocational',
    'T6 OOS Class 4': 'Class 4-6 Vocational',
    'T6 OOS Class 5': 'Class 4-6 Vocational',
    'T6 OOS Class 6': 'Class 4-6 Vocational',
    'T6 Instate Tractor Class 6': 'Class 4-6 Vocational',
    'T6TS': 'Class 4-6 Vocational',
    'T6 Utility Class 5': 'Class 4-6 Vocational',
    'T6 Utility Class 6': 'Class 4-6 Vocational',
    'T6 Utility Class 7': 'Class 4-6 Vocational',

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': 'Class 7&8 Vocational',
    'T6 Instate Other Class 7': 'Class 7&8 Vocational',
    'T6 Public Class 7': 'Class 7&8 Vocational',
    'T7 Public Class 8': 'Class 7&8 Vocational',
    'T7 Single Concrete/Transit Mix Class 8': 'Class 7&8 Vocational',
    'T7 Single Dump Class 8': 'Class 7&8 Vocational',
    'T7 Single Other Class 8': 'Class 7&8 Vocational',
    'T7IS': 'Class 7&8 Vocational',
    'T7 SWCV Class 8': 'Class 7&8 Vocational',

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': 'Class 7&8 Tractor',
    'T7 Tractor Class 8': 'Class 7&8 Tractor',
    'T6 CAIRP Class 7': 'Class 7&8 Tractor',
    'T6 OOS Class 7': 'Class 7&8 Tractor',
    'T7 CAIRP Class 8': 'Class 7&8 Tractor',
    'T7 NNOOS Class 8': 'Class 7&8 Tractor',
    'T7 NOOS Class 8': 'Class 7&8 Tractor',
    'T7 Other Port Class 8': 'Class 7&8 Tractor',
    'T7 POAK Class 8': 'Class 7&8 Tractor',
    'T7 POLA Class 8': 'Class 7&8 Tractor'
}

vehicle_category_mapping = {
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
    'T6 Public Class 4': 'MediumHeavyDutyTruck',
    'T6 Public Class 5': 'MediumHeavyDutyTruck',
    'T6 Public Class 6': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 4': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 5': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 6': 'MediumHeavyDutyTruck',
    'T6 OOS Class 4': 'MediumHeavyDutyTruck',
    'T6 OOS Class 5': 'MediumHeavyDutyTruck',
    'T6 OOS Class 6': 'MediumHeavyDutyTruck',
    'T6 Instate Tractor Class 6': 'MediumHeavyDutyTruck',
    'T6TS': 'MediumHeavyDutyTruck',
    'T6 Utility Class 5': 'MediumHeavyDutyTruck',
    'T6 Utility Class 6': 'MediumHeavyDutyTruck',
    'T6 Utility Class 7': 'MediumHeavyDutyTruck',

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': 'MediumHeavyDutyTruck',
    'T6 Instate Other Class 7': 'MediumHeavyDutyTruck',
    'T6 Public Class 7': 'MediumHeavyDutyTruck',

    'T7IS': 'HeavyHeavyDutyTruck',
    'T7 Public Class 8': 'HeavyHeavyDutyTruck',
    'T7 Single Concrete/Transit Mix Class 8': 'HeavyHeavyDutyTruck',
    'T7 Single Dump Class 8': 'HeavyHeavyDutyTruck',
    'T7 Single Other Class 8': 'HeavyHeavyDutyTruck',
    'T7 SWCV Class 8': 'HeavyHeavyDutyTruck',

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': 'MediumHeavyDutyTruck',
    'T6 CAIRP Class 7': 'MediumHeavyDutyTruck',
    'T6 OOS Class 7': 'MediumHeavyDutyTruck',

    'T7 Tractor Class 8': 'HeavyHeavyDutyTruck',
    'T7 CAIRP Class 8': 'HeavyHeavyDutyTruck',
    'T7 NNOOS Class 8': 'HeavyHeavyDutyTruck',
    'T7 NOOS Class 8': 'HeavyHeavyDutyTruck',
    'T7 Other Port Class 8': 'HeavyHeavyDutyTruck',
    'T7 POAK Class 8': 'HeavyHeavyDutyTruck',
    'T7 POLA Class 8': 'HeavyHeavyDutyTruck'
}

fuel_mapping = {
    'Dsl': 'Diesel',
    'Gas': 'Gasoline',
    'NG': 'NaturalGas',
    'Elec': 'Electricity',
    'Phe': 'PlugInHybridElectric'
}

# ## Population ##
sf_emfac_pop = pd.read_csv(model_dir + '/SF_Normalized_Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv')
famos_vehicle_types = pd.read_csv(model_dir + '/FAMOS/freight-only-vehicletypes--routee-baseline.csv')


# Prepare the new dataframe based on the format of freight_only_df
new_columns = famos_vehicle_types.columns
new_df = pd.DataFrame(columns=new_columns)


# Iterate over each row in the sf_normalized_df and find the closest match in freight_only_df
for index, row in sf_emfac_pop.iterrows():
    matched_class_fuel = famos_vehicle_types[
        (famos_vehicle_types['vehicleClass'] == vehicle_class_mapping[row['vehicle_class']]) &
        (famos_vehicle_types['primaryFuelType'] == fuel_mapping[row['fuel']])
    ]
    matched_class_phev = famos_vehicle_types[
        (famos_vehicle_types['vehicleClass'] == vehicle_class_mapping[row['vehicle_class']]) &
        (fuel_mapping[row['fuel']] == 'PlugInHybridElectric') &
        (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
        (famos_vehicle_types['secondaryFuelType'] == 'Diesel')
    ]
    matched_fuel_only = famos_vehicle_types[
        (famos_vehicle_types['primaryFuelType'] == fuel_mapping[row['fuel']]) |
        (fuel_mapping[row['fuel']] == 'PlugInHybridElectric') &
        (famos_vehicle_types['primaryFuelType'] == 'Electricity') &
        (famos_vehicle_types['secondaryFuelType'].isin(['Diesel', 'Gasoline']))
    ]

    if not matched_class_fuel.empty:
        new_row = matched_class_fuel.iloc[0].copy()
        new_row['emfacPopulationSize'] = row['sum_population']
        new_row['emfacPopulationPct'] = row['share_population']
        new_row['emfacVehicleClass'] = row['vehicle_class']
        new_row['vehicleCategory'] = vehicle_category_mapping[new_row['emfacVehicleClass']]
        new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
        new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

    elif not matched_class_phev.empty:
        new_row = matched_class_phev.iloc[0].copy()
        new_row['emfacPopulationSize'] = row['sum_population']
        new_row['emfacPopulationPct'] = row['share_population']
        new_row['emfacVehicleClass'] = row['vehicle_class']
        new_row['vehicleCategory'] = vehicle_category_mapping[new_row['emfacVehicleClass']]
        new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
        new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

    elif not matched_fuel_only.empty:
        new_row = matched_fuel_only.iloc[0].copy()
        new_row['emfacPopulationSize'] = row['sum_population']
        new_row['emfacPopulationPct'] = row['share_population']
        new_row['emfacVehicleClass'] = row['vehicle_class']
        new_row['vehicleCategory'] = vehicle_category_mapping[new_row['emfacVehicleClass']]

        ##
        new_row['vehicleClass'] = vehicle_class_mapping[row['vehicle_class']]
        new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
        new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

    elif fuel_mapping[row['fuel']] in ['NaturalGas', 'Gasoline']:
        matched_other_fuel = famos_vehicle_types[famos_vehicle_types['primaryFuelType'].isin(['Diesel', 'Gasoline'])]
        new_row = matched_other_fuel.iloc[0].copy()
        matched_class_only = famos_vehicle_types[
            (famos_vehicle_types['vehicleClass'] == vehicle_class_mapping[row['vehicle_class']])
        ]
        if not matched_class_only.empty:
            print(f"Class matches {matched_class_only.iloc[0]['vehicleClass']}. "
                  f"Make sure it is correct since we will be applying this fuel {new_row['primaryFuelType']} "
                  f"with vehicle class {new_row['vehicleClass']}")

        new_row['emfacPopulationSize'] = row['sum_population']
        new_row['emfacPopulationPct'] = row['share_population']
        new_row['emfacVehicleClass'] = row['vehicle_class']
        new_row['vehicleCategory'] = vehicle_category_mapping[new_row['emfacVehicleClass']]

        ##
        new_row['vehicleClass'] = vehicle_class_mapping[row['vehicle_class']]
        new_row['vehicleTypeId'] = (new_row['vehicleTypeId'] + '-' + new_row['emfacVehicleClass'].replace(' ', ''))
        new_df = pd.concat([new_df, pd.DataFrame([new_row])], ignore_index=True)

    else:
        print(f"This row failed to be added: {row}")


# Save the new dataframe to a CSV file
new_df.to_csv(model_dir + '/freight-only-vehicletypes--emfac--routee-baseline.csv', index=False)

# Display the first few rows of the new dataframe to verify
print(new_df.head())