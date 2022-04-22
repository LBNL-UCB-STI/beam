import pandas as pd
import os

work_directory = '~/Data/GEMINI/2022-04-20/'
filename = '0.events.4aBase.csv.gz'
full_filename = os.path.expanduser(work_directory + "events-raw/" + filename)
compression = None
if full_filename.endswith(".gz"):
    compression = 'gzip'
data = pd.read_csv(full_filename, sep=",", index_col=None, header=0, compression=compression)
data_filtered = data.loc[
    data.type.isin(["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"])
]
data_filtered2 = data_filtered[
    ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
     "locationY", "locationX", "duration", "vehicleType", "person", "fuel",
     "parkingZoneId", "pricingModel", "actType"]
]
data_filtered2.to_csv(work_directory + "events/filtered." + filename)
print("END")
