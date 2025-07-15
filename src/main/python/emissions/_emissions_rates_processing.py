import os
import sys
from multiprocessing import Pool

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.csv as csv
from tqdm import tqdm
from tqdm.auto import tqdm

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.files_utils import check_files
from python.utils.study_area_config import emissions_config


def calculate_road_dust_emissions(silt_loading, rainy_days):
    """
    Calculate road dust emissions based on EPA AP-42 methodology.

    Parameters:
    silt_loading (float): Roadway-specific silt loading in grams/square meter
    rainy_days (int): Number of wet days in the year

    Returns:
    tuple: PM2.5, PM10, and total PM emission factors in grams/vehicle-mile
    """
    # Constants
    k = 0.0022  # particle size multiplier for PM10 in lb/VMT
    W = 2.4  # average weight of vehicles in tons
    N = 365  # number of days in annual averaging period

    # Fractions of pollutants among road dust
    pm_25_frac = 0.0686
    pm_10_frac = 0.4572
    pm_frac = 0.5428

    # Calculate PM10 emission factor in lb/VMT
    E_10 = k * (silt_loading ** 0.91) * (W ** 1.02) * (1 - rainy_days / N / 4)

    # Calculate total PM emission factor
    E_total = E_10 / pm_10_frac

    # Calculate PM2.5 emission factor
    E_25 = E_total * pm_25_frac

    # Convert from lb/VMT to g/VMT (1 lb = 453.592 g)
    E_25_g = E_25 * 453.592
    E_10_g = E_10 * 453.592
    E_total_g = E_total * 453.592

    return E_25_g, E_10_g, E_total_g


def generate_road_dust_rates(rainy_days_file, silt_loading_file, air_basin_region):
    """
    Process rainy days and silt loading data to create road dust emission rates.

    Parameters:
    rainy_days_file (str): Path to the rainy days CSV file
    silt_loading_file (str): Path to the silt loading CSV file
    air_basin_region (list): List of air basins to filter by

    Returns:
    pd.DataFrame: DataFrame with road dust emission rates
    """
    # Map BEAM/OSM road types to CARB silt loading road categories
    silt_beam2carb_map = {
        'motorway': 'Freeway',
        'motorway_link': 'Freeway',
        'trunk': 'Freeway',
        'trunk_link': 'Major',
        'primary': 'Major',
        'primary_link': 'Major',
        'secondary': 'Collector',
        'secondary_link': 'Collector',
        'tertiary': 'Collector',
        'tertiary_link': 'Collector',
        'unclassified': 'Collector',
        'residential': 'Local Urban'
    }

    # Load silt loading data
    silt_loading_df = pd.read_csv(silt_loading_file)

    # Ensure consistent county names across datasets
    silt_loading_df['County'] = silt_loading_df['County'].str.strip().str.lower()
    silt_loading_df['Air Basin'] = silt_loading_df['Air Basin'].str.strip()
    silt_filtered_df = silt_loading_df[silt_loading_df['Air Basin'].isin(air_basin_region)]
    if silt_filtered_df.empty:
        raise ValueError(f"No data found in silt loading for the specified air basins: {air_basin_region}")
    road_categories = ['Freeway', 'Major', 'Collector', 'Local Urban', 'Local Rural']
    county_averages = silt_filtered_df.groupby('County')[road_categories].mean().reset_index()
    county_averages = county_averages.sort_values('County')

    # Load rainy days data
    rainy_days_df = pd.read_csv(rainy_days_file)
    rainy_days_df['County'] = rainy_days_df['County'].str.strip().str.lower()
    rainy_days_df['Air Basin'] = rainy_days_df['Air Basin'].str.strip()
    rainy_filtered_df = rainy_days_df[rainy_days_df['Air Basin'].isin(air_basin_region)]
    if rainy_filtered_df.empty:
        raise ValueError(f"No data found in rainy days for the specified air basins: {air_basin_region}")
    rainfall_averages = rainy_filtered_df.groupby('County')['Annual Rainfall Days'].mean().reset_index()
    rainfall_averages = rainfall_averages.sort_values('County')

    # Merge county silt loading with rainy days data
    merged_data = pd.merge(county_averages, rainfall_averages, on='County', how='inner')

    # Initialize lists to store emissions data for all BEAM/OSM road types
    all_rows = []

    # Calculate road dust emissions for each county and road type
    print("Calculating road dust emissions...")
    total_iterations = len(merged_data) * len(silt_beam2carb_map)
    with tqdm(total=total_iterations, desc="Processing counties and road types") as pbar:
        for _, row in merged_data.iterrows():
            county = row['County']
            rainy_days = row['Annual Rainfall Days']

            # Create a dictionary to map CARB road categories to their silt loading values for this county
            carb_road_to_silt = {road_type: row[road_type] for road_type in road_categories}

            # Process each BEAM/OSM road type
            for beam_road_type, carb_road_type in silt_beam2carb_map.items():
                silt_loading = carb_road_to_silt[carb_road_type]

                # Calculate emission factors
                pm25, pm10, pm_total = calculate_road_dust_emissions(silt_loading, rainy_days)

                # Create a dictionary for this row
                row_dict = {
                    'county': county,
                    'process': 'PRDUST',
                    'rate_pm2_5_gram_float': pm25,
                    'rate_pm10_gram_float': pm10,
                    'rate_pm_gram_float': pm_total,
                    'road_category': beam_road_type,
                    'carb_road_category': carb_road_type,
                    'silt_loading': silt_loading,
                    'rainy_days': rainy_days
                }

                all_rows.append(row_dict)
                pbar.update(1)

    # Create emissions DataFrame
    _emissions_df = pd.DataFrame(all_rows)

    # Reorder columns to match required format
    column_order = [
        'county',
        'road_category',
        'process',
        'rate_pm_gram_float',
        'rate_pm10_gram_float',
        'rate_pm2_5_gram_float'
    ]

    return _emissions_df[column_order]

