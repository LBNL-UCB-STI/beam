import gzip
import os
import re
import time

import duckdb
import psutil
from tqdm import tqdm
import pandas as pd

import matplotlib.pyplot as plt
from _emissions_utils import process_color_map


def get_or_upload_emissions_to_duckdb(csv_or_db_file, memory_limit=None):
    """
    Upload emissions CSV data to a DuckDB database for efficient querying.

    Parameters:
    -----------
    input_csv_file : str
        Path to the input CSV.GZ file containing emissions data
    db_path : str, optional
        Path where the DuckDB database will be stored. If None, creates in same dir as CSV
    threads : int, optional
        Number of threads to use for loading. If None, auto-detects based on CPU count

    Returns:
    --------
    str
        Path to the created DuckDB database
    """
    start_time = time.time()

    # Validate input file
    if not os.path.exists(csv_or_db_file):
        raise FileNotFoundError(f"Input file not found: {csv_or_db_file}")

    # Check if the input file is a CSV or a DuckDB database
    if csv_or_db_file.endswith('.duckdb'):
        db_path = csv_or_db_file
        input_csv_file = None
    elif csv_or_db_file.endswith('.csv.gz') or csv_or_db_file.endswith('.csv'):
        db_path = None
        input_csv_file = csv_or_db_file
    else:
        raise ValueError("Input file must be a .csv or .duckdb file")

    # Auto-determine database path if not provided
    if db_path is None:
        db_path = os.path.join(os.path.dirname(input_csv_file),f"{os.path.basename(input_csv_file).split('.')[0]}.duckdb")

    # Create parent directory if it doesn't exist
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)

    print(f"Loading emissions data into DuckDB database at: {db_path}")

    # Check if database already exists and has data
    db_exists = os.path.exists(db_path)
    has_data = False

    if db_exists:
        try:
            # Check if database already has emissions table with data
            conn = duckdb.connect(database=db_path, read_only=True)
            result = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='emissions'").fetchall()
            if result:
                count = conn.execute("SELECT COUNT(*) FROM emissions").fetchone()[0]
                print(f"Database already exists with {count:,} rows")
                has_data = count > 0
            conn.close()
        except Exception as e:
            print(f"Error checking existing database: {str(e)}")
            print("Will recreate database")
            if os.path.exists(db_path):
                os.remove(db_path)
                db_exists = False

    # If database already has data, return early
    if db_exists and has_data:
        print(f"Using existing database at {db_path}")
        return db_path
    # Connect to the database
    conn = duckdb.connect(database=db_path, read_only=False)
    # Configure DuckDB performance settings
    if memory_limit is not None:
        conn.execute(f"SET memory_limit='{memory_limit}'")
    # Auto-detect threads if not specified (leave 1-2 cores free)
    cpu_count = psutil.cpu_count(logical=False) or psutil.cpu_count()
    threads = max(1, cpu_count - 1)
    print(f"Using {threads} threads for processing")
    conn.execute(f"SET threads={threads}")

    try:
        # Additional performance optimizations that should be compatible with most DuckDB versions
        conn.execute("SET checkpoint_threshold='4GB'")  # Higher checkpoint threshold
    except:
        print("Note: Checkpoint threshold setting not supported in this DuckDB version")

    # Load the data
    print("Creating emissions table from CSV data...")
    try:
        # Try with parallel loading if supported
        conn.execute(f"""
            CREATE TABLE emissions AS
            SELECT * FROM read_csv_auto(
                '{input_csv_file}',
                ignore_errors=true,
                all_varchar=false,
                sample_size=10000,
                compression='auto',
                parallel=true
            )
        """)
    except Exception as e:
        print(f"Parallel loading not supported, using standard loading: {str(e)}")
        conn.execute(f"""
            CREATE TABLE emissions AS
            SELECT * FROM read_csv_auto(
                '{input_csv_file}',
                ignore_errors=true,
                all_varchar=false,
                sample_size=10000,
                compression='auto'
            )
        """)

    # # Create index on emissions column for faster searching
    # print("Creating index on emissions column...")
    # try:
    #     conn.execute("CREATE INDEX idx_emissions ON emissions(emissions)")
    # except Exception as e:
    #     print(f"Note: Index creation not supported in this DuckDB version: {str(e)}")

    # Verify loading was successful
    row_count = conn.execute("SELECT COUNT(*) FROM emissions").fetchone()[0]
    print(f"Successfully loaded {row_count:,} rows into database")

    # Analyze table for query optimization
    try:
        conn.execute("ANALYZE emissions")
    except:
        print("Note: ANALYZE command not supported in this DuckDB version")

    # Close the connection
    conn.close()

    load_time = time.time() - start_time
    print(f"Database creation completed in {load_time:.2f} seconds")
    return db_path


