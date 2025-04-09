import gzip
import io
import os
import time
import psutil

from tqdm import tqdm
from tqdm.auto import tqdm
import pandas as pd
import re

def combine_csv_files(input_files, output_file):
    # Read and combine CSV files vertically
    combined_df = pd.concat([pd.read_csv(f) for f in input_files], ignore_index=True)

    # Write the combined dataframe to a new CSV file
    combined_df.to_csv(output_file, index=False)

    print(f"Combined CSV file has been created: {output_file}")
    return combined_df  # Return the dataframe for further processing if needed

def fast_df_to_gzip(df, output_file, compression_level=5, chunksize=100000):
    """
    Write a pandas DataFrame to a compressed CSV.gz file quickly with a progress bar.

    :param df: pandas DataFrame to write
    :param output_file: path to the output .csv.gz file
    :param compression_level: gzip compression level (1-9, 9 being highest)
    :param chunksize: number of rows to write at a time
    """
    total_rows = len(df)

    with gzip.open(output_file, 'wt', compresslevel=compression_level) as gz_file:
        # Write header
        gz_file.write(','.join(df.columns) + '\n')

        # Write data in chunks
        with tqdm(total=total_rows, desc="Writing to gzip", unit="rows") as pbar:
            for start in range(0, total_rows, chunksize):
                end = min(start + chunksize, total_rows)
                chunk = df.iloc[start:end]

                csv_buffer = io.StringIO()
                chunk.to_csv(csv_buffer, index=False, header=False)
                gz_file.write(csv_buffer.getvalue())

                pbar.update(end - start)


def sanitize_name(filename):
    # Start with the original filename
    sanitized = filename

    # Replace other common superscripts if needed
    superscript_map = {'¹': '1', '²': '2', '³': '3', '⁴': '4', '⁵': '5', '⁶': '6', '⁷': '7', '⁸': '8', '⁹': '9'}
    for sup, normal in superscript_map.items():
        sanitized = sanitized.replace(sup, normal)

    # Replace parentheses with underscores
    sanitized = sanitized.replace('(', '_').replace(')', '_')

    # Replace forward slashes and backslashes with dashes
    sanitized = sanitized.replace('/', '-').replace('\\', '-')

    # Replace spaces with underscores
    sanitized = sanitized.replace(' ', '_')

    # Remove or replace any other non-alphanumeric characters (except dashes and underscores)
    sanitized = re.sub(r'[^\w\-_]', '', sanitized)

    # Replace any sequence of dashes or underscores with a single underscore
    sanitized = re.sub(r'[_-]+', '_', sanitized)

    # Remove leading and trailing underscores
    sanitized = sanitized.strip('_')

    return sanitized


def check_files(paths, delete=True):
    if isinstance(paths, str):
        paths = [paths]

    results = []
    for path in paths:
        exists = os.path.isfile(path)
        if exists and delete:
            os.remove(path)
            results.append(False)
        else:
            results.append(exists and not delete)

    return all(results)


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