def numerical_column_to_binned_and_pivot(df_raw, numerical_colname, binned_colname, edge_values):
    pivot_df = pivot_rates_for_beam(df_raw).sort_values(by='speed_time', ascending=True)
    df_raw_last_row = pivot_df.iloc[-1].copy()
    df_raw_last_row['speed_time'] = edge_values[1]
    pivot_df = pd.concat([pivot_df, pd.DataFrame([df_raw_last_row])], ignore_index=True)
    col_sorted = sorted(pivot_df[numerical_colname].unique())
    col_bins = [edge_values[0]] + col_sorted
    col_labels = [f"[{col_bins[i]}, {col_bins[i + 1]})" for i in range(len(col_bins) - 1)]
    pivot_df[binned_colname] = pd.cut(pivot_df[numerical_colname], bins=col_bins, labels=col_labels, right=True)
    return pivot_df

def pivot_rates_for_beam(df_raw):
    unique_speed_time = df_raw.speed_time.unique()
    has_non_empty_speed_time = any(len(str(x)) > 0 for x in unique_speed_time) and not pd.isnull(
        unique_speed_time).all()
    index_ = ["emfacId", 'county', 'process']
    if has_non_empty_speed_time:
        index_.append("speed_time")
    pivot_df = df_raw.pivot_table(index=index_, columns='pollutant', values='emission_rate', aggfunc='first',
                                  fill_value=0).reset_index()
    pivot_df = pivot_df.rename(columns=emissions_config["pollutants"])
    # Add missing columns with default values
    for col in emissions_config["pollutants"].values():
        if col not in pivot_df.columns:
            pivot_df[col] = 0.0
    pivot_df.insert(0, 'speed_mph_float_bins', "")
    pivot_df.insert(1, 'time_minutes_float_bins', "")
    return pivot_df


def process_rates_group(df, row):
    mask = ((df["county"] == row["county"]) & (df["emfacId"] == row["emfacId"]))
    df_subset = df[mask]
    df_output_list = []

    # # Extract PM-related pollutant columns
    # pm_columns = [value for key, value in emissions_config["pollutants"].items() if key.startswith('PM')]

    # Add progress bar for processing each emissions process
    print(f"Processing emissions for county: {row['county']}, emfacId: {row['emfacId']}")
    for process in tqdm(emissions_config["processes"], desc="Processing emission processes"):
        df_temp = df_subset[df_subset['process'] == process]
        if not df_temp.empty:
            if process in ['RUNEX', 'PMBW']:
                df_temp = numerical_column_to_binned_and_pivot(df_temp, 'speed_time', 'speed_mph_float_bins',
                                                               [0.0, 200.0])
            elif process == 'STREX':
                df_temp = numerical_column_to_binned_and_pivot(df_temp, 'speed_time', 'time_minutes_float_bins',
                                                               [0.0, 3600.0])
            else:
                df_temp = pivot_rates_for_beam(df_temp)

            # if emissions_version == "EMFAC2021":
            #     if process == 'PMTW' and row.get('fuel').isin(['Elec', 'Phe']):
            #         # Apply 15% increase to PM-related columns
            #         # EMFAC2021 underestimated tire wear emissions for electric vehicles
            #         # https://ww2.arb.ca.gov/sites/default/files/2024-11/3rd%20Workshop%20Draft%20Slides%20FINAL%20ADA.pdf
            #         for col in pm_columns:
            #             df_temp[col] = df_temp[col] * 1.15

            df_output_list.append(df_temp)

    return pd.concat(df_output_list, ignore_index=True) if df_output_list else pd.DataFrame()

