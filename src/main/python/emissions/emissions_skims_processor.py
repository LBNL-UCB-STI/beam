import os
import re
import time
import psutil
from pathlib import Path
import polars as pl


class TurboEmissionsProcessor:
    """
    Ultra-fast emissions processor that loads the entire file into memory
    and processes all pollutants in a single pass for maximum speed.
    """

    def __init__(
            self,
            input_file,
            output_dir,
            target_pollutants
    ):
        self.input_file = input_file
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True, parents=True)
        self.target_pollutants = target_pollutants if isinstance(target_pollutants, list) else [target_pollutants]

        # Column selection (only keep what we need)
        self.keep_columns = ['hour', 'linkId', 'vehicleTypeId', 'process',
                             'travelTimeInSecond', 'parkingDurationInSecond',
                             'observations', 'iterations', 'emissions']

        # Pre-compile regex patterns for all pollutants (using raw strings)
        self.regex_patterns = {
            pollutant: re.compile(fr"{pollutant}:([\d\.E\-]+)")
            for pollutant in self.target_pollutants
        }

    def process(self):
        """
        Process the emissions file in a single pass with maximum performance.
        Returns a list of output file paths.
        """
        start_time = time.time()
        print(f"Loading full file into memory using Polars engine...")

        # Report initial memory state
        mem_info = psutil.virtual_memory()
        print(
            f"Memory before loading: {mem_info.used / (1024 ** 3):.2f} GB / {mem_info.total / (1024 ** 3):.2f} GB ({mem_info.percent}%)")

        # Load the entire file using Polars (handles gzip natively)
        df = pl.read_csv(
            self.input_file,
            infer_schema_length=10000,
            low_memory=False,  # Since we're loading everything at once
            n_rows=None,  # Read all rows
            use_pyarrow=True,  # Use PyArrow's CSV parser for speed
        )

        # Report memory after loading
        mem_info = psutil.virtual_memory()
        print(
            f"Memory after loading: {mem_info.used / (1024 ** 3):.2f} GB / {mem_info.total / (1024 ** 3):.2f} GB ({mem_info.percent}%)")
        print(f"Loaded {len(df):,} rows in {time.time() - start_time:.2f} seconds")

        # Keep only the columns we need (reduces memory)
        df = df.select([col for col in self.keep_columns if col in df.columns])

        # Process all pollutants in a single pass
        created_files = []
        extract_time = time.time()

        print(f"Processing {len(self.target_pollutants)} pollutants...")

        for pollutant in self.target_pollutants:
            pollutant_start = time.time()
            pattern = self.regex_patterns[pollutant]

            # Create extraction function using pre-compiled regex pattern
            def extract_value(s):
                if s is None:
                    return None
                match = pattern.search(s)
                if match:
                    try:
                        return float(match.group(1))
                    except (ValueError, TypeError):
                        return None
                return None

            # Filter for rows containing this pollutant (much faster than regex on all rows)
            pollutant_df = df.filter(pl.col("emissions").str.contains(f"{pollutant}:"))

            if len(pollutant_df) > 0:
                # Extract the pollutant value and drop rows with null values
                pollutant_df = (
                    pollutant_df
                    .with_column(
                        pl.col("emissions").map_elements(extract_value).alias(pollutant)
                    )
                    .filter(pl.col(pollutant).is_not_null())
                    .drop("emissions")  # Drop emissions column as we've extracted what we need
                )

                # Write to output file if we have data
                if len(pollutant_df) > 0:
                    input_basename = Path(self.input_file).name
                    input_name = input_basename.split('.')[0]
                    if input_name.endswith('.csv'):
                        input_name = input_name[:-4]

                    output_path = self.output_dir / f"{input_name}_{pollutant}.csv.gz"
                    pollutant_df.write_csv(str(output_path), compression="gzip")

                    created_files.append(str(output_path))
                    print(f"  - {pollutant}: {len(pollutant_df):,} rows in {time.time() - pollutant_start:.2f} seconds")
            else:
                print(f"  - {pollutant}: No data found")

        # Report overall performance
        total_time = time.time() - start_time
        extract_only_time = time.time() - extract_time

        print(f"\nTotal processing time: {total_time:.2f} seconds")
        print(f"Extraction time only: {extract_only_time:.2f} seconds")
        print(f"File loading time: {extract_time - start_time:.2f} seconds")

        throughput = os.path.getsize(self.input_file) / (1024 ** 2) / total_time
        print(f"Overall throughput: {throughput:.2f} MB/s")

        return created_files


def process_pollutants(skims_file, emissions_output_dir, target_pollutants):
    """Process pollutants using the turbo approach (all-in-memory)."""
    print(f"\n{'=' * 80}")
    print("TURBO POLLUTANT EXTRACTION (ALL-IN-MEMORY)")
    print(f"{'=' * 80}")

    # Get system information
    mem_info = psutil.virtual_memory()
    file_size = os.path.getsize(skims_file) / (1024 ** 3)  # Size in GB

    print(f"System memory: {mem_info.total / (1024 ** 3):.1f} GB ({mem_info.percent}% used)")
    print(f"File size: {file_size:.2f} GB")

    # Estimate if we have enough memory (rough estimate)
    estimated_memory_needed = file_size * 5  # Estimate 5x for decompression and processing
    available_memory = (mem_info.total - mem_info.used) / (1024 ** 3)

    if estimated_memory_needed > available_memory:
        print(f"WARNING: This file may require ~{estimated_memory_needed:.1f} GB of RAM,")
        print(f"but only {available_memory:.1f} GB is available.")
        print("The process may use swap space or fail if there's not enough memory.")

        proceed = input("Do you want to proceed anyway? (y/n): ")
        if proceed.lower() != 'y':
            print("Operation cancelled.")
            return []

    # Initialize and run the processor
    processor = TurboEmissionsProcessor(
        input_file=skims_file,
        output_dir=emissions_output_dir,
        target_pollutants=target_pollutants
    )

    # Process the file
    return processor.process()


def main():
    """Main execution function."""
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    skims_file = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.csv.gz"
    output_dir = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/emissions-output"
    os.makedirs(output_dir, exist_ok=True)
    target_pollutants = [
        "CH4", "CO", "CO2", "HC", "NH3", "NOx", "PM", "PM10", "PM2_5", "ROG", "SOx", "TOG", "BC", "BCm", "BCh"
    ]

    process_pollutants(skims_file, output_dir, target_pollutants)


if __name__ == "__main__":
    main()