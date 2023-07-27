import pandas as pd
import os
import sys
import utils

workspace = '~/Workspace/Data/FREIGHT/'
city = "austin" # "sfbay"
scenario = "parking-sensitivity"
batch = "2018_unlimited/"

events_file = utils.construct_events_file_path(workspace, city, scenario, batch)
if len(sys.argv) >= 2:
    events_file = str(sys.argv[1])
dir_name = os.path.dirname(events_file)
basename = os.path.basename(events_file)
log_file = dir_name + "/log." + basename

## *****************************************************

file_to_read = dir_name + "/park." + basename
if not os.path.exists(file_to_read):
    file_to_read = dir_name + "/" + basename

utils.print2(log_file, "reading: " + file_to_read)
#ParkingEvent, LeavingParkingEvent, ChargingPlugInEvent, ChargingPlugOutEvent, RefuelSessionEvent
data = utils.read_csv_in_chunks(file_to_read)
#data = utils.read_csv(dir_name + "/park." + basename)
utils.print2(log_file, "Read... " + str(data.type.unique()))

# filtering
data_filtered = data.loc[data.type.isin(["ParkingEvent", "LeavingParkingEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "RefuelSessionEvent"])]
data_filtered2 = data_filtered.loc[data_filtered.vehicle.str.startswith("carrier", na=False)]
utils.print2(log_file, data_filtered2.vehicleType.unique())

# saving
file_to_write = dir_name + "/park." + basename
data_filtered2.to_csv(file_to_write)
utils.print2(log_file, "writing to " + file_to_write)

# second filtering
data_filtered3 = data_filtered2.loc[data.type.isin(["ParkingEvent", "LeavingParkingEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent"])]
utils.print2(log_file, "Filtered and now counting number of vehicles parked at each parking zone")
# parkingTaz, parkingZoneId

# Mapping for event to increment/decrement values
import numpy as np
event_mapping = {
    'ParkingEvent': (1, 0),
    'LeavingParkingEvent': (-1, 0),
    'ChargingPlugInEvent': (0, 1),
    'ChargingPlugOutEvent': (0, -1),
}

# Map the type column to increment/decrement values
#data_filtered3['EventCounts'] = data_filtered3['type'].map(event_mapping)
data_filtered3['numVehicles'] = 0
data_filtered3['numChargingVehicles'] = 0
df_mapped = data_filtered3['type'].map(event_mapping)
df_mapped2 = pd.DataFrame(df_mapped.tolist(), columns=['numVehicles', 'numChargingVehicles'], index=data_filtered3.index)
data_filtered3.update(df_mapped2)
print(data_filtered3)
for col in ['numVehicles', 'numChargingVehicles']:
    data_filtered3[col] = data_filtered3.groupby(['parkingTaz', 'parkingZoneId'])[col].fillna(0).cumsum()
print(data_filtered3)
#data_filtered3[['numVehicles', 'numChargingVehicles']] = data_filtered3['type'].map(event_mapping)
#data_filtered3[['numVehicles', 'numChargingVehicles']] = data_filtered3.groupby(['parkingTaz', 'parkingZoneId'])[['numVehicles', 'numChargingVehicles']].cumsum()

# Use groupby with cumsum to get the cumulative sum for each group
# for col in ['numVehicles', 'numChargingVehicles']:
#     data_filtered3[col] = 0
#     data_filtered3[col] = data_filtered3.groupby(['parkingTaz', 'parkingZoneId'])[col].cumsum()
#     data_filtered2[['numVehicles', 'numChargingVehicles']] = data_filtered2['type'].map(event_mapping)

data_filtered3.to_csv(dir_name + "/" + "park2." + basename)
utils.print2(log_file, "END")
