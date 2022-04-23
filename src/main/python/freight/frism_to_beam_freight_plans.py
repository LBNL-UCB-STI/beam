import pandas as pd
import os
from pathlib import Path


def read_csv_file(filename_):
    compression = None
    if filename_.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(filename_, sep=",", index_col=None, header=0, compression=compression)


def add_prefix(prefix, column, row, to_num=True, store_dict=None, veh_type=False):
    if to_num:
        old = str(int(row[column]))
    else:
        old = str(row[column])
    old_updated = old.lower().replace('_', '-').replace('b2b-', '').replace('b2c-', '')
    second_prefix = ''
    if veh_type:
        if old == '1':
            second_prefix = '@md#'
        else:
            second_prefix = '@hd#'
    first_prefix = prefix.replace('county', 'cty')
    new = f"{first_prefix}{second_prefix}{old_updated}"
    if store_dict is not None:
        store_dict[old] = new
    return new


directory_input = os.path.expanduser('~/Data/FREIGHT/Tour_plan_inputs')
directory_output = os.path.expanduser('~/Data/FREIGHT/Tour_plan_inputs_merged')
Path(directory_output).mkdir(parents=True, exist_ok=True)
carriers = None
payload_plans = None
tours = None
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
        df['carrierId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}@', 'carrierId', row, False), axis=1)
        df['vehicleId'] = df.apply(lambda row: add_prefix(row['carrierId']+'#', 'vehicleId', row), axis=1)
        df['vehicleTypeId'] = df.apply(lambda row: add_prefix('freight', 'vehicleTypeId', row, to_num=True, store_dict=None, veh_type=True), axis=1)
        # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
        df['tourId'] = df.apply(lambda row: add_prefix(row['carrierId']+'#', 'tourId', row, True, tourId_with_prefix), axis=1)
        if carriers is None:
            carriers = df
        else:
            carriers = pd.concat([carriers, df])
    elif "freight_tours" in filetype:
        df = pd.read_csv(filepath)
        # df['tour_id'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tour_id', row), axis=1)
        df['tour_id'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tour_id']))], axis=1)
        if tours is None:
            tours = df
        else:
            tours = pd.concat([tours, df])
    elif "payload" in filetype:
        df = pd.read_csv(filepath)
        # df['tourId'] = df.apply(lambda row: add_prefix(f'{business_type}-{county}-', 'tourId', row), axis=1)
        df['tourId'] = df.apply(lambda row: tourId_with_prefix[str(int(row['tourId']))], axis=1)
        df['payloadId'] = df.apply(lambda row: add_prefix(row['tourId']+'#', 'payloadId', row, False), axis=1)
        if payload_plans is None:
            payload_plans = df
        else:
            payload_plans = pd.concat([payload_plans, df])
        tourId_with_prefix = {}
    else:
        print(f'SKIPPING {filetype}')


# In[9]:


# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
# carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY,MESOZONE,BoundaryZONE
carriers_renames = {
    'depot_zone': 'warehouseZone',
    'depot_zone_x': 'warehouseX',
    'depot_zone_y': 'warehouseY'
}
carriers_drop = ['x', 'y']
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
payload_plans_drop = ['weightInlb', 'cummulativeWeightInlb']
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