import pandas as pd
import os

work_directory = '~/Data/FREIGHT/validation/beam/'
filename = '0.events.new.csv'
full_filename = os.path.expanduser(work_directory + filename)
compression = None
if full_filename.endswith(".gz"):
    compression = 'gzip'
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
print(data.type.unique())
data_filtered = data.loc[data.type.isin(["PathTraversal", "actstart", "actend"])]
print(data_filtered.type.unique())
data_filtered = data_filtered.loc[data_filtered.vehicle.str.startswith("freight", na=True)]
print(data_filtered.type.unique())
data_filtered2 = data_filtered.loc[data_filtered.actType.isin(["Warehouse", "Unloading", "Loading"]) | data_filtered.actType.isnull()]
print(data_filtered.type.unique())
# data_filtered2 = data_filtered[
#     ["time","type","vehicleType","vehicle","secondaryFuelLevel",
#      "primaryFuelLevel","driver","mode","seatingCapacity","startX",
#      "startY", "endX", "endY", "capacity", "arrivalTime", "departureTime",
#      "secondaryFuel", "secondaryFuelType", "primaryFuelType",
#      "numPassengers", "length", "primaryFuel", "actType", "fuel", "person",
#      "locationY", "locationX", "duration", "chargingPointType", "parkingType", "parkingTaz"]
# ]
data_filtered2.to_csv(work_directory + "filtered." + filename)
print("END")
