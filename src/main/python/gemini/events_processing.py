import pandas as pd
import os
import sys

work_directory = '/home/ubuntu/git/beam/src/main/python/gemini'
filename = '0.events.7Advanced.csv.gz'

if len(sys.argv) >= 2:
    filename = str(sys.argv[1])
full_filename = os.path.expanduser(work_directory + "/" + filename)
compression = None

if full_filename.endswith(".gz"):
    compression = 'gzip'


def print2(msg):
    with open(full_filename + ".out", 'w') as f:
        print(msg, file=f)


print2("reading " + filename)
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print2("filtering 1/2...")
data_filtered = data.loc[data.type.isin(
    ["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"]
)]
print2("filtering 2/2...")
data_filtered2 = data_filtered[
    ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
     "locationY", "locationX", "duration", "vehicleType", "person", "fuel",
     "parkingZoneId", "pricingModel", "actType"]
]
print2("writing...")
data_filtered2.to_csv(work_directory + "/filtered." + filename)
print2("END")
