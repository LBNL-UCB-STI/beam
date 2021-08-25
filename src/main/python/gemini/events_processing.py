import pandas as pd

filename = '/Users/haitamlaarabi/Data/GEMINI/2021Jul30-Oakland/BASE0/events/0.events.BASE0.csv.gz'
compression = None
if filename.endswith(".gz"):
    compression = 'gzip'
data = pd.read_csv(filename, sep=",", index_col=None, header=0, compression=compression)
print("END")
