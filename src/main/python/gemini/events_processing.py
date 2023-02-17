import pandas as pd
import os
import sys


events1 = ["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"]
columns1 = ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType", "locationY", "locationX",
            "duration", "vehicleType", "person", "fuel", "parkingZoneId", "pricingModel", "actType"]
events2 = ["PathTraversal", "ModeChoice"]
columns2 = []
events3 = ["PathTraversal", "RefuelSessionEvent"]
columns3 = []
events4 = ["PathTraversal", "RefuelSessionEvent"]
columns4 = []

event_set_type = "pev-siting"
event_set = events1
columns_set = columns1
full_filename = os.path.expanduser('~/Data/GEMINI/2022-07-05/0.events.a.csv.gz')
# full_filename = os.path.expanduser(work_directory + "events-raw/" + filename)

if len(sys.argv) >= 2:
    full_filename = str(sys.argv[1])


def print2(msg):
    with open(full_filename + ".out", 'w') as f:
        print(msg)
        print(msg, file=f)

dirname = os.path.dirname(full_filename)
basename = os.path.basename(full_filename)

compression = None
if basename.endswith(".gz"):
    compression = 'gzip'

if len(sys.argv) >= 3:
    event_set_type = str(sys.argv[2])

if event_set_type == "pt-mc":
    event_set = events2
    columns_set = columns2
elif event_set_type == "pt-rs":
    event_set = events4
    columns_set = columns4
elif event_set_type == "rhev-siting":
    event_set = events3
    columns_set = columns3
else:
    event_set = events1
    columns_set = columns1

print2("reading " + full_filename)
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print2("filtering 1/3...")
data_filtered = data.loc[data.type.isin(event_set)]
print2("filtering 2/3...")
if event_set_type == "rhev-siting":
    data_filtered = data_filtered.loc[data.vehicle.astype(str).str.startswith('rideHail')]
    data_filtered["isRideHail"] = True
    data_filtered["vehicleType2"] = "CONV-RH"
    data_filtered.loc[data.vehicleType.astype(str).str.startswith('ev-')]["vehicleType2"] = "PEV-RH"
    data_filtered.loc[data.vehicleType.astype(str).str.startswith('phev-')]["vehicleType2"] = "PEV-RH"
print2("filtering 3/3...")
if columns_set:
    data_filtered = data_filtered[columns_set]
print2("writing...")
data_filtered.to_csv(dirname + "/" + event_set_type + "." + basename)
print2("END")