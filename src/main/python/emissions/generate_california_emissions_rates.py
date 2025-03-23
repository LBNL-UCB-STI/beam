import os
import sys

import pandas as pd
import pyarrow as pa
import pyarrow.csv as csv
from joblib import Parallel, delayed

from _emfac_emissions_mapping import sanitize_name

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import

emissions_processes = [
    "RUNEX",
    "IDLEX",
    "STREX",
    "DIURN",
    "HOTSOAK",
    "RUNLOSS",
    "PMTW",
    "PMBW",
    "PRDUST"
]

pollutant_columns = {
    'CH4': 'rate_ch4_gram_float',
    'CO': 'rate_co_gram_float',
    'CO2': 'rate_co2_gram_float',
    'HC': 'rate_hc_gram_float',
    'NH3': 'rate_nh3_gram_float',
    'NOx': 'rate_nox_gram_float',
    'PM': 'rate_pm_gram_float',
    'PM10': 'rate_pm10_gram_float',
    'PM2_5': 'rate_pm2_5_gram_float',
    'ROG': 'rate_rog_gram_float',
    'SOx': 'rate_sox_gram_float',
    'TOG': 'rate_tog_gram_float',
    'BC_V1': 'rate_bc_gram_float',
    'BC_V2': 'rate_bcm_gram_float',
    'BC_V3': 'rate_bch_gram_float'
}

def categorize_model_year(year):
    # https://pubs.acs.org/doi/full/10.1021/acs.est.9b04763
    if year <= 1993:
        return '1993'
    elif year <= 2006:
        return '2006'
    else:  # year >= 2007
        return '2018'

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
    output_file (str, optional): Path to save the output CSV file

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

    # # Add additional columns at the end for reference/debugging
    # extended_cols = column_order + ['carb_road_category', 'silt_loading', 'rainy_days']
    # emissions_extended_df = _emissions_df[extended_cols]

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
    pivot_df = pivot_df.rename(columns=pollutant_columns)
    # Add missing columns with default values
    for col in pollutant_columns.values():
        if col not in pivot_df.columns:
            pivot_df[col] = 0.0
    pivot_df.insert(0, 'speed_mph_float_bins', "")
    pivot_df.insert(1, 'time_minutes_float_bins', "")
    return pivot_df

def process_rates_group(df, row):
    mask = ((df["county"] == row["county"]) & (df["emfacId"] == row["emfacId"]))
    df_subset = df[mask]
    df_output_list = []
    for process in emissions_processes:
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
            df_output_list.append(df_temp)

    return pd.concat(df_output_list, ignore_index=True)

