import os
import time
from pathlib import Path
import pandas as pd
import re
import gzip
import shutil
import polars as pl
import psutil


class MemoryEfficientProcessor:
    """
    Memory-efficient processor that uses lazy evaluation and streaming
    to avoid loading the entire file into memory at once.
    """

    def __init__(
            self,
            input_file,
            output_dir,
            target_pollutants,
            batch_size=100000  # Process in more manageable chunks
    ):
        self.input_file = input_file
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True, parents=True)
        self.target_pollutants = target_pollutants if isinstance(target_pollutants, list) else [target_pollutants]
        self.batch_size = batch_size
        # Column selection (only keep what we need)
        self.keep_columns = ['hour', 'linkId', 'vehicleTypeId', 'process',
                             'travelTimeInSecond', 'parkingDurationInSecond',
                             'observations', 'iterations', 'emissions']

        # Print version information
        print(f"Pandas version: {pd.__version__}")
        print(f"Polars version: {pl.__version__}")

    def process(self):
        """
        Process the emissions file using lazy evaluation to minimize memory usage.
        Returns a list of output file paths.
        """
        start_time = time.time()
        print(f"Processing file using memory-efficient streaming approach...")

        # Report initial memory state
        mem_info = psutil.virtual_memory()
        print(
            f"Memory before processing: {mem_info.used / (1024 ** 3):.2f} GB / {mem_info.total / (1024 ** 3):.2f} GB ({mem_info.percent}%)")

        created_files = []

        print(f"Processing {len(self.target_pollutants)} pollutants...")

        # Get the column names from the file first (lightweight operation)
        try:
            schema = pl.scan_csv(self.input_file, n_rows=10).collect().columns
            available_columns = [col for col in self.keep_columns if col in schema]
        except Exception as e:
            print(f"Error getting schema, falling back to basic schema: {str(e)}")
            schema = self.keep_columns  # Initialize schema to prevent reference errors
            available_columns = self.keep_columns

        # Process one pollutant at a time to minimize memory usage
        for pollutant in self.target_pollutants:
            pollutant_start = time.time()

            # Prepare the output file path
            filename = os.path.basename(self.input_file)
            if filename.endswith(".csv.gz"):
                new_file_name = filename.replace(".csv.gz", f"_{pollutant}.csv.gz")
            else:
                new_file_name = filename.replace(".csv", f"_{pollutant}.csv.gz")

            output_path = self.output_dir / new_file_name
            temp_output_path = str(output_path).replace('.gz', '')  # Non-compressed version

            try:
                # Process in streaming mode - filter first to reduce memory
                query = (
                    pl.scan_csv(self.input_file)
                    .filter(pl.col("emissions").str.contains(f"{pollutant}:"))
                    .select([
                        *available_columns,  # Directly use available columns
                        pl.col("emissions").str.extract(fr"{pollutant}:([\d\.E\-]+)", group_index=1)
                        .cast(pl.Float64).alias(pollutant)
                    ])
                    .filter(pl.col(pollutant).is_not_null())
                )

                # Execute the query and write results to uncompressed file first
                try:
                    result = query.collect()
                    rows_processed = len(result)

                    if rows_processed > 0:
                        # Write to uncompressed file first
                        result.write_csv(temp_output_path)

                        # Then compress it
                        with open(temp_output_path, 'rb') as f_in:
                            with gzip.open(str(output_path), 'wb') as f_out:
                                shutil.copyfileobj(f_in, f_out)

                        # Remove the uncompressed file
                        os.remove(temp_output_path)

                        created_files.append(str(output_path))
                        print(
                            f"  - {pollutant}: {rows_processed:,} rows in {time.time() - pollutant_start:.2f} seconds")
                    else:
                        print(f"  - {pollutant}: No data found")

                except Exception as e:
                    print(f"  - Error during query execution or file writing: {str(e)}")
                    raise Exception("Force pandas fallback")

            except Exception as e:
                # Skip the chunked processing with polars since it's failing
                # and go straight to pandas which is working
                print(f"  - Streaming failed for {pollutant}, using pandas: {str(e)}")

                # Try pandas as reliable fallback
                try:
                    # Function to process chunks with pandas
                    def process_chunk(chunk):
                        if "emissions" not in chunk.columns:
                            return pd.DataFrame()

                        # Ensure emissions is a string column
                        if not pd.api.types.is_string_dtype(chunk['emissions']):
                            chunk['emissions'] = chunk['emissions'].astype(str)

                        # Filter rows with the pollutant
                        mask = chunk['emissions'].str.contains(f"{pollutant}:", na=False)
                        if not mask.any():
                            return pd.DataFrame()

                        filtered = chunk.loc[mask].copy()

                        # Extract pollutant value
                        pattern = re.compile(fr"{pollutant}:([\d\.E\-]+)")
                        filtered[pollutant] = filtered['emissions'].apply(
                            lambda x: float(pattern.search(x).group(1)) if isinstance(x, str) and pattern.search(
                                x) else None
                        )

                        # Drop rows with missing values and the emissions column
                        filtered = filtered.dropna(subset=[pollutant])
                        if "emissions" in filtered.columns:
                            filtered = filtered.drop(columns=["emissions"])

                        return filtered

                    # Process in chunks
                    total_rows = 0
                    first_chunk = True

                    for chunk in pd.read_csv(
                            self.input_file,
                            compression='infer',
                            chunksize=self.batch_size,  # This is valid for pandas
                            low_memory=True
                    ):
                        # Keep only necessary columns to save memory
                        cols_to_keep = [col for col in self.keep_columns if col in chunk.columns]
                        if "emissions" not in cols_to_keep:
                            cols_to_keep.append("emissions")
                        chunk = chunk[cols_to_keep]

                        # Process chunk
                        result = process_chunk(chunk)
                        if len(result) > 0:
                            # Write to CSV
                            mode = 'w' if first_chunk else 'a'
                            header = first_chunk
                            result.to_csv(str(output_path), mode=mode, index=False, header=header,
                                          compression='gzip')
                            first_chunk = False
                            total_rows += len(result)

                        # Clean up memory
                        del chunk, result
                        import gc
                        gc.collect()

                    if total_rows > 0:
                        created_files.append(str(output_path))
                        print(
                            f"  - {pollutant}: {total_rows:,} rows processed with pandas in {time.time() - pollutant_start:.2f} seconds")
                    else:
                        # Remove empty files
                        if os.path.exists(output_path):
                            os.remove(output_path)
                        print(f"  - {pollutant}: No data found")

                except Exception as e:
                    print(f"  - All processing methods failed for {pollutant}: {str(e)}")

            # Monitor memory usage
            if (time.time() - start_time) % 60 < 5:  # Report memory every ~60 seconds
                mem_info = psutil.virtual_memory()
                print(
                    f"Memory usage: {mem_info.used / (1024 ** 3):.2f} GB / {mem_info.total / (1024 ** 3):.2f} GB ({mem_info.percent}%)")

        # Report overall performance
        total_time = time.time() - start_time
        print(f"\nTotal processing time: {total_time:.2f} seconds")
        print(f"Throughput: {os.path.getsize(self.input_file) / (1024 ** 2) / total_time:.2f} MB/s")

        return created_files


