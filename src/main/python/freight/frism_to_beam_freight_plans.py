import pandas as pd
import os
from pathlib import Path
import numpy as np

primary_energy_files = {
    "freight-md-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(Diesel,_2020,_no_program).csv",
    "freight-md-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
    # "freight-md-E-H2FC-Baseline": np.nan,
    "freight-md-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
    "freight-hdt-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(Diesel,_2020,_no_program).csv",
    "freight-hdt-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
    "freight-hdv-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(Diesel,_2020,_no_program).csv",
    "freight-hdv-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv",
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv"
}

secondary_energy_profile_for_phev = {
    # "freight-md-D-Diesel-Baseline": np.nan,
    # "freight-md-E-BE-Baseline": np.nan,
    # "freight-md-E-H2FC-Baseline": np.nan,
    "freight-md-E-PHEV-Baseline": ("Diesel", 9595.796035186175, 12000000000000000,
                                   "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
    # "freight-hdt-D-Diesel-Baseline": np.nan,
    # "freight-hdt-E-BE-Baseline": np.nan,
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": ("Diesel", 13817.086117829229, 12000000000000000,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
    # "freight-hdv-D-Diesel-Baseline": np.nan,
    # "freight-hdv-E-BE-Baseline": np.nan,
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": ("Diesel", 14026.761465378302, 12000000000000000,
                                    "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
}


def read_csv_file(filename_):
    compression = None
    if filename_.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(filename_, sep=",", index_col=None, header=0, compression=compression)


def add_prefix(prefix, column, row, to_num=True, store_dict=None, veh_type=False, suffix=""):
    str_value = str(row[column])
    if to_num and str_value.isnumeric():
        old = str(int(row[column]))
    else:
        old = str(row[column])
    if veh_type:
        old_updated = old.replace('_', '-').replace('b2b-', '').replace('b2c-', ''). \
            replace('Battery Electric', 'BE').replace('H2 Fuel Cell', 'H2FC')
    else:
        old_updated = old.lower().replace('_', '-').replace('b2b-', '').replace('b2c-', '')
    second_prefix = ''
    # if veh_type:
    #     if old == '1':
    #         second_prefix = '-MD-'
    #     else:
    #         second_prefix = '-HD-'
    first_prefix = prefix
    if 'county' in prefix:
        first_prefix = first_prefix.replace('county', 'cty')

    new = f"{first_prefix}{second_prefix}{old_updated}{suffix}"
    if store_dict is not None:
        store_dict[old] = new
    return new


frism_version = 1.5
city = "sfbay"
scenario_name = "2024-08-07"
year, run_name = "2018", "Baseline"
# year, run_name = "2050", "Ref_highp6"
run_name_label = run_name.replace("_", "")

directory_input = os.path.expanduser(f'~/Workspace/Simulation/{city}/frism/{scenario_name}/{run_name}')
directory_output = os.path.expanduser(f'~/Workspace/Simulation/{city}/beam-freight/{scenario_name}/{year}_{run_name_label}')
Path(directory_output).mkdir(parents=True, exist_ok=True)
directory_vehicle_tech = f'{directory_output}/../vehicle-tech'
Path(directory_vehicle_tech).mkdir(parents=True, exist_ok=True)
carriers = None
payload_plans = None
ondemand_plans = None
tours = None
vehicle_types = None
tourId_with_prefix = {}

for filename in sorted(os.listdir(directory_input)):
    filepath = f'{directory_input}/{filename}'
    print(filepath)
    parts = filename.split('_', 2)
    if len(parts) < 3:
        print("Warning! could not read file: ", filename)
        continue
    business_type = parts[0].lower()
    county = parts[1].lower()
    filetype = parts[2].lower()

    if "carrier" in filetype:
        df = pd.read_csv(filepath)
        # df['carrierId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'carrierId', row), axis=1)
        # df['vehicleId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'vehicleId', row), axis=1)
        df['carrierId'] = df.apply(lambda row: add_prefix(f'', 'carrierId', row, False), axis=1).tolist()
        df['vehicleTypeId'] = df.apply(
            lambda row: add_prefix('', 'vehicleTypeId', row, to_num=True, store_dict=None, veh_type=True,
                                   suffix=f"-{year}-{run_name_label}"),
            axis=1).tolist()
        df['vehicleId'] = df.apply(lambda row: add_prefix(row['carrierId'] + '-', 'vehicleId', row), axis=1).tolist()
        # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
        df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-', 'tourId', row, True, tourId_with_prefix), axis=1).tolist()
        if carriers is None:
            carriers = df
        else:
            carriers = pd.concat([carriers, df])
    elif "freight_tours" in filetype:
        df = pd.read_csv(filepath)
        # df['tour_id'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tour_id', row), axis=1)
        df['tour_id'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tour_id']))], axis=1).tolist()
        if tours is None:
            tours = df
        else:
            tours = pd.concat([tours, df])
    elif "payload" in filetype:
        df = pd.read_csv(filepath)
        if "ondemand" in county:
            df['tourId'] = df.apply(lambda row: add_prefix(f'ridehail-', 'tourId', row), axis=1)
            if ondemand_plans is None:
                ondemand_plans = df
            else:
                ondemand_plans = pd.concat([ondemand_plans, df])
        else:
            df['tourId'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tourId']))], axis=1).tolist()
            df['payloadId'] = df.apply(lambda row: add_prefix('', 'payloadId', row, False), axis=1).tolist()
            tourId_with_prefix = {}
            if payload_plans is None:
                payload_plans = df
            else:
                payload_plans = pd.concat([payload_plans, df])
    elif "vehicle_types" in filename:
        df = pd.read_csv(filepath)
        empty_vectors = list(np.repeat("", len(df.index)))
        # JoulePerMeter = 121300000/(mpgge*1609.34)
        vehicle_types_ids = df.apply(
            lambda row: add_prefix('', 'veh_type_id', row, to_num=True, store_dict=None, veh_type=True,
                                   suffix=f"-{year}-{run_name_label}"), axis=1).tolist()
        vehicles_techs = {
            "vehicleTypeId": vehicle_types_ids,
            "seatingCapacity": list(np.repeat(1, len(df.index))),
            "standingRoomCapacity": list(np.repeat(0, len(df.index))),
            "lengthInMeter": list(np.repeat(12, len(df.index))),
            "primaryFuelType": df["primary_fuel_type"],
            "primaryFuelConsumptionInJoulePerMeter": np.divide(121300000, np.float64(df["primary_fuel_rate"]) * 1609.34),
            "primaryFuelCapacityInJoule": list(np.repeat(12000000000000000, len(df.index))),
            "primaryVehicleEnergyFile": [primary_energy_files[id] if id in primary_energy_files else np.nan for id in
                                         vehicle_types_ids],
            "secondaryFuelType": [
                secondary_energy_profile_for_phev[id][0] if id in secondary_energy_profile_for_phev else np.nan for id
                in vehicle_types_ids],
            "secondaryFuelConsumptionInJoulePerMeter": [
                secondary_energy_profile_for_phev[id][1] if id in secondary_energy_profile_for_phev else np.nan for id
                in vehicle_types_ids],
            "secondaryVehicleEnergyFile": [
                secondary_energy_profile_for_phev[id][3] if id in secondary_energy_profile_for_phev else np.nan for id
                in vehicle_types_ids],
            "secondaryFuelCapacityInJoule": [
                secondary_energy_profile_for_phev[id][2] if id in secondary_energy_profile_for_phev else np.nan for id
                in vehicle_types_ids],
            "automationLevel": list(np.repeat(1, len(df.index))),
            "maxVelocity": df["max_speed(mph)"],  # convert to meter per second
            "passengerCarUnit": empty_vectors,
            "rechargeLevel2RateLimitInWatts": empty_vectors,
            "rechargeLevel3RateLimitInWatts": empty_vectors,
            "vehicleCategory": list(np.repeat("Class456Vocational", len(df.index))),
            "sampleProbabilityWithinCategory": empty_vectors,
            "sampleProbabilityString": empty_vectors,
            "payloadCapacityInKg": df["payload_capacity_weight"],
            "vehicleClass": df["veh_class"]
        }
        df2 = pd.DataFrame(vehicles_techs)
        df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('hdv'), 'Class78Vocational',
                                          df2.vehicleCategory)
        df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('hdt'), 'Class78Tractor',
                                          df2.vehicleCategory)
        df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('ld'), 'Class2b3Vocational',
                                          df2.vehicleCategory)
        if vehicle_types is None:
            vehicle_types = df2
        else:
            vehicle_types = pd.concat([vehicle_types, df2])
    else:
        print(f'SKIPPING {filename}')

