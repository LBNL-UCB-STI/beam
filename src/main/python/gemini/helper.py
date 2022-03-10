import pandas as pd

def read_file(filename):
    compression = None
    if filename.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(filename, sep=",", index_col=None, header=0, compression=compression)