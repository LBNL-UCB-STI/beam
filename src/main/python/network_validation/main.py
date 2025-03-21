import os
import time

import pandas as pd
import psutil


def load_heavy_csv(file_path, chunk_size=100000):
    """
    Load a heavy CSV file efficiently with memory usage tracking.

    Parameters:
    -----------
    file_path : str
        Path to the CSV file
    chunk_size : int
        Number of rows to process at a time

    Returns:
    --------
    pd.DataFrame or None
        The loaded DataFrame or None if file doesn't exist
    """
    start_time = time.time()

    # Check if file exists
    if not os.path.exists(file_path):
        print(f"Error: File {file_path} not found.")
        return None

    # Get file size
    file_size_bytes = os.path.getsize(file_path)
    file_size_mb = file_size_bytes / (1024 * 1024)
    print(f"File size: {file_size_mb:.2f} MB")

    # Initial memory usage
    process = psutil.Process(os.getpid())
    initial_memory = process.memory_info().rss / (1024 * 1024)
    print(f"Initial memory usage: {initial_memory:.2f} MB")

    try:
        # For very large files, use chunking
        if file_size_mb > 500:  # If file is larger than 500MB
            print(f"Loading large file in chunks of {chunk_size} rows...")
            chunks = []
            for i, chunk in enumerate(pd.read_csv(file_path, chunksize=chunk_size)):
                chunks.append(chunk)
                if (i + 1) % 10 == 0:
                    current_memory = process.memory_info().rss / (1024 * 1024)
                    print(f"Processed {(i + 1) * chunk_size} rows. Current memory usage: {current_memory:.2f} MB")

            df = pd.concat(chunks, ignore_index=True)
        else:
            print("Loading file into memory...")
            df = pd.read_csv(file_path)

        # Final memory usage
        final_memory = process.memory_info().rss / (1024 * 1024)
        memory_increase = final_memory - initial_memory

        # Print statistics
        print(f"CSV loaded successfully in {time.time() - start_time:.2f} seconds")
        print(f"Rows: {len(df)}, Columns: {len(df.columns)}")
        print(f"Final memory usage: {final_memory:.2f} MB (increased by {memory_increase:.2f} MB)")

        # Display first few rows and column info
        print("\nFirst 5 rows:")
        print(df.head())

        print("\nColumn information:")
        print(df.dtypes)

        return df

    except Exception as e:
        print(f"Error loading CSV: {str(e)}")
        return None


if __name__ == "__main__":
    # Testing H5 Data
    # url = "https://console.cloud.google.com/storage/browser/_details/beam-core-outputs/urbansim-inputs/custom_mpo_06197001_model_data_2017.h5;tab=live_object?project=beam-core"
    # url = "https://storage.googleapis.com/beam-core-outputs/urbansim-inputs/custom_mpo_06197001_model_data_2017.h5"
    # h5_path = os.path.expanduser("~/Workspace/Simulation/sfbay/urbansim/custom_mpo_06197001_model_data.h5")
    # download_h5_data(url, h5_path)

    # Loading an events file
    # Replace with your CSV file path
    file_path = os.path.expanduser("~/Workspace/Models/pilates/0.events.csv.gz")
    out_path = os.path.expanduser("~/Downloads/0.events.p3048964.csv")
    #300181
    df = load_heavy_csv(file_path)

    if df is not None:
        # Perform additional operations with the dataframe here
        print("\nMemory usage of DataFrame:")
        print(f"{df.memory_usage(deep=True).sum() / (1024 * 1024):.2f} MB")