# Define this function at module level (outside any other function)
def process_chunk(chunk_data):
    chunk, emissions_df = chunk_data
    results = []
    # Process each row in the chunk
    for _, row in tqdm(chunk.iterrows(), total=len(chunk),
                       desc=f"Processing chunk with {len(chunk)} rows",
                       leave=False):
        result = process_rates_group(emissions_df, row)
        results.append(result)

    return pd.concat(results, ignore_index=True) if results else pd.DataFrame()


def process_emfac_rates(
        emfac_rates_by_model_year_file,
        format_func,
        season_month,
        calendar_year,
        air_basin_area,
        temperature,
        relative_humidity,
        include_nan=True):
    """
    Process EMFAC emissions rates with improved air basin filtering.

    Args:
        emfac_rates_by_model_year_file: Path to EMFAC rates input file
        format_func: Function to format the data
        season_month: Season or month to filter by
        calendar_year: Calendar year to filter by
        air_basin_area: Air basin area(s) to filter by (can be list or single string)
        temperature: Temperature to filter by
        relative_humidity: Relative humidity to filter by
        include_nan: Whether to include NaN values in filtering

    Returns:
        DataFrame with processed EMFAC rates
    """
    # Process the emissions data
    print(f"Reading CSV file: {emfac_rates_by_model_year_file}")
    table = csv.read_csv(emfac_rates_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
    df = table.to_pandas()
    print(f"CSV file loaded. Shape: {df.shape}")

    # Apply filters based on config
    print("Applying filters...")
    if 'season_month' in df.columns:
        print(f"Filtering by season_month: {season_month}")
        df = df[(df['season_month'] == season_month) | (include_nan & df['season_month'].isna())]
        print(f"After season_month filter. Shape: {df.shape}")

    if 'calendar_year' in df.columns:
        print(f"Filtering by calendar_year: {calendar_year}")
        df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]
        print(f"After calendar_year filter. Shape: {df.shape}")

    # Improved air basin area filtering to handle partial matches
    if 'sub_area' in df.columns:
        print(f"Filtering by air_basin_area: {air_basin_area}")
        # Create a filter condition for partial matches
        sub_area_filter = include_nan & df['sub_area'].isna()

        for area in air_basin_area:
            # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
            sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({area}\\)', regex=True) | (
                    df['sub_area'] == area)

        # Apply the filter
        df = df[sub_area_filter]
        print(f"After sub_area filter. Shape: {df.shape}")

    if 'temperature' in df.columns:
        print(f"Filtering by temperature: {temperature}")
        df = df[(df['temperature'] == temperature) | (include_nan & df['temperature'].isna())]
        print(f"After temperature filter. Shape: {df.shape}")

    if 'relative_humidity' in df.columns:
        print(f"Filtering by relative_humidity: {relative_humidity}")
        df = df[(df['relative_humidity'] == relative_humidity) | (include_nan & df['relative_humidity'].isna())]
        print(f"After relative_humidity filter. Shape: {df.shape}")

    # Group by MY_group and calculate statistics
    print("Filling missing values and formatting data...")
    df = df.fillna('')
    df = df.reset_index(drop=True)

    print("Formatting data with provided format function...")
    df_formatted = format_func(df)

    print("Grouping and calculating mean emission rates...")
    group_col = ['area', 'county', 'emfacId', 'model_year_group', 'vehicle_class', 'fuel', 'process', 'speed_time', 'pollutant']
    emissions_rates = df_formatted.groupby(group_col)['emission_rate'].mean().reset_index()
    print("Getting unique county/emfacId combinations...")
    df_unique = emissions_rates[["county", "emfacId"]].drop_duplicates().reset_index(drop=True)
    print(f"Found {len(df_unique)} unique county/emfacId combinations")

    # Parallel processing
    # Use fewer, larger chunks and match to number of CPU cores
    num_cores = min(os.cpu_count() or 4, 8)  # Cap at 8 to prevent excessive overhead
    chunks = np.array_split(df_unique, num_cores)
    print(f"Starting parallel processing with {num_cores} cores...")

    # Use parallel processing with fewer, larger chunks
    with Pool(num_cores) as pool:
        with tqdm(total=num_cores, desc="Processing chunks") as pbar:
            # Use imap to process chunks sequentially with progress updates
            df_output_list = []
            for result in pool.imap(process_chunk, [(chunk, emissions_rates) for chunk in chunks]):
                df_output_list.append(result)
                pbar.update(1)

    # Formatting for merge
    print("Combining results and finalizing data...")
    df_output = pd.concat(df_output_list, ignore_index=True).drop(["speed_time"], axis=1)

    # Filter out rows where all emission columns are zero
    emission_columns = [col for col in df_output.columns if col.startswith('rate_') and col.endswith('_gram_float')]

    # Count rows before filtering
    total_rows_before = len(df_output)
    df_output = df_output[~(df_output[emission_columns] == 0).all(axis=1)]
    # Count rows after filtering
    total_rows_after = len(df_output)

    print(f"Filtered out {total_rows_before - total_rows_after} rows where all emission columns are zero")
    print(f"Final dataset has {total_rows_after} rows")

    # Reorder columns to ensure 'county' is at the front
    columns = df_output.columns.tolist()
    columns = ['county'] + [col for col in columns if col != 'county']
    emfac_rates = df_output[columns]

    return emfac_rates

