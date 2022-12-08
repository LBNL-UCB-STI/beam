import pandas as pd
import os
import sys


def print2(msg):
    with open(full_filename + ".out", 'w') as f:
        print(msg, file=f)


work_directory = '~/Data/GEMINI/2022-07-05/'
filename = '0.events.a.csv.gz'
events1 = ["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"]
columns1 = ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType", "locationY", "locationX",
            "duration", "vehicleType", "person", "fuel", "parkingZoneId", "pricingModel", "actType"]
events2 = ["PathTraversal", "ModeChoice"]
columns2 = []
events3 = ["PathTraversal", "RefuelSessionEvent"]
columns3 = []

event_set_type = 1
event_set = events1
columns_set = columns1
full_filename = os.path.expanduser(work_directory + "/" + filename)
# full_filename = os.path.expanduser(work_directory + "events-raw/" + filename)

if len(sys.argv) >= 2:
    full_filename = str(sys.argv[1])
print("reading " + filename)
compression = None
if full_filename.endswith(".gz"):
    compression = 'gzip'

if len(sys.argv) >= 3:
    event_set_type = str(sys.argv[2])

if event_set_type == 2:
    event_set = events2
    columns_set = columns2
elif event_set_type == 3:
    event_set = events3
    columns_set = columns3
else:
    event_set = events1
    columns_set = columns1


print2("reading " + filename)
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print2("filtering 1/2...")
data_filtered = data.loc[data.type.isin(event_set)]
print2("filtering 2/2...")
if columns_set:
    data_filtered = data_filtered[columns_set]
print2("writing...")
data_filtered.to_csv(work_directory + "/filtered." + filename)
print2("END")
