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
import glob
import json
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


class CsvChunker:
    """
    A class for efficiently splitting and processing large CSV.GZ files in chunks.
    Provides methods to split files, read specific chunks, and process chunks
    with custom functions.
    """

    def __init__(
            self,
            input_file=None,
            output_dir=None,
            chunk_size=1000000,
            compression='gzip',
            n_workers=None
    ):
        """
        Initialize the CsvChunker.

        Args:
            input_file: Path to the input CSV.gz file
            output_dir: Directory to store the chunked files
            chunk_size: Number of rows per chunk
            compression: Compression format ('gzip', 'bz2', etc.)
            n_workers: Number of worker processes for parallel processing
                       (defaults to CPU count if None)
        """
        self.input_file = input_file
        self.output_dir = output_dir
        self.chunk_size = chunk_size
        self.compression = compression
        self.n_workers = n_workers if n_workers is not None else os.cpu_count()
        self.metadata = None
        self.total_chunks = 0
        self.total_lines = 0

        # Create output directory if specified
        if self.output_dir:
            os.makedirs(self.output_dir, exist_ok=True)
            # Try to load existing metadata
            self._load_metadata()

    def _get_metadata_path(self):
        """Get the path to the metadata file."""
        return os.path.join(self.output_dir, "chunks_metadata.json")

    def _save_metadata(self, total_chunks):
        """Save metadata about the chunking process."""
        self.metadata = {
            "source_file": self.input_file,
            "chunk_size": self.chunk_size,
            "total_chunks": total_chunks,
            "total_lines": self.total_lines,
            "creation_time": pd.Timestamp.now().isoformat()
        }

        with open(self._get_metadata_path(), 'w') as f:
            json.dump(self.metadata, f)

    def _load_metadata(self):
        """Load metadata about the chunking process if it exists."""
        metadata_path = self._get_metadata_path()
        if os.path.exists(metadata_path):
            with open(metadata_path, 'r') as f:
                self.metadata = json.load(f)
                if 'total_lines' in self.metadata:
                    self.total_lines = self.metadata['total_lines']
                if 'total_chunks' in self.metadata:
                    self.total_chunks = self.metadata['total_chunks']

    def _verify_chunks_exist(self):
        """Verify that all expected chunks exist in the directory."""
        if not self.metadata:
            return False, []

        missing_chunks = []

        for i in range(self.metadata['total_chunks']):
            chunk_file = os.path.join(self.output_dir, f"chunk_{i:05d}.csv.gz")
            if not os.path.exists(chunk_file):
                missing_chunks.append(i)

        return len(missing_chunks) == 0, missing_chunks

    def split_file(self, force_resplit=False):
        """
        Split a large compressed CSV file into smaller chunks with progress tracking.

        Args:
            force_resplit: If True, force resplitting even if chunks already exist

        Returns:
            List of output file paths
        """
        if not self.input_file or not self.output_dir:
            raise ValueError("Input file and output directory must be specified")

        # Check if chunks already exist
        if not force_resplit and self.metadata is not None:
            # Verify all chunks exist and match current parameters
            if (self.metadata['source_file'] == self.input_file and
                    self.metadata['chunk_size'] == self.chunk_size):

                chunks_exist, missing_chunks = self._verify_chunks_exist()

                if chunks_exist:
                    print(f"Chunks already exist for {self.input_file} with chunk size {self.chunk_size}.")
                    print(f"Total chunks: {self.metadata['total_chunks']}")

                    # Get all chunk files
                    chunk_files = sorted(glob.glob(os.path.join(self.output_dir, "chunk_*.csv.gz")))
                    return chunk_files
                else:
                    print(f"Some chunks are missing: {missing_chunks}. Will recreate all chunks.")
            else:
                print(
                    f"Parameters changed. Previous: {self.metadata['source_file']} with size {self.metadata['chunk_size']}")
                print(f"Current: {self.input_file} with size {self.chunk_size}. Will recreate all chunks.")

        # Get total number of lines for progress bar
        print("Counting lines in file (this might take a while for a 10GB file)...")
        with gzip.open(self.input_file, 'rt') as f:
            # Get the header
            header = f.readline()

            # Count lines using a buffer-based approach (memory efficient)
            self.total_lines = 0
            for _ in tqdm(f, desc="Counting lines"):
                self.total_lines += 1

        # Calculate total chunks
        self.total_chunks = math.ceil(self.total_lines / self.chunk_size)
        print(f"File will be split into {self.total_chunks} chunks")

        # Use a sequential approach to avoid multiprocessing issues
        output_files = []
        for chunk_id in tqdm(range(self.total_chunks), desc="Splitting file"):
            chunk_file = self._process_chunk_sequential(chunk_id, header)
            if chunk_file:
                output_files.append(chunk_file)

        # Save metadata
        self._save_metadata(self.total_chunks)

        print(f"Successfully split into {len(output_files)} chunks")
        return output_files

    def _process_chunk_sequential(self, chunk_id, header=None):
        """
        Process a single chunk sequentially (no multiprocessing).

        Args:
            chunk_id: Index of the chunk to process
            header: Optional header text from the file

        Returns:
            Path to the saved chunk file or None if error
        """
        # For the first chunk we need to read from the start
        if chunk_id == 0:
            skip_rows = 0
            header_flag = 0  # Don't skip header
        else:
            # Skip header for subsequent chunks
            skip_rows = 1 + (chunk_id * self.chunk_size)
            header_flag = 0  # Already skipping rows, so don't skip header again

        # Determine how many rows to read
        if chunk_id == self.total_chunks - 1:  # Last chunk
            nrows = self.total_lines - (chunk_id * self.chunk_size)
        else:
            nrows = self.chunk_size

        chunk_file = os.path.join(self.output_dir, f"chunk_{chunk_id:05d}.csv.gz")

        try:
            # Read chunk from the original file
            df_chunk = pd.read_csv(
                self.input_file,
                compression='gzip',
                skiprows=skip_rows,
                nrows=nrows,
                header=header_flag
            )

            # If it's not the first chunk, add the header
            if chunk_id > 0 and header is None:
                with gzip.open(self.input_file, 'rt') as f:
                    header_text = f.readline().strip()
                    df_chunk.columns = header_text.split(',')
            elif chunk_id > 0 and header is not None:
                df_chunk.columns = header.strip().split(',')

            # Save the chunk
            df_chunk.to_csv(
                chunk_file,
                compression=self.compression,
                index=False
            )

            return chunk_file
        except Exception as e:
            print(f"Error processing chunk {chunk_id}: {str(e)}")
            return None

    def get_chunk_count(self):
        """
        Get the total number of chunks in the directory.

        Returns:
            Number of chunks
        """
        if self.metadata is not None:
            return self.metadata['total_chunks']

        # Fallback to counting files if metadata is not available
        if self.output_dir:
            chunk_files = glob.glob(os.path.join(self.output_dir, "chunk_*.csv.gz"))
            return len(chunk_files)

        return 0

    def read_chunk_by_index(self, chunk_index):
        """
        Read a specific chunk by its index.

        Args:
            chunk_index: Index of the chunk to read (0-based)

        Returns:
            DataFrame chunk or None if not found
        """
        if not self.output_dir:
            raise ValueError("Output directory must be specified")

        # Format the filename with leading zeros
        chunk_file = os.path.join(self.output_dir, f"chunk_{chunk_index:05d}.csv.gz")

        # Check if file exists
        if not os.path.exists(chunk_file):
            print(f"Chunk {chunk_index} does not exist at path: {chunk_file}")
            return None

        # Read the chunk
        print(f"Reading chunk {chunk_index} from {chunk_file}")
        df_chunk = pd.read_csv(chunk_file, compression=self.compression)

        return df_chunk

    def process_chunk(self, chunk, process_function):
        """
        Apply a processing function to a chunk DataFrame.

        Args:
            chunk: DataFrame to process
            process_function: Function to apply to the chunk

        Returns:
            Processed DataFrame
        """
        return process_function(chunk)

    def read_chunks(self):
        """
        Read chunks one by one.

        Yields:
            DataFrame chunks
        """
        if not self.output_dir:
            raise ValueError("Output directory must be specified")

        # Get the number of chunks from metadata
        total_chunks = self.get_chunk_count()

        # Read each chunk in order
        for i in tqdm(range(total_chunks), desc="Processing chunks"):
            chunk_file = os.path.join(self.output_dir, f"chunk_{i:05d}.csv.gz")

            if os.path.exists(chunk_file):
                df_chunk = pd.read_csv(chunk_file, compression=self.compression)
                yield df_chunk
            else:
                print(f"Warning: Chunk file {chunk_file} not found, skipping")

    def process_all_chunks(self, process_function, output_file=None):
        """
        Process all chunks sequentially and optionally combine results.

        Args:
            process_function: Function to apply to each chunk
            output_file: Optional output file to save combined results

        Returns:
            Combined DataFrame if output_file is None, otherwise None
        """
        if not self.output_dir:
            raise ValueError("Output directory must be specified")

        # Get the number of chunks from metadata
        total_chunks = self.get_chunk_count()

        # Create list of chunk files
        chunk_files = [os.path.join(self.output_dir, f"chunk_{i:05d}.csv.gz") for i in range(total_chunks)]
        existing_chunk_files = [f for f in chunk_files if os.path.exists(f)]

        if len(existing_chunk_files) < total_chunks:
            print(f"Warning: Expected {total_chunks} chunks but found {len(existing_chunk_files)}")

        # Process chunks sequentially with progress bar
        results = []
        for chunk_file in tqdm(existing_chunk_files, desc="Processing all chunks"):
            df_chunk = pd.read_csv(chunk_file, compression=self.compression)
            processed_chunk = process_function(df_chunk)
            results.append(processed_chunk)

        # Combine results if needed
        if results:
            combined = pd.concat(results, ignore_index=True)

            if output_file:
                combined.to_csv(output_file, compression=self.compression, index=False)
                print(f"Combined results saved to {output_file}")
                return None
            else:
                return combined

        return None