def extract_pollutant(db_path, pollutant, output_path, compress=True):
    """
    Extract a specific pollutant from the emissions database.
    Handles semicolon-delimited emissions data.

    Parameters:
    -----------
    db_path : str
        Path to the DuckDB database containing emissions data
    pollutant : str
        Name of the pollutant to extract (e.g., 'CO2', 'NOx')
    output_path : str
        Path where the output CSV file will be saved
    compress : bool, optional
        Whether to compress the output file as .csv.gz (True) or leave as .csv (False)

    Returns:
    --------
    str
        Path to the created output file
    int
        Number of rows extracted
    """
    start_time = time.time()

    # Validate inputs
    if not os.path.exists(db_path):
        raise FileNotFoundError(f"Database file not found: {db_path}")

    # Ensure output directory exists
    output_dir = os.path.dirname(os.path.abspath(output_path))
    os.makedirs(output_dir, exist_ok=True)

    # Determine temporary and final paths
    if compress and not output_path.endswith('.gz'):
        output_path = f"{output_path}.gz"

    temp_output_path = output_path
    if compress:
        temp_output_path = output_path.replace('.gz', '')

    print(f"Extracting pollutant '{pollutant}' from database: {db_path}")

    # Connect to the database
    conn = duckdb.connect(database=db_path, read_only=True)

    try:
        # Check if database has emissions table
        result = conn.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='emissions'").fetchall()
        if not result:
            raise ValueError("Database does not contain an emissions table")

        # Count matching rows for progress information
        count_query = f"""
            SELECT COUNT(*) 
            FROM emissions 
            WHERE emissions LIKE '%{pollutant}:%'
        """
        matching_rows = conn.execute(count_query).fetchone()[0]
        print(f"Found {matching_rows:,} rows containing {pollutant}")

        if matching_rows == 0:
            print(f"No data found for pollutant: {pollutant}")
            return None, 0

        # Get the raw data with emissions column - we'll extract values using pandas
        # since the delimiter is semicolon instead of comma
        query = f"""
            SELECT 
                hour, 
                linkId, 
                vehicleTypeId, 
                process, 
                travelTimeInSecond, 
                observations, 
                iterations,
                emissions
            FROM emissions
            WHERE emissions LIKE '%{pollutant}:%'
        """

        # Execute the query and get results as DataFrame
        print(f"Fetching data for {pollutant}...")
        df = conn.execute(query).df()

        if len(df) == 0:
            print(f"No data found for pollutant: {pollutant}")
            return None, 0

        # Extract pollutant values using regex
        print(f"Extracting {pollutant} values...")
        # Pattern to match pollutant:value followed by semicolon or end of string
        pattern = re.compile(fr'{pollutant}:([\d\.E\-]+)(;|$)')

        # Apply extraction
        df[pollutant] = df['emissions'].apply(
            lambda x: float(pattern.search(x).group(1)) if pattern.search(x) else None
        )

        # Drop rows with missing values and the original emissions column
        df = df.dropna(subset=[pollutant])
        df = df.drop(columns=['emissions'])

        if len(df) == 0:
            print(f"No valid data found for pollutant: {pollutant}")
            return None, 0

        # Write to CSV
        print(f"Writing {len(df):,} rows to output file...")
        df.to_csv(temp_output_path, index=False)

        # Compress if needed
        if compress:
            print(f"Compressing output file...")
            with open(temp_output_path, 'rb') as f_in:
                with gzip.open(output_path, 'wb', compresslevel=6) as f_out:
                    # Get file size for progress bar
                    f_in.seek(0, os.SEEK_END)
                    file_size = f_in.tell()
                    f_in.seek(0)

                    # Use progress bar for compression
                    with tqdm(total=file_size, unit='B', unit_scale=True,
                              desc=f"Compressing {pollutant} data") as pbar:
                        # Use larger buffer for better performance
                        buffer_size = 4 * 1024 * 1024  # 4MB buffer
                        while True:
                            chunk = f_in.read(buffer_size)
                            if not chunk:
                                break
                            f_out.write(chunk)
                            pbar.update(len(chunk))

            # Remove temporary uncompressed file
            os.remove(temp_output_path)
            final_path = output_path
        else:
            final_path = temp_output_path

        rows_extracted = len(df)
        processing_time = time.time() - start_time
        print(f"Extracted {rows_extracted:,} rows with {pollutant} in {processing_time:.2f} seconds")
        return final_path, rows_extracted

    except Exception as e:
        print(f"Error extracting pollutant {pollutant}: {str(e)}")
        # Clean up any temporary files
        if os.path.exists(temp_output_path):
            os.remove(temp_output_path)
        return None, 0

    finally:
        # Close the connection
        conn.close()