def process_emfac_emissions(study_area, scenario_name, work_dir, config, format_func):
    # Get file paths
    emfac_config = config["rates"]["emfac"]
    filters_config = config["rates"]["filters"]
    emfac_rates_by_model_year_file = os.path.join(work_dir, emfac_config['emfac_rates_by_model_year_file'])
    emfac_emission_rate_output_file = os.path.join(
        work_dir,
        f"{config["run"]["emissions_dir"]}/{study_area}_emfac_rates_{scenario_name}.csv"
    )

    if check_files([emfac_emission_rate_output_file], config["override_rates"]):
        emfac_rates = pd.read_csv(emfac_emission_rate_output_file)
    else:
        emfac_rates = process_emfac_rates(
            emfac_rates_by_model_year_file,
            format_func,
            filters_config['season_month'],
            filters_config['calendar_year'],
            filters_config['sub_area'],
            filters_config['temperature'],
            filters_config['relative_humidity'],
            include_nan=filters_config["include_nan"])

        print(f"Writing EMFAC emission rate to: {emfac_emission_rate_output_file}")
        emfac_rates.to_csv(emfac_emission_rate_output_file, index=False)

    return emfac_rates


def process_black_carbon(study_area, scenario_name, work_dir, config, format_func):
    # Get file paths
    black_carbon_config = config["rates"]["black_carbon"]
    filters_config = config["rates"]["filters"]
    bc_rates_by_model_year_file = os.path.join(work_dir, black_carbon_config['black_carbon_rates_file'])
    bc_emission_rate_output_file = os.path.join(
        work_dir,
        f"{config["run"]["emissions_dir"]}/{study_area}_black_carbon_rates_{scenario_name}.csv"
    )

    if check_files([bc_emission_rate_output_file], config["override_rates"]):
        bc_rates = pd.read_csv(bc_emission_rate_output_file)
    else:
        bc_rates = process_emfac_rates(
            bc_rates_by_model_year_file,
            format_func,
            filters_config['season_month'],
            filters_config['calendar_year'],
            filters_config['sub_area'],
            filters_config['temperature'],
            filters_config['relative_humidity'],
            include_nan=filters_config["include_nan"]
        )

        print(f"Writing Black Carbon emission rate to: {bc_emission_rate_output_file}")
        bc_rates.to_csv(bc_emission_rate_output_file, index=False)

    return bc_rates


def process_road_dust(study_area, scenario_name, work_dir, config, emfac_ids):
    """
    Process road dust emission rates for all EMFAC IDs.

    Args:
        study_area (str): Study area name
        scenario_name (str): Scenario name
        work_dir (str): Working directory path
        config (dict): Configuration dictionary
        emfac_ids (set): Set of EMFAC IDs to process

    Returns:
        pd.DataFrame: Road dust emission rates for all EMFAC IDs
    """
    road_dust_config = config["rates"]["road_dust"]
    filters_config = config["rates"]["filters"]

    # Get road dust file paths
    _rainy_days_file = os.path.join(work_dir, road_dust_config['rainy_days_file'])
    _silt_loading_file = os.path.join(work_dir, road_dust_config['silt_loading_file'])
    road_dust_output_file = os.path.join(
        work_dir,
        f"{config["run"]["emissions_dir"]}/{study_area}_paved_road_dust_rates_{scenario_name}.csv"
    )

    # Check if the output file already exists
    if check_files([road_dust_output_file], config["override_rates"]):
        print(f"Loading existing road dust rates from: {road_dust_output_file}")
        road_dust_rates = pd.read_csv(road_dust_output_file)
        print(f"Loaded road dust rates with {len(road_dust_rates)} rows")
    else:
        try:
            # Ensure output directory exists
            os.makedirs(os.path.dirname(road_dust_output_file), exist_ok=True)

            print(f"Processing road dust for air basins: {filters_config['sub_area']}")
            print(f"Using rainy days file: {_rainy_days_file}")
            print(f"Using silt loading file: {_silt_loading_file}")

            # Process road dust emission rates
            road_dust_rates = generate_road_dust_rates(_rainy_days_file, _silt_loading_file, filters_config['sub_area'])

            print(f"Generated base road dust rates with {len(road_dust_rates)} rows")
            print(f"Duplicating rates for {len(emfac_ids)} EMFAC IDs")

            # Create a list to hold all DataFrames
            dfs = []

            # Use tqdm to show progress of the duplication process
            for emfac_id in tqdm(emfac_ids, desc="Creating rates for each EMFAC ID"):
                temp_df = road_dust_rates.copy()
                temp_df["emfacId"] = emfac_id
                dfs.append(temp_df)

            # Concatenate all the DataFrames
            print("Concatenating all rates...")
            road_dust_rates = pd.concat(dfs, ignore_index=True)
            print(f"Final road dust rates shape: {road_dust_rates.shape}")

            # Ensure output directory exists
            os.makedirs(os.path.dirname(road_dust_output_file), exist_ok=True)

            print(f"Writing road dust emission rates to: {road_dust_output_file}")
            road_dust_rates.to_csv(road_dust_output_file, index=False)
            print("Road dust rates saved successfully")

        except Exception as e:
            print(f"Error processing road dust for scenario '{scenario_name}': {str(e)}")
            print("Returning empty DataFrame due to error")
            return pd.DataFrame()

    return road_dust_rates


