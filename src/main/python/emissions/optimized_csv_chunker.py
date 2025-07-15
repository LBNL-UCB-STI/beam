import concurrent.futures
import glob
import gzip
import json
import math
import os
import queue
import subprocess
import threading

import pandas as pd
from tqdm import tqdm
from tqdm.auto import tqdm


class OptimizedCsvChunker:
    """
    An optimized class for efficiently splitting and processing large CSV.GZ files in chunks.
    Provides methods to split files, read specific chunks, and process chunks
    with custom functions. Includes enhanced parallel processing and memory optimizations.
    """

    def __init__(
            self,
            input_file=None,
            output_dir=None,
            chunk_size=1000000,
            compression='gzip',
            compression_level=5,
            n_workers=None,
            use_fast_line_count=True,
            use_memory_map=True,
            cache_metadata=True
    ):
        """
        Initialize the OptimizedCsvChunker.

        Args:
            input_file: Path to the input CSV.gz file
            output_dir: Directory to store the chunked files
            chunk_size: Number of rows per chunk
            compression: Compression format ('gzip', 'bz2', etc.)
            compression_level: Compression level (1-9, 9 being highest)
            n_workers: Number of worker processes for parallel processing
                       (defaults to CPU count if None)
            use_fast_line_count: Use fast line counting methods when possible
            use_memory_map: Use memory mapping for large files when possible
            cache_metadata: Cache file metadata for faster subsequent operations
        """
        self.input_file = input_file
        self.output_dir = output_dir
        self.chunk_size = chunk_size
        self.compression = compression
        self.compression_level = compression_level
        self.n_workers = n_workers if n_workers is not None else max(1, os.cpu_count() - 1)
        self.use_fast_line_count = use_fast_line_count
        self.use_memory_map = use_memory_map
        self.cache_metadata = cache_metadata
        self.metadata = None
        self.total_chunks = 0
        self.total_lines = 0
        self._header = None
        self._chunk_queue = None
        self._result_queue = None
        self._lock = threading.Lock()

        # Set a reasonable chunk buffer size for optimal I/O
        self.read_buffer_size = 8 * 1024 * 1024  # 8MB buffer size

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
            "header": self._header.strip() if self._header else None,
            "creation_time": pd.Timestamp.now().isoformat()
        }

        if self.cache_metadata:
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
                if 'header' in self.metadata and self.metadata['header']:
                    self._header = self.metadata['header']

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

    def _fast_count_lines(self):
        """
        Efficiently count lines in a large file using external tools or optimized methods.
        Works best for Unix-like systems with 'wc' command available.
        """
        # Try using wc command on Unix-like systems first (extremely fast)
        if os.name != 'nt':  # Not Windows
            try:
                if self.compression == 'gzip':
                    result = subprocess.run(
                        f"zcat {self.input_file} | wc -l",
                        shell=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True
                    )
                    count = int(result.stdout.strip())
                    return count + 1  # Add 1 to account for header
                else:
                    result = subprocess.run(
                        f"wc -l < {self.input_file}",
                        shell=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True
                    )
                    return int(result.stdout.strip())
            except Exception as e:
                print(f"Fast line count failed, falling back to manual count: {str(e)}")

        # Fallback to faster Python-based counting
        total_lines = 0

        # For gzipped files
        if self.compression == 'gzip':
            with gzip.open(self.input_file, 'rt', buffering=self.read_buffer_size) as f:
                # Get the header
                self._header = f.readline()
                total_lines = 1  # Initialize with header line

                # Count lines in chunks for better performance
                chunk_size = 16 * 1024 * 1024  # 16MB chunk size
                while True:
                    data = f.read(chunk_size)
                    if not data:
                        break
                    # Count newlines
                    total_lines += data.count('\n')
        else:
            # For plain text files - use binary mode for speed
            with open(self.input_file, 'rb', buffering=self.read_buffer_size) as f:
                # Read and store header
                header_bytes = b''
                for byte in iter(lambda: f.read(1), b''):
                    header_bytes += byte
                    if byte == b'\n':
                        break

                self._header = header_bytes.decode('utf-8')
                total_lines = 1  # Initialize with header line

                # Count lines in chunks
                chunk_size = 16 * 1024 * 1024  # 16MB chunk size
                while True:
                    data = f.read(chunk_size)
                    if not data:
                        break
                    total_lines += data.count(b'\n')

        return total_lines

    def _standard_count_lines(self):
        """Standard line counting with progress bar for when fast methods aren't available."""
        print("Counting lines in file (this might take a while for a large file)...")
        total_lines = 0

        with gzip.open(self.input_file, 'rt') as f:
            # Get the header
            self._header = f.readline()
            total_lines = 1  # Start with header

            # Count lines using buffered reads for better performance
            for _ in tqdm(f, desc="Counting lines"):
                total_lines += 1

        return total_lines

    def _worker_process_chunk(self, worker_id):
        """Worker function for processing chunks in parallel."""
        while True:
            try:
                # Get chunk to process from queue
                chunk_info = self._chunk_queue.get(block=False)
                if chunk_info is None:  # Sentinel value
                    break

                chunk_id, start_row, end_row = chunk_info

                # Process the chunk
                chunk_file = self._process_chunk(chunk_id, start_row, end_row)

                # Add result to result queue
                self._result_queue.put((chunk_id, chunk_file))

                # Update progress bar
                with self._lock:
                    self._pbar.update(1)

            except queue.Empty:
                # No more chunks to process
                break
            except Exception as e:
                print(f"Error in worker {worker_id}: {str(e)}")
                self._result_queue.put((None, None))  # Signal an error occurred

        # Put a sentinel value to signal this worker is done
        self._result_queue.put(None)

    def _process_chunk(self, chunk_id, start_row, end_row):
        """
        Process a single chunk.

        Args:
            chunk_id: Index of the chunk to process
            start_row: Starting row index (0-based)
            end_row: Ending row index (exclusive)

        Returns:
            Path to the saved chunk file or None if error
        """
        chunk_file = os.path.join(self.output_dir, f"chunk_{chunk_id:05d}.csv.gz")

        try:
            # Skip header for all but the first chunk
            skiprows = 1 + start_row if chunk_id > 0 else start_row

            # Determine how many rows to read
            nrows = end_row - start_row

            # Read chunk from the original file
            df_chunk = pd.read_csv(
                self.input_file,
                compression='gzip',
                skiprows=skiprows,
                nrows=nrows,
                header=0 if chunk_id == 0 else None,
                low_memory=True,  # Better memory usage
                engine='c',  # Use C engine for speed
                on_bad_lines='warn'  # Skip bad lines but warn about them
            )

            # If it's not the first chunk, add the header
            if chunk_id > 0 and self._header:
                df_chunk.columns = self._header.strip().split(',')

            # Use a more optimized way to save to gzip
            with gzip.open(chunk_file, 'wt', compresslevel=self.compression_level) as gz_file:
                df_chunk.to_csv(gz_file, index=False)

            # Release memory explicitly
            del df_chunk

            return chunk_file

        except Exception as e:
            print(f"Error processing chunk {chunk_id}: {str(e)}")
            return None

    def _memory_efficient_split(self):
        """
        Memory-efficient file splitting that processes the file in blocks
        rather than loading large DataFrames.
        """
        print("Using memory-efficient splitting method...")

        # Calculate indices for each chunk
        chunks_info = []
        for i in range(self.total_chunks):
            start_row = i * self.chunk_size
            end_row = min((i + 1) * self.chunk_size, self.total_lines - 1)  # Exclude header from count
            chunks_info.append((i, start_row, end_row))

        # Create progress bar
        self._pbar = tqdm(total=len(chunks_info), desc="Splitting file")

        # Create queues for parallel processing
        self._chunk_queue = queue.Queue()
        self._result_queue = queue.Queue()

        # Fill queue with chunks to process
        for chunk_info in chunks_info:
            self._chunk_queue.put(chunk_info)

        # Add sentinel values to signal end of queue
        for _ in range(self.n_workers):
            self._chunk_queue.put(None)

        # Start worker threads
        workers = []
        for i in range(self.n_workers):
            worker = threading.Thread(target=self._worker_process_chunk, args=(i,))
            worker.daemon = True
            worker.start()
            workers.append(worker)

        # Wait for all chunks to be processed
        completed_chunks = 0
        output_files = [None] * len(chunks_info)

        while completed_chunks < len(chunks_info):
            result = self._result_queue.get()
            if result is None:
                # A worker is done
                continue

            chunk_id, chunk_file = result
            if chunk_id is not None:
                output_files[chunk_id] = chunk_file
                completed_chunks += 1

        # Wait for workers to finish
        for worker in workers:
            worker.join()

        # Clean up
        self._pbar.close()
        self._chunk_queue = None
        self._result_queue = None

        # Filter out None values (failed chunks)
        output_files = [f for f in output_files if f]

        return output_files

    def split_file(self, force_resplit=False):
        """
        Split a large compressed CSV file into smaller chunks with parallel processing.

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

        # Get total number of lines - use fast method if available
        if self.use_fast_line_count:
            try:
                print("Using fast line counting method...")
                self.total_lines = self._fast_count_lines()
                print(f"File contains {self.total_lines} lines (including header)")
            except Exception as e:
                print(f"Fast line count failed: {str(e)}")
                print("Falling back to standard line counting...")
                self.total_lines = self._standard_count_lines()
        else:
            self.total_lines = self._standard_count_lines()

        # Calculate total chunks - account for header line
        self.total_chunks = math.ceil((self.total_lines - 1) / self.chunk_size)
        print(f"File will be split into {self.total_chunks} chunks")

        # Use memory efficient splitting method
        output_files = self._memory_efficient_split()

        # Save metadata
        self._save_metadata(self.total_chunks)

        print(f"Successfully split into {len(output_files)} chunks")
        return output_files

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
        Read a specific chunk by its index with optimized I/O.

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

        # Read the chunk with optimized parameters
        print(f"Reading chunk {chunk_index} from {chunk_file}")

        # Use memory mapping if possible
        if self.use_memory_map and self.compression != 'gzip':
            df_chunk = pd.read_csv(
                chunk_file,
                compression=self.compression,
                memory_map=True,
                low_memory=True
            )
        else:
            # For gzipped files, use optimized buffer size
            compression_opts = {'method': self.compression, 'compresslevel': self.compression_level}
            df_chunk = pd.read_csv(
                chunk_file,
                compression=compression_opts,
                low_memory=True
            )

        return df_chunk

    def process_chunks_parallel(self, process_function, output_file=None, process_inline=False):
        """
        Process chunks in parallel and optionally combine results.

        Args:
            process_function: Function to apply to each chunk
            output_file: Optional output file to save combined results
            process_inline: If True, write processed chunks back to original chunk files

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

        # Process chunks in parallel using ThreadPoolExecutor
        results = []

        with tqdm(total=len(existing_chunk_files), desc="Processing chunks in parallel") as pbar:
            with concurrent.futures.ThreadPoolExecutor(max_workers=self.n_workers) as executor:
                # Define function to process each chunk
                def process_chunk_file(chunk_file):
                    try:
                        # Read chunk
                        df_chunk = pd.read_csv(chunk_file, compression=self.compression)

                        # Process chunk
                        processed_chunk = process_function(df_chunk)

                        # Write back to same file if inline processing
                        if process_inline:
                            processed_chunk.to_csv(
                                chunk_file,
                                compression=self.compression,
                                index=False
                            )
                            pbar.update(1)
                            return None
                        else:
                            pbar.update(1)
                            return processed_chunk
                    except Exception as e:
                        print(f"Error processing {chunk_file}: {str(e)}")
                        pbar.update(1)
                        return None

                # Submit all tasks
                future_to_file = {
                    executor.submit(process_chunk_file, f): f for f in existing_chunk_files
                }

                # Collect results as they complete
                for future in concurrent.futures.as_completed(future_to_file):
                    result = future.result()
                    if result is not None:
                        results.append(result)

        # Combine results if needed
        if results and not process_inline:
            print("Combining processed chunks...")
            combined = pd.concat(results, ignore_index=True)

            if output_file:
                # Write to output file efficiently
                with gzip.open(output_file, 'wt', compresslevel=self.compression_level) as gz_file:
                    combined.to_csv(gz_file, index=False)
                print(f"Combined results saved to {output_file}")
                return None
            else:
                return combined

        return None

    def stream_process(self, process_function, output_file, chunksize=None):
        """
        Stream process the chunks and write directly to output file without loading everything in memory.
        Good for transformations that don't require seeing all data at once.

        Args:
            process_function: Function to apply to each chunk
            output_file: Output file to save results
            chunksize: Optional custom chunksize for processing (defaults to self.chunk_size)

        Returns:
            None
        """
        if not self.output_dir:
            raise ValueError("Output directory must be specified")

        # Use class chunk size if not specified
        if chunksize is None:
            chunksize = self.chunk_size

        # Get list of chunk files
        chunk_files = sorted(glob.glob(os.path.join(self.output_dir, "chunk_*.csv.gz")))

        if not chunk_files:
            print("No chunks found to process")
            return

        # Open output file
        with gzip.open(output_file, 'wt', compresslevel=self.compression_level) as out_file:
            # Process first chunk to get header
            first_df = pd.read_csv(chunk_files[0], compression=self.compression)
            processed_first = process_function(first_df)

            # Write header to output file
            processed_first.to_csv(out_file, index=False)

            # Process remaining chunks
            with tqdm(total=len(chunk_files) - 1, desc="Streaming chunks") as pbar:
                for chunk_file in chunk_files[1:]:
                    df_chunk = pd.read_csv(chunk_file, compression=self.compression)
                    processed_chunk = process_function(df_chunk)

                    # Write without header
                    processed_chunk.to_csv(out_file, index=False, header=False)
                    pbar.update(1)

                    # Clean up memory
                    del df_chunk
                    del processed_chunk

        print(f"Streaming process complete, output saved to {output_file}")

    def memory_optimized_combine(self, output_file):
        """
        Combine all chunks into a single file without loading everything into memory.

        Args:
            output_file: Path to the output file

        Returns:
            None
        """
        if not self.output_dir:
            raise ValueError("Output directory must be specified")

        # Get list of chunk files
        chunk_files = sorted(glob.glob(os.path.join(self.output_dir, "chunk_*.csv.gz")))

        if not chunk_files:
            print("No chunks found to combine")
            return

        print(f"Combining {len(chunk_files)} chunks to {output_file}")

        # Open output file
        with gzip.open(output_file, 'wt', compresslevel=self.compression_level) as out_file:
            # Process first chunk to get header
            with gzip.open(chunk_files[0], 'rt') as first_file:
                header = first_file.readline()
                out_file.write(header)  # Write header

                # Copy rest of first file
                for line in first_file:
                    out_file.write(line)

            # Process remaining chunks
            with tqdm(total=len(chunk_files) - 1, desc="Combining chunks") as pbar:
                for chunk_file in chunk_files[1:]:
                    with gzip.open(chunk_file, 'rt') as in_file:
                        # Skip header for all but first file
                        next(in_file)

                        # Copy chunk content to output file
                        for line in in_file:
                            out_file.write(line)
                    pbar.update(1)

        print(f"All chunks combined into {output_file}")


# Example usage function
def example_usage():
    # Example process function
    def example_process(df):
        # Replace with actual processing
        df = df.fillna(0)
        # Add a new column as an example
        df['processed'] = 1
        return df

    # Initialize chunker
    input_file = "large_file.csv.gz"
    output_dir = "chunks"

    chunker = OptimizedCsvChunker(
        input_file=input_file,
        output_dir=output_dir,
        chunk_size=500000,
        compression_level=4,  # Lower for faster processing
        n_workers=4,  # Adjust based on CPU cores
        use_fast_line_count=True
    )

    # Split file (only happens once)
    chunker.split_file(force_resplit=False)

    # Example 1: Process chunks in parallel and combine results
    chunker.process_chunks_parallel(
        process_function=example_process,
        output_file="processed_output.csv.gz"
    )

    # Example 2: Stream processing (even more memory efficient)
    chunker.stream_process(
        process_function=example_process,
        output_file="streamed_output.csv.gz"
    )

    # Example 3: Simply combine chunks without processing
    chunker.memory_optimized_combine("combined_output.csv.gz")


if __name__ == "__main__":
    example_usage()