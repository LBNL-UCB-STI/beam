import pandas as pd
import os

filename = '~/Data/GEMINI/2021Oct29/BATCH1/events-raw/0.events.SC4Bis5.csv.gz'
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

data_filtered2.to_csv('~/Data/GEMINI/2021Oct29/BATCH1/events/filtered.0.events.SC4Bis5.csv.gz')
print("END")
