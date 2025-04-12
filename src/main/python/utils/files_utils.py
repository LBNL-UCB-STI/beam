import gzip
import io
import os
import time
import psutil

from tqdm import tqdm
from tqdm.auto import tqdm
import pandas as pd
import re
import math
import concurrent.futures



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


def split_csv_gz(
        input_file: str,
        output_dir: str,
        chunk_size: int = 1000000,  # Number of rows per chunk
        compression: str = 'gzip',
        process_function=None,
        n_workers: int = 4
):
    """
    Split a large compressed CSV file into smaller chunks with progress tracking.

    Args:
        input_file: Path to the input CSV.gz file
        output_dir: Directory to store the chunked files
        chunk_size: Number of rows per chunk
        compression: Compression format ('gzip', 'bz2', etc.)
        process_function: Optional function to process each chunk
        n_workers: Number of worker processes for parallel processing

    Returns:
        List of output file paths
    """
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    # Get total number of lines for progress bar
    print("Counting lines in file (this might take a while for a 10GB file)...")
    with gzip.open(input_file, 'rt') as f:
        # Get the header
        header = f.readline()

        # Count lines using a buffer-based approach (memory efficient)
        total_lines = 0
        for _ in tqdm(f, desc="Counting lines"):
            total_lines += 1

    # Calculate total chunks
    total_chunks = math.ceil(total_lines / chunk_size)
    print(f"File will be split into {total_chunks} chunks")

    # Function to process a single chunk
    def process_chunk(chunk_id):
        # For the first chunk we need to read from the start
        if chunk_id == 0:
            skip_rows = 0
            header_flag = 0  # Don't skip header
        else:
            # Skip header for subsequent chunks
            skip_rows = 1 + (chunk_id * chunk_size)
            header_flag = 0  # Already skipping rows, so don't skip header again

        # Determine how many rows to read
        if chunk_id == total_chunks - 1:  # Last chunk
            nrows = total_lines - (chunk_id * chunk_size)
        else:
            nrows = chunk_size

        chunk_file = os.path.join(output_dir, f"chunk_{chunk_id:05d}.csv.gz")

        try:
            # Read chunk from the original file
            df_chunk = pd.read_csv(
                input_file,
                compression='gzip',
                skiprows=skip_rows,
                nrows=nrows,
                header=header_flag
            )

            # If it's not the first chunk, add the header
            if chunk_id > 0:
                with gzip.open(input_file, 'rt') as f:
                    header_text = f.readline().strip()
                    df_chunk.columns = header_text.split(',')

            # Apply process function if provided
            if process_function:
                df_chunk = process_function(df_chunk)

            # Save the chunk
            df_chunk.to_csv(
                chunk_file,
                compression=compression,
                index=False
            )

            return chunk_file
        except Exception as e:
            print(f"Error processing chunk {chunk_id}: {str(e)}")
            return None

    # Process chunks with progress bar
    output_files = []
    with tqdm(total=total_chunks, desc="Splitting file") as pbar:
        with concurrent.futures.ProcessPoolExecutor(max_workers=n_workers) as executor:
            futures = [executor.submit(process_chunk, i) for i in range(total_chunks)]

            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                if result:
                    output_files.append(result)
                pbar.update(1)

    print(f"Successfully split into {len(output_files)} chunks")
    return output_files


def read_chunks(
        chunk_dir: str,
        process_function=None,
        pattern: str = "chunk_*.csv.gz",
        compression: str = 'gzip'
):
    """
    Read and process chunks one by one.

    Args:
        chunk_dir: Directory containing the chunks
        process_function: Function to apply to each chunk
        pattern: Glob pattern to match chunk files
        compression: Compression format

    Yields:
        Processed DataFrame chunks
    """
    import glob

    # Get all chunk files sorted by name
    chunk_files = sorted(glob.glob(os.path.join(chunk_dir, pattern)))

    # Process each chunk with progress bar
    for chunk_file in tqdm(chunk_files, desc="Processing chunks"):
        df_chunk = pd.read_csv(chunk_file, compression=compression)

        if process_function:
            df_chunk = process_function(df_chunk)

        yield df_chunk


def process_all_chunks(
        chunk_dir: str,
        process_function,
        pattern: str = "chunk_*.csv.gz",
        compression: str = 'gzip',
        output_file: str = None,
        n_workers: int = 4
):
    """
    Process all chunks in parallel and optionally combine results.

    Args:
        chunk_dir: Directory containing the chunks
        process_function: Function to apply to each chunk
        pattern: Glob pattern to match chunk files
        compression: Compression format
        output_file: Optional output file to save combined results
        n_workers: Number of worker processes

    Returns:
        Combined DataFrame if output_file is None, otherwise None
    """
    import glob

    # Get all chunk files sorted by name
    chunk_files = sorted(glob.glob(os.path.join(chunk_dir, pattern)))
    total_chunks = len(chunk_files)

    # Function to process a single chunk file
    def process_chunk_file(chunk_file):
        df_chunk = pd.read_csv(chunk_file, compression=compression)
        return process_function(df_chunk)

    # Process chunks in parallel with progress bar
    results = []
    with tqdm(total=total_chunks, desc="Processing all chunks") as pbar:
        with concurrent.futures.ProcessPoolExecutor(max_workers=n_workers) as executor:
            futures = [executor.submit(process_chunk_file, f) for f in chunk_files]

            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                results.append(result)
                pbar.update(1)

    # Combine results if needed
    if results:
        combined = pd.concat(results, ignore_index=True)

        if output_file:
            combined.to_csv(output_file, compression=compression, index=False)
            print(f"Combined results saved to {output_file}")
            return None
        else:
            return combined

    return None


# Example usage
if __name__ == "__main__":
    # Example process function
    def example_process(df):
        # Replace with your actual processing logic
        return df.fillna(0)  # Just an example

    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-runs/20240123/2018-Baseline")
    # Split the large file
    input_file = f"{work_dir}/0.skimsEmissions.csv.gz"
    output_dir = f"{work_dir}/chunks"
    output_file = f"{work_dir}/0.skimsEmissions.processed.csv.gz"

    # Split the file into chunks
    split_csv_gz(
        input_file=input_file,
        output_dir=output_dir,
        chunk_size=1000000,  # Adjust based on your memory constraints
        process_function=example_process,
        n_workers=os.cpu_count()  # Use all available cores
    )

    # Option 1: Process chunks one by one (lower memory usage)
    for chunk in read_chunks(output_dir, process_function=example_process):
        # Do something with each processed chunk
        print(f"Processed chunk with {len(chunk)} rows")

    # Option 2: Process all chunks in parallel and combine results
    combined_df = process_all_chunks(
        chunk_dir=output_dir,
        process_function=example_process,
        output_file=output_file,
        n_workers=os.cpu_count()
    )