# Example usage
def example_process(df):
    # Replace with your actual processing logic
    return df.fillna(0)  # Just an example


def main():
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/beam-runs/20240123/2018-Baseline")
    # Split the large file
    input_file = f"{work_dir}/0.skimsEmissions.csv.gz"
    output_dir = f"{work_dir}/chunks"
    output_file = f"{work_dir}/0.skimsEmissions.processed.csv.gz"

    # Create a CsvChunker instance
    chunker = CsvChunker(
        input_file=input_file,
        output_dir=output_dir,
        chunk_size=1000000,  # Adjust based on your memory constraints
        n_workers=os.cpu_count()  # Use all available cores
    )

    # Split the file into chunks if not already done
    chunker.split_file(force_resplit=False)  # Set to True to force resplitting

    print(f"Total chunks available: {chunker.get_chunk_count()}")

    # Example 1: Read a specific chunk by index
    chunk_index = 3  # Read the 4th chunk (0-based index)
    df_specific_chunk = chunker.read_chunk_by_index(chunk_index)

    if df_specific_chunk is not None:
        print(f"Successfully read chunk {chunk_index} with {len(df_specific_chunk)} rows")
        # Process the specific chunk
        processed_chunk = chunker.process_chunk(df_specific_chunk, example_process)
        print(f"Processed chunk has {len(processed_chunk)} rows")
        print(len(df_specific_chunk))
        print(df_specific_chunk.head(5))

    # # Example 2: Process chunks one by one (lower memory usage)
    # for i, chunk in enumerate(chunker.read_chunks()):
    #     # Process the chunk
    #     processed_chunk = example_process(chunk)
    #     print(f"Processed chunk {i} with {len(processed_chunk)} rows")
    #     if i >= 2:  # Just process a few chunks as an example
    #         break
    #
    # # Example 3: Process all chunks and combine results
    # combined_df = chunker.process_all_chunks(
    #     process_function=example_process,
    #     output_file=output_file
    # )


if __name__ == "__main__":
    main()