def process_emfac_rates(
        emfac_rates_by_model_year_file,
        emfac_class_map,
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
        emfac_class_map: emfac class map to consider
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
    if not os.path.exists(emfac_rates_by_model_year_file):
        emfac_rates = pd.DataFrame({})
        print(
            f"Error: Emissions rates file '{emfac_rates_by_model_year_file}' not found.")
    else:
        table = csv.read_csv(emfac_rates_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
        df = table.to_pandas()

        # Apply filters based on config
        if 'season_month' in df.columns:
            df = df[(df['season_month'] == season_month) | (include_nan & df['season_month'].isna())]

        if 'calendar_year' in df.columns:
            df = df[(df['calendar_year'] == calendar_year) | (include_nan & df['calendar_year'].isna())]

        # Improved air basin area filtering to handle partial matches
        if 'sub_area' in df.columns:
            # Create a filter condition for partial matches
            sub_area_filter = include_nan & df['sub_area'].isna()

            for area in air_basin_area:
                # Look for exact match or area in parentheses (e.g., "Santa Clara (SF)" for "SF")
                sub_area_filter = sub_area_filter | df['sub_area'].str.contains(f'\\({area}\\)', regex=True) | (
                            df['sub_area'] == area)

            # Apply the filter
            df = df[sub_area_filter]

        if 'temperature' in df.columns:
            df = df[(df['temperature'] == temperature) | (include_nan & df['temperature'].isna())]

        if 'relative_humidity' in df.columns:
            df = df[(df['relative_humidity'] == relative_humidity) | (include_nan & df['relative_humidity'].isna())]

        df['model_year_group'] = df['model_year'].apply(categorize_model_year)
        # Group by MY_group and calculate statistics
        df = df.fillna('')
        df = df.reset_index(drop=True)
        group_col = ['sub_area', 'vehicle_class', 'fuel', 'process', 'speed_time', 'pollutant', 'model_year_group']
        df_grouped = df.groupby(group_col)['emission_rate'].mean().reset_index()
        # Extract county and area from sub_area
        df_grouped['beamClass'] = df_grouped['vehicle_class'].map(emfac_class_map)
        df_grouped.dropna(subset=['beamClass'], inplace=True)
        df_grouped[['county', 'area']] = df_grouped['sub_area'].str.extract(r'^([^()]+)\s*\(([^)]+)\)')
        # Clean up the extracted data
        df_grouped['county'] = df_grouped['county'].str.strip().str.lower()
        df_grouped['area'] = df_grouped['area'].str.strip()
        df_grouped.drop(['sub_area'], axis=1, inplace=True)
        # Create emfacId
        df_grouped['emfacId'] = df_grouped.apply(
            lambda row: sanitize_name(f"{row['model_year_group']}-{row['vehicle_class']}-{row['fuel']}"),
            axis=1
        )
        emissions_rates = df_grouped.rename(columns={'vehicle_class': 'emfacClass', 'fuel': 'emfacFuel'})
        df_unique = emissions_rates[["county", "emfacId"]].drop_duplicates().reset_index(drop=True)

        # Parallel processing
        df_output_list = Parallel(n_jobs=-1)(
            delayed(process_rates_group)(emissions_rates, row) for index, row in df_unique.iterrows()
        )

        # Formatting for merge
        df_output = pd.concat(df_output_list, ignore_index=True).drop(["speed_time"], axis=1)

        # Filter out rows where all emission columns are zero
        emission_columns = [col for col in df_output.columns if col.startswith('rate_') and col.endswith('_gram_float')]
        filtered_out = df_output[(df_output[emission_columns] == 0).all(axis=1)]
        df_output = df_output[~(df_output[emission_columns] == 0).all(axis=1)]

        filtered_out.to_csv("filtered_out.csv")

        # Reorder columns to ensure 'county' is at the front
        columns = df_output.columns.tolist()
        columns = ['county'] + [col for col in columns if col != 'county']
        emfac_rates = df_output[columns]

    return emfac_rates


def process_emfac_emissions(study_area, scenario_name, config, work_dir, emfac_class_map):
    # Get file paths
    emfac_config = config["emfac"]
    filters_config = config["filters"]
    emfac_rates_by_model_year_file = os.path.join(
        work_dir,
        emfac_config['emfac_rates_by_model_year_file']
    )
    emfac_emission_rate_output_file = os.path.join(
        work_dir,
        f"emissions/emfac/{study_area}_emfac_rates_{scenario_name}.csv"
    )

    if os.path.exists(emfac_emission_rate_output_file):
        emfac_rates = pd.read_csv(emfac_emission_rate_output_file)
    else:
        emfac_rates = process_emfac_rates(
            emfac_rates_by_model_year_file,
            emfac_class_map,
            filters_config['season_month'],
            filters_config['calendar_year'],
            filters_config['sub_area'],
            filters_config['temperature'],
            filters_config['relative_humidity'],
            include_nan=filters_config["include_nan"])

        print(f"Writing EMFAC emission rate to: {emfac_emission_rate_output_file}")
        emfac_rates.to_csv(emfac_emission_rate_output_file, index=False)

    return emfac_rates


def process_black_carbon(study_area, scenario_name, config, work_dir, emfac_class_map):
    # Get file paths
    black_carbon_config = config["black_carbon"]
    filters_config = config["filters"]
    bc_rates_by_model_year_file = os.path.join(
        work_dir,
        black_carbon_config['black_carbon_rates_file']
    )
    bc_emission_rate_output_file = os.path.join(
        work_dir,
        f"emissions/black_carbon/{study_area}_black_carbon_rates_{scenario_name}.csv"
    )

    if os.path.exists(bc_emission_rate_output_file):
        bc_rates = pd.read_csv(bc_emission_rate_output_file)
    else:
        bc_rates = process_emfac_rates(
            bc_rates_by_model_year_file,
            emfac_class_map,
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


def process_road_dust(study_area, scenario_name, config, work_dir, emfacIds):
    road_dust_config = config["road_dust"]
    filters_config = config["filters"]
    # Get road dust file paths
    _rainy_days_file = os.path.join(work_dir, road_dust_config['rainy_days_file'])
    _silt_loading_file = os.path.join(work_dir, road_dust_config['silt_loading_file'])
    road_dust_output_file = os.path.join(
        work_dir,
        f"emissions/road_dust/{study_area}_paved_road_dust_rates_{scenario_name}.csv"
    )

    # Create appropriate air basin region from the emfac config if available
    if os.path.exists(road_dust_output_file):
        road_dust_rates = pd.read_csv(road_dust_output_file)
        print(f"Skipping road dust processing for {road_dust_output_file} as it already exists.")
    else:
        try:
            # Process road dust emission rates
            road_dust_rates = generate_road_dust_rates(_rainy_days_file, _silt_loading_file, filters_config['sub_area'])
        except Exception as e:
            road_dust_rates = None
            print(f"Error processing road dust for scenario '{scenario_name}': {str(e)}")

        dfs = []
        for emfac_id in emfacIds:
            temp_df = road_dust_rates.copy()
            temp_df["emfacId"] = emfac_id
            dfs.append(temp_df)

        road_dust_rates = pd.concat(dfs, ignore_index=True)
        print(f"Writing CARB is Paved Road Dust emission rate to: {road_dust_output_file}")
        road_dust_rates.to_csv(road_dust_output_file, index=False)

    return road_dust_rates


def process_emissions_rates(study_area, scenario_name, config, work_dir, emfac_class_map):
    """
    Process emissions rates for one or more scenarios based on the provided configuration.

    Args:
        study_area (str): Area for which emissions rates need to be processed
        config (dict): Configuration dictionary containing emission scenarios

    Returns:
        dict: Dictionary of processed emissions rates for each scenario
    """
    # File paths for outputs
    combined_rate_file = os.path.join(work_dir, f"{study_area}_emissions_rates_{scenario_name}.csv")

    if os.path.exists(combined_rate_file):
        _combined_rates = pd.read_csv(combined_rate_file)
    else:
        dfs = []
        emfacIds = set()
        # Process EMFAC emissions if configured
        if 'emfac' in config:
            print(f"Processing emfac emissions for scenario '{scenario_name}'")
            emfac_rates = process_emfac_emissions(study_area, scenario_name, config, work_dir, emfac_class_map)
            dfs.append(emfac_rates)
            emfacIds.update(emfac_rates["emfacId"].unique())
        else:
            print(f"Skipping EMFAC processing for scenario '{scenario_name}' as no config is provided.")

        # Process black carbon emissions if configured
        if 'black_carbon' in config:
            print(f"Processing black carbon emissions for scenario '{scenario_name}'")
            black_carbon_rates = process_black_carbon(study_area, scenario_name, config, work_dir, emfac_class_map)
            dfs.append(black_carbon_rates)
            emfacIds.update(black_carbon_rates["emfacId"].unique())
        else:
            print(f"Skipping Black Carbon processing for scenario '{scenario_name}' as no config is provided.")

        # Process road dust emissions if configured
        if 'road_dust' in config:
            print(f"Processing road dust emissions for scenario '{scenario_name}'")
            road_dust_rates = process_road_dust(study_area, scenario_name, config, work_dir, emfacIds)
            dfs.append(road_dust_rates)
        else:
            print(f"Skipping Paved Road Dust processing for scenario '{scenario_name}' as no config is provided.")

        if not dfs or len(dfs) < 3:
            print(f"Warning: No emission rates available for scenario '{scenario_name}'")
            _combined_rates = pd.DataFrame()
        else:
            # Get all unique columns from all dataframes
            all_columns = set()
            for df in dfs:
                if df is not None:  # Check that df is not None
                    all_columns.update(df.columns)

            # Filter out None values
            valid_dfs = [df for df in dfs if df is not None]

            # Add missing columns to each dataframe
            for i in range(len(valid_dfs)):
                for col in all_columns:
                    if col not in valid_dfs[i].columns:
                        valid_dfs[i][col] = None

            _combined_rates = pd.concat(valid_dfs, ignore_index=True)
            _combined_rates["scenario"] = scenario_name
            # Specify the columns you want to appear first
            first_cols = [
                "scenario", "emfacId", "county", "speed_mph_float_bins", "time_minutes_float_bins", "road_category",
                "process"
            ]
            remaining_cols = [col for col in _combined_rates.columns if col not in first_cols]
            _combined_rates = _combined_rates[first_cols + remaining_cols]
            _combined_rates.to_csv(combined_rate_file, index=False)

    return _combined_rates