vehicle_types.to_csv(f'{directory_vehicle_tech}/ft-vehicletypes--{scenario_name.replace("-", "")}--{year}-{run_name_label}.csv', index=False)

# In[9]:


# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
carriers_renames = {
    'depot_zone': 'warehouseZone',
    'depot_zone_x': 'warehouseX',
    'depot_zone_y': 'warehouseY',
    'true_depot_zone': 'mesoZone'
}
carriers_drop = ['x', 'y', 'index']
carriers.rename(columns=carriers_renames, inplace=True)
carriers.drop(carriers_drop, axis=1, inplace=True, errors='ignore')
carriers['warehouseZone'] = carriers['warehouseZone'].astype(int)
carriers.to_csv(f'{directory_output}/carriers--{year}-{run_name_label}.csv', index=False)

# In[10]:


# tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
# tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
tours_renames = {
    'tour_id': 'tourId',
    'departureLocation_zone': 'departureLocationZone',
    'departureLocation_x': 'departureLocationX',
    'departureLocation_y': 'departureLocationY',
    'true_depot_zone': 'mesoZone'
}
tours.rename(columns=tours_renames, inplace=True)
tours['departureTimeInSec'] = tours['departureTimeInSec'].astype(int)
tours['maxTourDurationInSec'] = tours['maxTourDurationInSec'].astype(int)
tours['departureLocationZone'] = tours['departureLocationZone'].astype(int)
tours.drop(['index'], axis=1, inplace=True, errors='ignore')
tours.to_csv(f'{directory_output}/tours--{year}-{run_name_label}.csv', index=False)