def process_by_link_type_process(skims_db, pollutants, group_by_clauses, output_file, multiplier_factor, pollutant_suffix):
    """
    Process emissions data directly from DuckDB database by link type, vehicle type, and process.
    Handles semicolon-delimited emissions data with robust error handling.
    If the output file already exists, reads and returns it instead of reprocessing.

    Parameters:
    -----------
    skims_db : str
        Path to the DuckDB database
    pollutants : list
        List of pollutant names to process
    group_by_clauses : list
        Columns to group by
    output_file : str
        Path to save the output file

    Returns:
    --------
    pandas.DataFrame
        Merged DataFrame with all pollutants
    """
    # Check if output file already exists
    if os.path.exists(output_file):
        print(f"Output file {output_file} already exists. Reading existing file...")
        try:
            result_df = pd.read_csv(output_file, compression='gzip')
            print(f"Successfully loaded existing file with {len(result_df)} rows.")
            return result_df
        except Exception as e:
            print(f"Error reading existing file: {e}")
            print("Will reprocess the data...")

    # Connect to the database
    conn = duckdb.connect(database=skims_db, read_only=True)

    try:
        # Construct the SELECT clause for each pollutant
        select_clauses = list(group_by_clauses) + ["SUM(observations) as observations"]

        for pollutant in pollutants:
            # Add calculation for total pollutant
            select_clauses.append(f"""
                SUM(
                    CASE 
                        WHEN REGEXP_MATCHES(emissions, '{pollutant}:([\\d\\.E\\-]+)(;|$)') 
                        THEN CAST(NULLIF(REGEXP_EXTRACT(emissions, '{pollutant}:([\\d\\.E\\-]+)(;|$)', 1), '') AS DOUBLE) * observations * {multiplier_factor}
                        ELSE 0 
                    END
                ) AS {pollutant}_{pollutant_suffix}
            """)

        # Construct the WHERE clause to filter for rows containing any of the pollutants
        where_conditions = []
        for pollutant in pollutants:
            where_conditions.append(f"emissions LIKE '%{pollutant}:%'")

        where_clause = " OR ".join(where_conditions)

        # Build the final query
        query = f"""
        SELECT 
            {', '.join(select_clauses)}
        FROM emissions
        WHERE {where_clause}
        GROUP BY {', '.join(group_by_clauses)}
        """

        print("Executing query to extract and aggregate pollutants...")
        print(f"Query: grouped by {', '.join(group_by_clauses)} for pollutants: {', '.join(pollutants)}")
        result_df = conn.execute(query).df()

        if len(result_df) == 0:
            print("No data found for any of the specified pollutants")
            return None

        # Create directory if it doesn't exist
        os.makedirs(os.path.dirname(output_file), exist_ok=True)

        # Save the result to a CSV file
        result_df.to_csv(output_file, index=False, compression='gzip')
        print(f"Saved merged emissions skims to {output_file}")

        return result_df

    finally:
        # Close the connection
        conn.close()


def plot_pollutants_by_process(skims, scenario, plot_dir, height_size, font_size):
    process_order = list(process_color_map.keys())
    grouped = skims.groupby(['pollutant', 'process'])['tons_year'].sum().unstack().reindex(columns=process_order)
    normalized = grouped.div(grouped.sum(axis=1), axis=0) * 100
    csv_filename = f'{plot_dir}/emissions_by_process_{scenario.replace(" ", "_").lower()}.csv'
    normalized.to_csv(csv_filename)

    # Create the plot
    fig, ax = plt.subplots(figsize=(20, height_size))
    normalized.plot(kind='bar', stacked=True, ax=ax, color=[process_color_map[col] for col in normalized.columns])
    plt.title(f'Normalized Emissions by Process - {scenario}', fontsize=font_size + 4)
    plt.xlabel('Emissions', fontsize=font_size)
    plt.ylabel('Relative Process Contribution (%)', fontsize=font_size)
    plt.xticks(rotation=0, ha='center', fontsize=font_size)
    plt.yticks(fontsize=font_size)
    ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda y, _: '{:.0f}%'.format(y)))
    ax.set_ylim(0, 100)
    legend = plt.legend(title='Process', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=font_size)
    plt.setp(legend.get_title(), fontsize=font_size)
    plt.tight_layout()
    plt.savefig(
        f'{plot_dir}/emissions_by_process_{scenario.replace(" ", "_").lower()}.png',
        dpi=300,
        bbox_inches='tight'
    )
    plt.show()


