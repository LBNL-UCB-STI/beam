

def read_csv(file_path):
    import pandas as pd
    compression = None
    if file_path.endswith(".gz"):
        compression = 'gzip'
    return pd.read_csv(file_path, sep=",", index_col=None, header=0, compression=compression)


def read_csv_in_chunks(file_path):
    import pandas as pd
    compression = None
    if file_path.endswith(".gz"):
        compression = 'gzip'
    # Read the large csv file in chunks
    chunk_size = 5_000_000  # This will depend on your available memory
    chunks = []
    for chunk in pd.read_csv(file_path, chunksize=chunk_size, sep=",", index_col=None, header=0, compression=compression):
        # Process each chunk here if necessary, for example:
        chunks.append(chunk)
    # Concatenate all chunks into one DataFrame
    return pd.concat(chunks, ignore_index=True)


def read_csv_in_parallel(file_path):
    import dask.dataframe as dd
    compression = None
    if file_path.endswith(".gz"):
        compression = 'gzip'
    return dd.read_csv(file_path, compression=compression, blocksize=None)


def print2(file_path, msg):
    with open(file_path + ".out", 'w') as f:
        print(msg)
        print(msg, file=f)


def construct_events_file_path(workspace, city, scenario, batch, iteration=0):
    import os
    base_dir = workspace+city+'/beam/runs/'+scenario+'/'+batch+"/"
    filename = str(iteration)+'.events.csv.gz'
    return os.path.expanduser(base_dir + filename)