import pandas as pd
import os
import sys

city = "sfbay"
scenario = "baseline"
batch = "2018_dense/"
iteration = 0
prefix = ""
filename = prefix+str(iteration)+'.events.csv.gz'
local_work_directory = '~/Workspace/Data/FREIGHT/'+city+'/beam/runs/'+scenario+'/'+batch
full_filename = os.path.expanduser(local_work_directory + filename)

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


print2("reading: " + full_filename)
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print2(data.type.unique())
data_filtered = data.loc[data.type.isin(["PathTraversal", "actstart", "actend"])]
print2(data_filtered.type.unique())
data_filtered = data_filtered.loc[data_filtered.vehicle.str.startswith("freight", na=True)]
print2(data_filtered.type.unique())
data_filtered2 = data_filtered.loc[data_filtered.actType.isin(["Warehouse", "Unloading", "Loading"]) | data_filtered.actType.isnull()]
print2(data_filtered.type.unique())
# data_filtered2 = data_filtered[
#     ["time","type","vehicleType","vehicle","secondaryFuelLevel",
#      "primaryFuelLevel","driver","mode","seatingCapacity","startX",
#      "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
#      "secondaryFuel", "secondaryFuelType", "primaryFuelType",
#      "numPassengers", "length", "primaryFuel", "actType", "fuel", "person",
#      "locationY", "locationX", "duration", "chargingPointType", "parkingType", "parkingTaz"]
# ]
print2("writing to " + dirname + "/" + "filtered." + basename)
data_filtered2.to_csv(dirname + "/" + "filtered." + basename)
print2("END")