def plot_emissions_by_mode_and_pollutant(skims, pollutant, scenario, plot_dir, width_size, height_size, font_size):
    process_order = list(process_color_map.keys())

    pollutant_data = skims[skims['pollutant'] == pollutant].copy()
    grouped = pollutant_data.groupby(['mode', 'process'])['tons_year'].sum().unstack().reindex(columns=process_order)
    grouped = grouped.fillna(0)

    csv_filename = f'{plot_dir}/emissions_by_mode_{pollutant}_{scenario.replace(" ", "_").lower()}.csv'
    grouped.to_csv(csv_filename)

    # Create the plot
    fig, ax = plt.subplots(figsize=(width_size, height_size))
    grouped.plot(kind='bar', stacked=True, ax=ax, color=[process_color_map[col] for col in grouped.columns])
    plt.title(f'{pollutant} Emissions by Mode - {scenario}', fontsize=font_size + 4)
    plt.xlabel('Mode', fontsize=font_size)
    plt.ylabel(f'{pollutant} Emissions (tons/year)', fontsize=font_size)
    plt.xticks(rotation=0, ha='center', fontsize=font_size)
    plt.yticks(fontsize=font_size)
    ax.yaxis.set_major_formatter(plt.FuncFormatter(lambda y, _: '{:,.0f}'.format(y)))
    legend = plt.legend(title='Process', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=font_size)
    plt.setp(legend.get_title(), fontsize=font_size)
    plt.tight_layout()
    plt.savefig(
        f'{plot_dir}/emissions_by_mode_{pollutant}_{scenario.replace(" ", "_").lower()}.png',
        dpi=300,
        bbox_inches='tight'
    )
    plt.show()