def process_pollutants(skims_file, emissions_output_dir, target_pollutants):
    """Process pollutants using memory-efficient streaming approach."""
    print(f"\n{'=' * 80}")
    print("MEMORY-EFFICIENT EMISSIONS PROCESSOR")
    print(f"{'=' * 80}")

    # Get system information
    mem_info = psutil.virtual_memory()
    file_size = os.path.getsize(skims_file) / (1024 ** 3)  # Size in GB

    print(f"System memory: {mem_info.total / (1024 ** 3):.1f} GB ({mem_info.percent}% used)")
    print(f"Input file size: {file_size:.2f} GB")

    # Calculate batch size based on available memory, with reasonable limits
    available_memory = (mem_info.available * 0.5) / (1024 ** 3)  # Use up to 50% of available RAM
    estimated_expansion = 5  # Estimate of how much a compressed file expands in memory

    # Cap the batch size to something reasonable
    batch_size = max(10000,
                     min(1000000, int((available_memory * 1024 * 1024) / (file_size * estimated_expansion * 10))))
    print(f"Using batch size of {batch_size:,} rows")

    # Initialize and run the processor
    processor = MemoryEfficientProcessor(
        input_file=skims_file,
        output_dir=emissions_output_dir,
        target_pollutants=target_pollutants,
        batch_size=batch_size
    )

    # Process the file
    return processor.process()


def main():
    """Main execution function."""
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    # skims_file = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.csv.gz"
    skims_file = f"/Users/haitamlaarabi/Workspace/Models/beam/trap/output/sf-light/sflight-11-emissions-urbansim_v2__2025-04-13_14-06-48_hif/ITERS/it.0/0.skimsEmissions.csv.gz"
    output_dir = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/emissions-output"
    os.makedirs(output_dir, exist_ok=True)
    target_pollutants = [
        "CH4", "CO", "CO2", "HC", "NH3", "NOx", "PM", "PM10", "PM2_5", "ROG", "SOx", "TOG", "BC", "BCm", "BCh"
    ]

    process_pollutants(skims_file, output_dir, target_pollutants)


if __name__ == "__main__":
    main()