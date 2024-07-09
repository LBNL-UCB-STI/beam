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
    "freight-md-E-PHEV-Baseline": ("Diesel", 9595.796035186175, 12000000000000000, "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
    # "freight-hdt-D-Diesel-Baseline": np.nan,
    # "freight-hdt-E-BE-Baseline": np.nan,
    # "freight-hdt-E-H2FC-Baseline": np.nan,
    "freight-hdt-E-PHEV-Baseline": ("Diesel", 13817.086117829229, 12000000000000000, "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
    # "freight-hdv-D-Diesel-Baseline": np.nan,
    # "freight-hdv-E-BE-Baseline": np.nan,
    # "freight-hdv-E-H2FC-Baseline": np.nan,
    "freight-hdv-E-PHEV-Baseline": ("Diesel", 14026.761465378302, 12000000000000000, "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
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


city = "sfbay"
scenario_name = "2024-01-23"
run_name = "Baseline"

directory_input = os.path.expanduser('~/Workspace/Data/FREIGHT/' + city + '/frism/'+scenario_name+"/"+run_name)
directory_output = os.path.expanduser('~/Workspace/Data/FREIGHT/' + city + '/beam_freight/'+scenario_name+"/"+run_name)
Path(directory_output).mkdir(parents=True, exist_ok=True)
carriers = None
payload_plans = None
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
        df['carrierId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'carrierId', row, False), axis=1).tolist()
        df['vehicleTypeId'] = df.apply(
            lambda row: add_prefix('freight-', 'vehicleTypeId', row, to_num=True, store_dict=None, veh_type=True, suffix="-"+run_name),
            axis=1).tolist()
        df['vehicleId'] = df.apply(lambda row: add_prefix(row['carrierId']+'-', 'vehicleId', row), axis=1).tolist()
        # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
        df['tourId'] = df.apply(lambda row: add_prefix(row['carrierId']+'-', 'tourId', row, True, tourId_with_prefix), axis=1).tolist()
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
        # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
        df['tourId'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tourId']))], axis=1).tolist()
        df['payloadId'] = df.apply(lambda row: add_prefix(row['tourId']+'-', 'payloadId', row, False), axis=1).tolist()
        if payload_plans is None:
            payload_plans = df
        else:
            payload_plans = pd.concat([payload_plans, df])
        tourId_with_prefix = {}
    elif "vehicle_types" in filename:
        df = pd.read_csv(filepath)
        empty_vectors = list(np.repeat("", len(df.index)))
        # JoulePerMeter = 121300000/(mpgge*1609.34)
        vehicle_types_ids = df.apply(lambda row: add_prefix('freight-', 'veh_type_id', row, to_num=True, store_dict=None, veh_type=True, suffix="-"+run_name), axis=1).tolist()
        vehicles_techs = {
            "vehicleTypeId": vehicle_types_ids,
            "seatingCapacity": list(np.repeat(1, len(df.index))),
            "standingRoomCapacity": list(np.repeat(0, len(df.index))),
            "lengthInMeter": list(np.repeat(12, len(df.index))),
            "primaryFuelType": df["primary_fuel_type"],
            "primaryFuelConsumptionInJoulePerMeter": np.divide(121300000, np.float_(df["primary_fuel_rate"])*1609.34),
            "primaryFuelCapacityInJoule": list(np.repeat(12000000000000000, len(df.index))),
            "primaryVehicleEnergyFile": [primary_energy_files[id] if id in primary_energy_files else np.nan for id in vehicle_types_ids],
            "secondaryFuelType": [secondary_energy_profile_for_phev[id][0] if id in secondary_energy_profile_for_phev else np.nan for id in vehicle_types_ids],
            "secondaryFuelConsumptionInJoulePerMeter": [secondary_energy_profile_for_phev[id][1] if id in secondary_energy_profile_for_phev else np.nan for id in vehicle_types_ids],
            "secondaryVehicleEnergyFile": [secondary_energy_profile_for_phev[id][3] if id in secondary_energy_profile_for_phev else np.nan for id in vehicle_types_ids],
            "secondaryFuelCapacityInJoule": [secondary_energy_profile_for_phev[id][2] if id in secondary_energy_profile_for_phev else np.nan for id in vehicle_types_ids],
            "automationLevel": list(np.repeat(1, len(df.index))),
            "maxVelocity": df["max_speed(mph)"], # convert to meter per second
            "passengerCarUnit": empty_vectors,
            "rechargeLevel2RateLimitInWatts": empty_vectors,
            "rechargeLevel3RateLimitInWatts": empty_vectors,
            "vehicleCategory": list(np.repeat("LightDutyTruck", len(df.index))),
            "sampleProbabilityWithinCategory": empty_vectors,
            "sampleProbabilityString": empty_vectors,
            "payloadCapacityInKg": df["payload_capacity_weight"]
        }
        df2 = pd.DataFrame(vehicles_techs)
        df2["vehicleCategory"] = np.where(df2["vehicleTypeId"].str.contains('hd'), 'HeavyDutyTruck', df2.vehicleCategory)
        if vehicle_types is None:
            vehicle_types = df2
        else:
            vehicle_types = pd.concat([vehicle_types, df2])
    else:
        print(f'SKIPPING {filename}')


vehicle_types.to_csv(f'{directory_output}/freight-only-vehicletypes--{run_name}.csv', index=False)


# In[9]:


# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
carriers_renames = {
    'depot_zone': 'warehouseZone',
    'depot_zone_x': 'warehouseX',
    'depot_zone_y': 'warehouseY'
}
carriers_drop = ['x', 'y', 'index']
carriers.rename(columns=carriers_renames, inplace=True)
carriers.drop(carriers_drop, axis=1, inplace=True, errors='ignore')
carriers['warehouseZone'] = carriers['warehouseZone'].astype(int)
carriers.to_csv(f'{directory_output}/freight-merged-carriers.csv', index=False)


# In[10]:


# tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
# tourId,departureTimeInSec,departureLocationZone,maxTourDurationInSec,departureLocationX,departureLocationY
tours_renames = {
    'tour_id': 'tourId',
    'departureLocation_zone': 'departureLocationZone',
    'departureLocation_x': 'departureLocationX',
    'departureLocation_y': 'departureLocationY'
}
tours.rename(columns=tours_renames, inplace=True)
tours['departureTimeInSec'] = tours['departureTimeInSec'].astype(int)
tours['maxTourDurationInSec'] = tours['maxTourDurationInSec'].astype(int)
tours['departureLocationZone'] = tours['departureLocationZone'].astype(int)
tours.drop(['index'], axis=1, inplace=True, errors='ignore')
tours.to_csv(f'{directory_output}/freight-merged-tours.csv', index=False)


# In[11]:


# payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationZone,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec,locationX,locationY
# payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationZone,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec,locationX,locationY
payload_plans_renames = {
    'arrivalTimeWindowInSec_lower': 'arrivalTimeWindowInSecLower',
    'arrivalTimeWindowInSec_upper': 'arrivalTimeWindowInSecUpper',
    'locationZone_x': 'locationX',
    'locationZone_y': 'locationY'
}
payload_plans_drop = ['weightInlb', 'cummulativeWeightInlb', 'index']
payload_plans['weightInKg'] = abs(payload_plans['weightInlb'].astype(int)) * 0.45359237
payload_plans.rename(columns=payload_plans_renames, inplace=True)
payload_plans.drop(payload_plans_drop, axis=1, inplace=True, errors='ignore')
payload_plans['sequenceRank'] = payload_plans['sequenceRank'].astype(int)
payload_plans['payloadType'] = payload_plans['payloadType'].astype(int)
payload_plans['requestType'] = payload_plans['requestType'].astype(int)
payload_plans['estimatedTimeOfArrivalInSec'] = payload_plans['estimatedTimeOfArrivalInSec'].astype(int)
payload_plans['arrivalTimeWindowInSecLower'] = payload_plans['arrivalTimeWindowInSecLower'].astype(int)
payload_plans['arrivalTimeWindowInSecUpper'] = payload_plans['arrivalTimeWindowInSecUpper'].astype(int)
payload_plans['operationDurationInSec'] = payload_plans['operationDurationInSec'].astype(int)
payload_plans['locationZone'] = payload_plans['locationZone'].astype(int)
payload_plans.to_csv(f'{directory_output}/freight-merged-payload-plans.csv', index=False)

print("END")