if __name__ == "__main__":
    # Example inputs
    sample_size = 0.1
    grams_to_us_tons = 1 / 907185
    days_per_year = 320
    multiplier_factor = (1 / sample_size) * grams_to_us_tons * days_per_year
    pollutants = ["CH4", "CO", "CO2", "HC", "NH3", "N2O", "NOx", "PM", "PM10", "PM25", "ROG", "SOx", "TOG", "BC"]

    # Files
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    # skims_file = f"/Users/haitamlaarabi/Workspace/Models/beam/trap/output/sf-light/sflight-11-emissions-urbansim_v2__2025-04-13_14-06-48_hif/ITERS/it.0/0.skimsEmissions.csv.gz"
    skims_file = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.csv.gz"
    output_dir = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/emissions-output"
    os.makedirs(output_dir, exist_ok=True)
    skims_db_file = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.duckdb"
    freight_types_file = f"{work_dir}/vehicle-tech/vehicleTypes--frism--2018-Baseline--EM.csv"
    passenger_types_file = f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2017-Baseline--EM.csv"

    # Loading
    run_dir = os.path.dirname(skims_db_file)
    skims_db = get_or_upload_emissions_to_duckdb(csv_or_db_file=skims_db_file)
    freight_types = pd.read_csv(freight_types_file)
    passenger_types = pd.read_csv(passenger_types_file)

    # Processing
    plot_pollutants_by_process_flag = False
    plot_pollutants_by_process_and_demand_flag = False
    plot_pollutant_total_by_demand = True

    # pollutants = ["PM25", "NOx", "CO2"]
    # group_by = ["linkId", "vehicleTypeId", "process"]
    # process_by_link_type_process(
    #     skims_db = skims_db,
    #     pollutants = pollutants,
    #     group_by_clauses = group_by,
    #     output_file = f"{run_dir}/emissions_by_{'_'.join(group_by)}_for_{'_'.join(pollutants)}.csv.gz"
    # )

    # pollutants = ["PM25", "NOx", "CO2"]
    # group_by = ["process"]
    # result = process_by_link_type_process(
    #     skims_db = skims_db,
    #     pollutants = pollutants,
    #     group_by_clauses = group_by,
    #     output_file = f"{run_dir}/emissions_by_{'_'.join(group_by)}_for_{'_'.join(pollutants)}.csv.gz"
    # )
    # tot_co2 = result["CO2"].sum()
    # result["CO2_share"] = result["CO2"]/tot_co2
    # print(result.head(20))

    if plot_pollutants_by_process_and_demand_flag:
        group_by = ["process", "vehicleTypeId"]
        result = process_by_link_type_process(
            skims_db = skims_db,
            pollutants = pollutants,
            group_by_clauses = group_by,
            output_file = f"{run_dir}/emissions_by_{'_'.join(group_by)}_for_all.csv.gz",
            multiplier_factor = multiplier_factor,
            pollutant_suffix = "tons_year"
        )
        freight_types_ids = freight_types["vehicleTypeId"].unique()
        freight_result = result[result["vehicleTypeId"].isin(freight_types_ids)]
        melted_df = pd.melt(freight_result,
            id_vars=['process'],
            value_vars=[col for col in result.columns if col.endswith('_tons_year')],
            var_name='pollutant',
            value_name='tons_year'
        )
        melted_df['pollutant'] = melted_df['pollutant'].str.replace('_tons_year', '')
        melted_df = melted_df[['process', 'pollutant', 'tons_year']]
        melted_df = melted_df[melted_df['tons_year'] > 0].copy()
        plot_pollutants_by_process(
            melted_df,
            scenario="2018 Baseline Freight Only",
            plot_dir=output_dir,
            height_size=6,
            font_size=20
        )

    if plot_pollutants_by_process_flag:
        group_by = ["process"]
        result = process_by_link_type_process(
            skims_db = skims_db,
            pollutants = pollutants,
            group_by_clauses = group_by,
            output_file = f"{run_dir}/emissions_by_{'_'.join(group_by)}_for_all.csv.gz",
            multiplier_factor = multiplier_factor,
            pollutant_suffix = "tons_year"
        )
        melted_df = pd.melt(result,
            id_vars=['process'],
            value_vars=[col for col in result.columns if col.endswith('_tons_year')],
            var_name='pollutant',
            value_name='tons_year'
        )
        melted_df['pollutant'] = melted_df['pollutant'].str.replace('_tons_year', '')
        melted_df = melted_df[['process', 'pollutant', 'tons_year']]
        melted_df = melted_df[melted_df['tons_year'] > 0].copy()
        plot_pollutants_by_process(
            melted_df,
            scenario="2018 Baseline Passenger and Freight",
            plot_dir=output_dir,
            height_size=6,
            font_size=20
        )

    if plot_pollutant_total_by_demand:
        group_by = ["process", "vehicleTypeId"]
        result = process_by_link_type_process(
            skims_db = skims_db,
            pollutants = pollutants,
            group_by_clauses = group_by,
            output_file = f"{run_dir}/emissions_by_{'_'.join(group_by)}_for_all.csv.gz",
            multiplier_factor = multiplier_factor,
            pollutant_suffix = "tons_year"
        )
        melted_df = pd.melt(result,
                            id_vars=['process', 'vehicleTypeId'],
                            value_vars=[col for col in result.columns if col.endswith('_tons_year')],
                            var_name='pollutant',
                            value_name='tons_year'
                            )
        melted_df['pollutant'] = melted_df['pollutant'].str.replace('_tons_year', '')
        melted_df = melted_df[['process', 'vehicleTypeId', 'pollutant', 'tons_year']]
        melted_df = melted_df[melted_df['tons_year'] > 0].copy()

        # Get unique vehicle type IDs for each category
        freight_types_ids = freight_types["vehicleTypeId"].unique()
        car_types_ids = passenger_types[passenger_types["vehicleCategory"].str.lower() == "car"][
            "vehicleTypeId"].unique()
        bike_types_ids = passenger_types[passenger_types["vehicleCategory"].str.lower() == "bike"][
            "vehicleTypeId"].unique()
        bus_types_ids = passenger_types[
            (passenger_types["vehicleCategory"].str.lower() == "mediumdutypassenger") &
            (passenger_types["vehicleTypeId"].str.lower().str.contains("bus"))
            ]["vehicleTypeId"].unique()

        melted_df["mode"] = ""
        melted_df.loc[melted_df["vehicleTypeId"].isin(car_types_ids), "mode"] = "Car"
        melted_df.loc[melted_df["vehicleTypeId"].isin(bike_types_ids), "mode"] = "Bike"
        melted_df.loc[melted_df["vehicleTypeId"].isin(bus_types_ids), "mode"] = "Bus"
        melted_df.loc[melted_df["vehicleTypeId"].isin(freight_types_ids), "mode"] = "MHD"

        grouped_df = melted_df.groupby(["process", "pollutant", "mode"])["tons_year"].sum().reset_index()
        plot_emissions_by_mode_and_pollutant(
            grouped_df,
            pollutant="PM25",
            scenario="2018 Baseline Passenger and Freight",
            plot_dir=output_dir,
            width_size=16,
            height_size=6,
            font_size=18
        )


    print("END")
