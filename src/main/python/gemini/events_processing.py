import pandas as pd
import os

work_directory = '~/Data/GEMINI/2022Feb/BATCH1'
#filename = work_directory + '/events-raw/0.events.SC4b2.csv.gz'
filename = '~/Data/GEMINI/0.events.csv.gz'
full_filename = os.path.expanduser(filename)
compression = None
if filename.endswith(".gz"):
    compression = 'gzip'
data = pd.read_csv(filename, sep=",", index_col=None, header=0, compression=compression)
data_filtered = data.loc[
    data.type.isin(["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"])
]
data_filtered2 = data_filtered[
    ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
     "locationY", "locationX", "duration", "vehicleType", "person", "fuel",
     "parkingZoneId", "pricingModel", "actType"]
]
data_filtered2.to_csv('~/Data/GEMINI/filtered.0.events.csv.gz')
#data_filtered2.to_csv(work_directory + '/events/filtered.0.events.SC4b2.csv.gz')
print("END")