def process_emissions_rates(_study_area, _scenario_name, _work_dir, config, format_func):
    """
    Process emissions rates for one or more scenarios based on the provided configuration.

    Args:
        _study_area (str): Area for which emissions rates need to be processed
        _scenario_name (str): Name of the scenario
        _work_dir (str): Working directory path
        config (dict): Configuration dictionary containing emission scenarios
        format_func:

    Returns:
        pd.DataFrame: Combined emissions rates for the scenario
    """
    # File paths for outputs
    rates_config = config["rates"]
    combined_rate_file = os.path.join(_work_dir, f"{config["run"]["output_dir"]}/{_study_area}_emissions_rates_{_scenario_name}.csv")

    # Ensure output directory exists
    os.makedirs(os.path.dirname(combined_rate_file), exist_ok=True)

    # Specify the columns you want to appear first
    first_cols = [
        "scenario", "emfacId", "county", "speed_mph_float_bins", "time_minutes_float_bins", "road_category", "process"
    ]

    if check_files([combined_rate_file], config["override_rates"]):
        print(f"Loading existing combined rates from: {combined_rate_file}")
        _combined_rates = pd.read_csv(combined_rate_file, dtype=str)
        print(f"Loaded combined rates with {len(_combined_rates)} rows")
    else:
        print(f"Starting emissions rate processing for {_study_area}, scenario: {_scenario_name}")
        dfs = []
        emfac_ids = set()

        # Track processing steps
        steps = []
        if 'emfac' in rates_config:
            steps.append('EMFAC emissions')
        if 'black_carbon' in rates_config:
            steps.append('Black Carbon emissions')
        if 'road_dust' in rates_config:
            steps.append('Road Dust emissions')

        print(f"Will process: {', '.join(steps)}")

        # Use tqdm to show progress of processing steps
        with tqdm(total=len(steps), desc="Processing emission types") as pbar:
            # Process EMFAC emissions if configured
            if 'emfac' in rates_config:
                print(f"\nProcessing EMFAC emissions for scenario '{_scenario_name}'")
                emfac_rates = process_emfac_emissions(_study_area, _scenario_name, _work_dir, config, format_func)
                pbar.update(1)
            else:
                emfac_rates = None
                print(f"Skipping EMFAC processing for scenario '{_scenario_name}' as no config is provided.")

            # Process black carbon emissions if configured
            if 'black_carbon' in rates_config:
                print(f"\nProcessing Black Carbon emissions for scenario '{_scenario_name}'")
                black_carbon_rates = process_black_carbon(_study_area, _scenario_name, _work_dir, config, format_func)
                pbar.update(1)
            else:
                black_carbon_rates = None
                print(f"Skipping Black Carbon processing for scenario '{_scenario_name}' as no config is provided.")

            if emfac_rates is not None and black_carbon_rates is not None:
                emfac_bc_keys = ["emfacId", "county", "speed_mph_float_bins", "time_minutes_float_bins", "process"]
                # Filter black_carbon_rates to keep only key columns and columns starting with "rate_bc"
                bc_cols_to_keep = [col for col in black_carbon_rates.columns if col.startswith("rate_bc")]
                bc_rates_filtered = black_carbon_rates[emfac_bc_keys + bc_cols_to_keep]
                # Filter emfac_rates to keep everything except columns starting with "rate_bc"
                emfac_cols_to_keep = [col for col in emfac_rates.columns if not col.startswith("rate_bc")]
                emfac_rates_filtered = emfac_rates[emfac_cols_to_keep]
                # Merge the filtered dataframes
                emfac_bc_rates = pd.merge(emfac_rates_filtered, bc_rates_filtered, on=emfac_bc_keys,how='outer')
                print(f"Merged EMFAC and Black Carbon rates. Shape: {emfac_bc_rates.shape}")
            elif emfac_rates is not None:
                emfac_bc_rates = emfac_rates
                print(f"Using EMFAC rates only. Shape: {emfac_rates.shape}")
            elif black_carbon_rates is not None:
                emfac_bc_rates = black_carbon_rates
                print(f"Using Black Carbon rates only. Shape: {black_carbon_rates.shape}")
            else:
                emfac_bc_rates = pd.DataFrame()  # Create empty DataFrame if both are None
                print("No emission rates available.")

            if not emfac_bc_rates.empty:
                dfs.append(emfac_bc_rates)
                emfac_ids.update(emfac_bc_rates["emfacId"].unique())
                print(f"Added {len(emfac_bc_rates)} emission rows")

            # Process road dust emissions if configured
            if 'road_dust' in rates_config:
                print(f"\nProcessing Road Dust emissions for scenario '{_scenario_name}'")
                road_dust_rates = process_road_dust(_study_area, _scenario_name, _work_dir, config, emfac_ids)
                if not road_dust_rates.empty:
                    dfs.append(road_dust_rates)
                    print(f"Added {len(road_dust_rates)} Road Dust emission rows")
                else:
                    print("No Road Dust emissions were processed")
                pbar.update(1)
            else:
                print(f"Skipping Paved Road Dust processing for scenario '{_scenario_name}' as no config is provided.")

        if not dfs:
            print(f"Warning: No emission rates available for scenario '{_scenario_name}'")
            _combined_rates = pd.DataFrame()
        else:
            print("\nCombining all emission rates...")

            # Get counts for each type of emission
            emission_counts = {
                f"Source {i + 1}": len(df) for i, df in enumerate(dfs) if df is not None
            }
            print(f"Emission source row counts: {emission_counts}")

            # Get all unique columns from all dataframes
            all_columns = set()
            for df in dfs:
                if df is not None:  # Check that df is not None
                    all_columns.update(df.columns)
            print(f"Total unique columns across all sources: {len(all_columns)}")

            # Filter out None values
            valid_dfs = [df for df in dfs if df is not None]
            print(f"Processing {len(valid_dfs)} valid dataframes")

            # Add missing columns to each dataframe
            print("Adding missing columns to each dataframe...")
            for i in tqdm(range(len(valid_dfs)), desc="Standardizing dataframes"):
                missing_cols = all_columns - set(valid_dfs[i].columns)
                for col in missing_cols:
                    valid_dfs[i][col] = None
                print(f"Added {len(missing_cols)} missing columns to dataframe {i + 1}")

            print("Concatenating all dataframes...")
            _combined_rates = pd.concat(valid_dfs, ignore_index=True)
            _combined_rates["scenario"] = _scenario_name

            # Report on final combined size
            print(f"Combined rates shape: {_combined_rates.shape}")

            remaining_cols = [col for col in _combined_rates.columns if col not in first_cols]
            _combined_rates = _combined_rates[first_cols + remaining_cols]

            print(f"Writing combined emission rates to: {combined_rate_file}")
            _combined_rates.to_csv(combined_rate_file, index=False)
            print("Combined rates saved successfully")

    return _combined_rates