# In[11]:


# payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationZone,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec,locationX,locationY
def format_payload(_payload):
    payload_plans_renames = {
        'arrivalTimeWindowInSec_lower': 'arrivalTimeWindowInSecLower',
        'arrivalTimeWindowInSec_upper': 'arrivalTimeWindowInSecUpper',
        'locationZone_x': 'locationX',
        'locationZone_y': 'locationY',
        'true_locationZone': 'mesoZone',
        'BuyerNAICS': "buyerNAICS",
        "SellerNAICS": "sellerNAICS"
    }
    payload_plans_drop = ['truck_mode', 'weightInlb', 'cummulativeWeightInlb', 'index']
    int_columns = ['sequenceRank', 'payloadType', 'requestType', 'estimatedTimeOfArrivalInSec',
                   'arrivalTimeWindowInSecLower', 'arrivalTimeWindowInSecUpper',
                   'operationDurationInSec', 'locationZone']
    payload_type_map = {
        1: 'bulk',
        2: 'fuel_fert',
        3: 'interm_food',
        4: 'mfr_goods',
        5: 'others'
    }

    _payload.rename(columns=payload_plans_renames, inplace=True)

    # Convert columns to integer type
    for col in int_columns:
        _payload[col] = _payload[col].astype(int)

    _payload['payloadType'] = _payload['payloadType'].map(payload_type_map)
    # Convert weightInlb to weightInKg without applying abs yet
    _payload['weightInKg'] = _payload['weightInlb'].astype(float) * 0.45359237

    # Apply modifications for frism version > 1.0
    if frism_version > 1.0:
        # Create DeliveryType column
        _payload['deliveryType'] = _payload['requestType'].map({1: 'delivery-only', 3: 'pickup-delivery'})

        # Update requestType based on weightInKg
        _payload['requestType'] = _payload['requestType'].astype('object')
        _payload.loc[_payload['weightInKg'] < 0, 'requestType'] = 'unloading'
        _payload.loc[_payload['weightInKg'] >= 0, 'requestType'] = 'loading'

        # Now make weightInKg positive
        _payload['weightInKg'] = np.abs(_payload['weightInKg'])

    else:
        # For frism version 1.0, just ensure weightInKg is positive
        _payload['requestType'] = _payload['requestType'].map({1: 'unloading', 0: 'loading'})
        _payload['weightInKg'] = np.abs(_payload['weightInKg'])

    _payload['fleetType'] = _payload['truck_mode'].map({
        'Private Truck': 'private',
        'For-hire Truck': 'for-hire'
    }, na_action='ignore')  # This keeps NA values as they are
    _payload.drop(payload_plans_drop, axis=1, inplace=True, errors='ignore')
    return _payload


# Save the modified DataFrame
format_payload(payload_plans).to_csv(f'{directory_output}/payloads--{year}-{run_name_label}.csv', index=False)

if ondemand_plans is not None:
    format_payload(ondemand_plans).to_csv(f'{directory_output}/ondemand--{year}-{run_name_label}.csv', index=False)


print("END")