def process_emfac_population(_study_area, _scenario_name, _work_dir, config, format_func):
    """
    Process EMFAC population data by model year, adding proportional calculations.

    Args:
        _study_area: Study area name
        _scenario_name: Scenario name
        _work_dir: Working directory path
        config: Configuration dictionary containing filtering and file path information
        format_func: Function to format the data

    Returns:
        pandas.DataFrame: Processed and grouped population data with proportion calculations
    """
    _emfac_population_output_file = os.path.join(
        _work_dir,
        f"{config["run"]["emissions_dir"]}/{_study_area}_emfac_population_{_scenario_name}.csv"
    )

    # Ensure output directory exists
    os.makedirs(os.path.dirname(_emfac_population_output_file), exist_ok=True)

    if check_files([_emfac_population_output_file], config["override_rates"]):
        print(f"Loading existing EMFAC population data from: {_emfac_population_output_file}")
        emfac_population = pd.read_csv(_emfac_population_output_file)
        print(f"Loaded population data with {len(emfac_population)} rows")
    else:
        print(f"Processing EMFAC population data for {_study_area}, scenario: {_scenario_name}")

        include_nan = config["rates"]["filters"]["include_nan"]
        calendar_year = config["rates"]["filters"]["calendar_year"]
        air_basin_area = config["rates"]["filters"]["sub_area"]
        _emfac_population_by_model_year_file = os.path.join(
            _work_dir,
            config["rates"]["emfac"]["emfac_pop_by_model_year_file"]
        )

        print(f"Reading population data from: {_emfac_population_by_model_year_file}")

        table = csv.read_csv(_emfac_population_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        df = table.to_pandas()
        print(f"Loaded population data with shape: {df.shape}")

        # Create a progress bar for the filtering steps
        filtering_steps = [
            "Calendar year",
            "Air basin area",
            "Convert population",
            "Clean data",
            "Format data",
            "Group data",
            "Calculate proportions"
        ]

        with tqdm(total=len(filtering_steps), desc="Processing population data") as pbar:
            # Filter by calendar year
            if 'calendar_year' in df.columns:
                print(f"Filtering by calendar year: {calendar_year}")
                before_count = len(df)
                df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]
                print(f"After filtering: {len(df)} rows (removed {before_count - len(df)} rows)")
            pbar.update(1)

            # Filter by sub area
            if 'sub_area' in df.columns:
                print(f"Filtering by air basin area: {air_basin_area}")
                before_count = len(df)
                # Create a filter condition for partial matches
                sub_area_filter = include_nan & df['sub_area'].isna()

                for _area in air_basin_area:
                    # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                    sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({_area}\\)', regex=True) | (
                            df['sub_area'] == _area)

                # Apply the filter
                df = df[sub_area_filter]
                print(f"After filtering: {len(df)} rows (removed {before_count - len(df)} rows)")
            pbar.update(1)

            # Convert population column to float for calculations
            if 'population' in df.columns:
                print("Converting population to numeric values")
                df['population'] = pd.to_numeric(df['population'], errors='coerce')
                # Check for NaN values after conversion
                nan_count = df['population'].isna().sum()
                if nan_count > 0:
                    print(f"Warning: {nan_count} rows have non-numeric population values")
            pbar.update(1)

            # Clean data
            print("Cleaning data (filling NaN values and resetting index)")
            df = df.fillna('')
            df = df.reset_index(drop=True)
            pbar.update(1)

            # Format the data
            print("Formatting data with provided format function")
            df_formatted = format_func(df)
            print(f"Formatted data shape: {df_formatted.shape}")
            pbar.update(1)

            # Group by relevant columns and sum population
            print("Grouping data and calculating total populations")
            emfac_population = df_formatted.groupby('emfacId').agg({
                'vehicle_class': 'first',
                'fuel': 'first',
                'model_year_group': 'first',
                'mappedFuel': 'first',
                'mappedClass': 'first',
                'population': 'sum'
            }).reset_index()
            print(f"After grouping: {len(emfac_population)} unique vehicle class/fuel/model year combinations")
            pbar.update(1)

            # Calculate total population across all groups
            total_population = emfac_population['population'].sum()
            print(f"Total vehicle population: {total_population:,.0f}")

            # Calculate proportion of each group relative to total
            print("Calculating population proportions")
            emfac_population['population_proportion'] = emfac_population['population'] / total_population

            # Print the top 5 vehicle categories by population
            print("Top 5 vehicle categories by population:")
            top_5 = emfac_population.sort_values('population', ascending=False).head(5)
            for _, row in top_5.iterrows():
                percent = row['population_proportion'] * 100
                print(f"  {row['vehicle_class']}, {row['fuel']}, {row['model_year_group']}: "
                      f"{row['population']:,.0f} vehicles ({percent:.2f}%)")
            pbar.update(1)

            # Save the processed data
            print(f"Writing population data to: {_emfac_population_output_file}")
            emfac_population.to_csv(_emfac_population_output_file, index=False)
            print("Population data saved successfully")

    return emfac_population


def process_emfac_vmt(_study_area, _scenario_name, _work_dir, config, format_func):
    """
    Process EMFAC VMT data by model year, adding proportional calculations.

    Args:
        _study_area: Study area name
        _scenario_name: Scenario name
        _work_dir: Working directory path
        config: Configuration dictionary containing filtering and file path information
        format_func: Function to format the data

    Returns:
        pandas.DataFrame: Processed and grouped VMT data with proportion calculations
    """
    _emfac_vmt_output_file = os.path.join(
        _work_dir,
        f"{config["run"]["emissions_dir"]}/{_study_area}_emfac_vmt_{_scenario_name}.csv"
    )

    # Ensure output directory exists
    os.makedirs(os.path.dirname(_emfac_vmt_output_file), exist_ok=True)

    if check_files([_emfac_vmt_output_file], config["override_rates"]):
        print(f"Loading existing EMFAC VMT data from: {_emfac_vmt_output_file}")
        emfac_vmt = pd.read_csv(_emfac_vmt_output_file)
        print(f"Loaded VMT data with {len(emfac_vmt)} rows")
    else:
        print(f"Processing EMFAC VMT data for {_study_area}, scenario: {_scenario_name}")

        include_nan = config["rates"]["filters"]["include_nan"]
        calendar_year = config["rates"]["filters"]["calendar_year"]
        air_basin_area = config["rates"]["filters"]["sub_area"]
        _emfac_vmt_by_model_year_file = os.path.join(
            _work_dir,
            config["rates"]["emfac"]["emfac_vmt_by_model_year_file"]
        )

        print(f"Reading VMT data from: {_emfac_vmt_by_model_year_file}")

        table = csv.read_csv(_emfac_vmt_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        df = table.to_pandas()
        print(f"Loaded VMT data with shape: {df.shape}")

        # Create a progress bar for the filtering steps
        filtering_steps = [
            "Calendar year",
            "Air basin area",
            "Convert numeric columns",
            "Format data",
            "Clean data",
            "Group data",
            "Calculate proportions"
        ]

        with tqdm(total=len(filtering_steps), desc="Processing VMT data") as pbar:
            # Filter by calendar year
            if 'calendar_year' in df.columns:
                print(f"Filtering by calendar year: {calendar_year}")
                before_count = len(df)
                df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]
                print(f"After filtering: {len(df)} rows (removed {before_count - len(df)} rows)")
            pbar.update(1)

            # Filter by sub area
            if 'sub_area' in df.columns:
                print(f"Filtering by air basin area: {air_basin_area}")
                before_count = len(df)
                # Create a filter condition for partial matches
                sub_area_filter = include_nan & df['sub_area'].isna()

                for _area in air_basin_area:
                    # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                    sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({_area}\\)', regex=True) | (
                            df['sub_area'] == _area)

                # Apply the filter
                df = df[sub_area_filter]
                print(f"After filtering: {len(df)} rows (removed {before_count - len(df)} rows)")
            pbar.update(1)

            # Convert numeric columns to float for calculations
            numeric_columns = ['total_vmt', 'cvmt', 'evmt']
            print("Converting numeric columns to float")
            for col in numeric_columns:
                if col in df.columns:
                    df[col] = pd.to_numeric(df[col], errors='coerce')
                    # Check for NaN values after conversion
                    nan_count = df[col].isna().sum()
                    if nan_count > 0:
                        print(f"Warning: {nan_count} rows have non-numeric {col} values")
            pbar.update(1)

            # Format the data
            print("Formatting data with provided format function")
            df_formatted = format_func(df)
            print(f"Formatted data shape: {df_formatted.shape}")
            pbar.update(1)

            # Clean data
            print("Cleaning data (filling NaN values and resetting index)")
            df_formatted = df_formatted.fillna('').reset_index(drop=True)
            pbar.update(1)

            # Group by relevant columns and sum VMT
            print("Grouping data and calculating total VMT")
            emfac_vmt = df_formatted.groupby('emfacId').agg({
                'vehicle_class': 'first',
                'fuel': 'first',
                'model_year_group': 'first',
                'mappedFuel': 'first',
                'mappedClass': 'first',
                'total_vmt': 'sum'
            }).reset_index()
            print(f"After grouping: {len(emfac_vmt)} unique vehicle class/fuel/model year combinations")
            pbar.update(1)

            # Calculate total VMT across all groups
            total_vmt = emfac_vmt['total_vmt'].sum()
            print(f"Total VMT: {total_vmt:,.0f}")

            # Calculate proportion of each group relative to total
            print("Calculating VMT proportions")
            emfac_vmt['vmt_proportion'] = emfac_vmt['total_vmt'] / total_vmt

            # Print the top 5 vehicle categories by VMT
            print("Top 5 vehicle categories by VMT:")
            top_5 = emfac_vmt.sort_values('total_vmt', ascending=False).head(5)
            for _, row in top_5.iterrows():
                percent = row['vmt_proportion'] * 100
                print(f"  {row['vehicle_class']}, {row['fuel']}, {row['model_year_group']}: "
                      f"{row['total_vmt']:,.0f} VMT ({percent:.2f}%)")
            pbar.update(1)

            # Save the processed data
            print(f"Writing VMT data to: {_emfac_vmt_output_file}")
            emfac_vmt.to_csv(_emfac_vmt_output_file, index=False)
            print("VMT data saved successfully")

    return emfac_vmt