import gzip
import io
import math
import os
import re
import shutil
import time
import warnings

import geopandas as gpd
import h3
import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.csv as pv
from pyproj import Transformer
from shapely.geometry import LineString, Polygon
from tqdm import tqdm
from tqdm.auto import tqdm

warnings.filterwarnings("ignore", category=FutureWarning,
                        message="The default dtype for empty Series will be 'object' instead of 'float64' in a future version. Specify a dtype explicitly to silence this warning.")

class_2b3 = 'Class 2b&3 Vocational'
class_46 = 'Class 4-6 Vocational'
class_78_v = 'Class 7&8 Vocational'
class_78_t = 'Class 7&8 Tractor'
class_car = "Car"  # these include light and medium duty trucks
class_bike = "Bike"
class_mdp = "MediumDutyPassenger"
not_matched = "Not Matched"

class_to_category = {
    class_2b3: 'Class2b3Vocational',
    class_46: 'Class456Vocational',
    class_78_v: 'Class78Vocational',
    class_78_t: 'Class78Tractor'
}

fuel_emfac2beam_map = {
    'Dsl': 'diesel',
    'Gas': 'gasoline',
    'NG': 'naturalgas',
    'Elec': 'electricity',
    'Phe': 'pluginhybridelectricity',
    'H2fc': 'hydrogen',
    'BioDsl': 'biodiesel'
}

fuel_beam2emfac_map = {
    'diesel': 'Dsl',
    'gasoline': 'Gas',
    'naturalgas': 'NG',
    'electricity': 'Elec',
    'pluginhybridelectricity': 'Phe',
    'hydrogen': 'H2fc',
    "biodiesel": 'BioDsl'
}

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
    'TOG': 'rate_tog_gram_float'
}

emissions_processes = ["RUNEX", "IDLEX", "STREX", "DIURN", "HOTSOAK", "RUNLOSS", "PMTW", "PMBW"]

region_to_emfac_area = {
    "sfbay": "SF"
}

skims_schema = pa.schema([
    ('hour', pa.int64()),
    ('linkId', pa.int64()),
    ('tazId', pa.string()),
    ('vehicleTypeId', pa.string()),
    ('emissionsProcess', pa.string()),
    ('speedInMps', pa.float64()),
    ('energyInJoule', pa.float64()),
    ('observations', pa.int64()),
    ('iterations', pa.int64()),
    ('CH4', pa.float64()),
    ('CO', pa.float64()),
    ('CO2', pa.float64()),
    ('HC', pa.float64()),
    ('NH3', pa.float64()),
    ('NOx', pa.float64()),
    ('PM', pa.float64()),
    ('PM10', pa.float64()),
    ('PM2_5', pa.float64()),
    ('ROG', pa.float64()),
    ('SOx', pa.float64()),
    ('TOG', pa.float64())
])


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


def get_vehicle_class_from_freight(vehicle_type):
    if 'md' in vehicle_type:
        return class_46
    elif 'hdt' in vehicle_type:
        return class_78_v
    elif 'hdv' in vehicle_type:
        return class_78_t
    else:
        return None





def unpacking_ft_vehicle_population_mesozones(carriers, mesozones_to_county_file, mesozones_lookup_file):
    import pygris
    # ### Mapping counties with Mesozones ###
    if not os.path.exists(mesozones_to_county_file):
        county_data = pygris.counties(state='06', year=2018, cb=True, cache=True)
        cbg_data = pygris.block_groups(state='06', year=2018, cb=True, cache=True)
        county_data_clipped = county_data[['COUNTYFP', 'NAME']]
        cbg_data_clipped = cbg_data[['GEOID', 'COUNTYFP']]
        cbg_to_county = pd.merge(cbg_data_clipped, county_data_clipped, on="COUNTYFP", how='left')
        mesozones_lookup = pd.read_csv(mesozones_lookup_file, dtype=str)
        mesozones_lookup_clipped = mesozones_lookup[['MESOZONE', 'GEOID']]
        mesozones_to_county = pd.merge(mesozones_lookup_clipped, cbg_to_county, on='GEOID', how='left')
        mesozones_to_county.to_csv(mesozones_to_county_file, index=False)
    else:
        mesozones_to_county = pd.read_csv(mesozones_to_county_file, dtype=str)

    # TODO For future improvement find a way to map outside study area mesozones. It's a significant effort because
    # TODO need to also restructure EMFAC in such a way vehicle population from outside study area well represented
    if not mesozones_to_county[mesozones_to_county["NAME"].isna()].empty:
        print("Mesozones outside study area do not have a proper GEOID and were not mapped.")
    mesozones_to_county_studyarea = mesozones_to_county[mesozones_to_county["NAME"].notna()][["MESOZONE", "NAME"]]

    # ### Mapping freight carriers with counties, payload and vehicle types ###
    carriers_by_zone = pd.merge(carriers, mesozones_to_county_studyarea, left_on='warehouseZone',
                                right_on='MESOZONE', how='left')
    if not carriers_by_zone[carriers_by_zone['NAME'].isna()].empty:
        print(
            "Something went wrong with the mapping of freight carrier zones with mesozones. Here the non mapped ones:")
        print(carriers_by_zone[carriers_by_zone['NAME'].isna()])
    carriers_by_zone = carriers_by_zone[['tourId', 'vehicleId', 'vehicleTypeId', 'NAME']].rename(
        columns={'NAME': 'zone'})

    return carriers_by_zone


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


def process_road_dust(rainy_days_file, silt_loading_file, air_basin_region, output_file=None):
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
    emissions_df = pd.DataFrame(all_rows)

    # Reorder columns to match required format
    column_order = [
        'county',
        'road_category',
        'process',
        'rate_pm_gram_float',
        'rate_pm10_gram_float',
        'rate_pm2_5_gram_float'
    ]

    # Add additional columns at the end for reference/debugging
    extended_cols = column_order + ['carb_road_category', 'silt_loading', 'rainy_days']
    emissions_df = emissions_df[extended_cols]

    # Save to CSV if output file is specified
    if output_file:
        # Create a version with just the required columns
        emissions_df[column_order].to_csv(output_file, index=False)
        print(f"Road dust emission rates saved to {output_file}")

        # Also save an extended version with additional info
        extended_output = output_file.replace('.csv', '_extended.csv')
        emissions_df.to_csv(extended_output, index=False)
        print(f"Extended road dust emission rates saved to {extended_output}")

    return emissions_df


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




def process_single_vehicle_type(veh_type, emissions_rates, rates_prefix_filepath):
    veh_type_id = veh_type['vehicleTypeId']
    emfac_id = veh_type['emfacId']

    # Filter taz_emissions_rates for the current vehicle type
    veh_emissions = emissions_rates[emissions_rates['emfacId'] == emfac_id].copy()

    if not veh_emissions.empty:
        # Remove the emfacId column as it's no longer needed
        veh_emissions = veh_emissions.drop('emfacId', axis=1)

        # Generate the file name
        file_path = f"{rates_prefix_filepath}{veh_type_id}.csv"

        print("Writing " + file_path)
        # Save the emissions rates to a CSV file
        veh_emissions.to_csv(file_path, index=False)

        return veh_type_id
    else:
        print(f"Warning: No emissions data found for vehicle type {veh_type_id}")
        return veh_type_id










def combine_csv_files(input_files, output_file):
    # Read and combine CSV files vertically
    combined_df = pd.concat([pd.read_csv(f) for f in input_files], ignore_index=True)

    # Write the combined dataframe to a new CSV file
    combined_df.to_csv(output_file, index=False)

    print(f"Combined CSV file has been created: {output_file}")
    return combined_df  # Return the dataframe for further processing if needed


def ensure_empty_directory(directory_path):
    """
    Ensure an empty directory exists at the given path.
    If it exists, delete it and its contents, then recreate it.
    If it doesn't exist, create it.
    """
    directory_path = os.path.abspath(directory_path)

    if os.path.exists(directory_path):
        try:
            shutil.rmtree(directory_path)
            print(f"Existing directory removed: {directory_path}")
        except Exception as e:
            print(f"Error removing directory {directory_path}: {e}")
            return False

    try:
        os.makedirs(directory_path)
        print(f"Directory created: {directory_path}")
        return True
    except Exception as e:
        print(f"Error creating directory {directory_path}: {e}")
        return False


def calculate_truck_ownership_probability(income):
    """
    Calculate the probability of truck ownership based on household income.

    :param income: Household income in thousands of dollars per year
    :return: Probability of truck ownership (0 to 1)
    """
    k = 0.1  # Steepness parameter
    x0 = 80  # Income at which probability is 0.5

    # Calculate probability using logistic function
    probability = 1 / (1 + math.exp(-k * (income - x0)))

    return probability


def parse_probability_string(prob_string):
    result = {}
    parts = prob_string.split(';')
    for part in parts:
        try:
            key, value = part.strip().split(':')
            if 'ridehail' in key:
                result['ridehail_prob_all'] = float(value)
            elif 'income' in key:
                income_range = key.split('|')[1].strip()
                result[f'private_prob_{income_range}'] = float(value)
        except ValueError:
            # If the part doesn't have the expected structure, skip it
            continue
    return result


def update_sample_probability_string(row):
    groups = row['sampleProbabilityString'].replace(' ', '').lower().split(';')
    updated_groups = []

    for group in groups:
        if '|' not in group:
            updated_groups.append(group)
            continue

        group_key, values = group.split('|')
        key_probs = [kp.split(':') for kp in values.split(',')]

        if group_key == 'ridehail':
            # Update 'all' probability with population_share
            key_probs = [(k, str(float(p) * row['population_share']) if k == 'all' else p) for k, p in key_probs]
        elif group_key == 'income':
            # Update income category probability with population_share
            key_probs = [(k, str(float(p) * row['population_share'])) for k, p in key_probs]

        updated_values = ','.join([f"{k}:{p}" for k, p in key_probs])
        updated_groups.append(f"{group_key}|{updated_values}")

    return '; '.join(updated_groups)


def load_network(network_file, source_epsg):
    # Read and process network file
    network = pd.read_csv(network_file)
    transformer = Transformer.from_crs(source_epsg, "EPSG:4326", always_xy=True)

    # Vectorized coordinate conversion
    network[['fromLocationX', 'fromLocationY']] = network.apply(
        lambda row: pd.Series(transformer.transform(row['fromLocationX'], row['fromLocationY'])),
        axis=1, result_type='expand'
    )
    network[['toLocationX', 'toLocationY']] = network.apply(
        lambda row: pd.Series(transformer.transform(row['toLocationX'], row['toLocationY'])),
        axis=1, result_type='expand'
    )

    return network[['linkId', 'linkLength', 'fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']]


def read_skims_emissions(skims_file, vehicleTypes_file, vehicleTypeId_filter, network, expansion_factor, scenario_name):
    start_time = time.time()
    # Read and filter the skims file using PyArrow
    table = pv.read_csv(skims_file,
                        read_options=pv.ReadOptions(use_threads=True),
                        parse_options=pv.ParseOptions(delimiter=','),
                        convert_options=pv.ConvertOptions(column_types=skims_schema))

    filtered_table = table.filter(pc.match_substring(table['vehicleTypeId'], pattern=vehicleTypeId_filter))

    # Perform calculations in PyArrow
    annual_expansion = filtered_table['observations'] * expansion_factor * 365

    for pollutant in pollutant_columns.keys():
        filtered_table = filtered_table.append_column(
            f'{pollutant}_annual',
            pc.multiply(pc.divide(filtered_table[pollutant], pc.cast(pa.scalar(1e6), pa.float64())), annual_expansion)
        )

    filtered_table = filtered_table.append_column(
        'annualHourlyEnergyGwh',
        pc.multiply(pc.divide(filtered_table['energyInJoule'], pc.cast(pa.scalar(3.6e12), pa.float64())),
                    annual_expansion)
    )

    filtered_table = filtered_table.append_column(
        'annualHourlySpeedMph',
        pc.divide(filtered_table['speedInMps'], pc.cast(pa.scalar(2.237), pa.float64()))
    )

    # Convert to pandas
    df = filtered_table.to_pandas()

    # Process vehicleTypes file
    vehicleTypes = pd.read_csv(vehicleTypes_file)
    vehicleTypes['fuel'] = vehicleTypes['emfacId'].str.split('-').str[-1]
    vehicleTypes['class'] = vehicleTypes['vehicleClass'].str.replace('Vocational|Tractor', '', regex=True).str.strip()

    # Merge with vehicleTypes and network
    df = (df.merge(vehicleTypes[['vehicleTypeId', 'class', 'fuel']], on='vehicleTypeId', how='left')
          .merge(network[['linkId', 'linkLength']], on='linkId', how='left'))

    # Calculate annualHourlyMVMT
    df['annualHourlyMVMT'] = (df['linkLength'] * 6.21371192e-13) * annual_expansion

    # Rename column
    df.rename(columns={'emissionsProcess': 'process'}, inplace=True)

    # Melt the dataframe
    id_vars = ['hour', 'linkId', 'tazId', 'class', 'fuel', 'process', 'annualHourlySpeedMph', 'annualHourlyEnergyGwh',
               'annualHourlyMVMT']
    value_vars = [f'{pollutant}_annual' for pollutant in pollutant_columns.keys()]
    melted = df.melt(id_vars=id_vars, value_vars=value_vars, var_name='pollutant', value_name='rate')
    melted['pollutant'] = melted['pollutant'].str.replace('_annual', '')
    melted['scenario'] = scenario_name

    end_time = time.time()
    print(f"Time taken to read the file: {end_time - start_time:.2f} seconds to read file {skims_file}")

    return melted


def read_skims_emissions_chunked(skims_file, vehicleTypes_file, vehicleTypeId_filter, network, expansion_factor,
                                 scenario_name, chunk_size=1000000):
    start_time = time.time()

    # Process vehicleTypes file
    vehicleTypes = pd.read_csv(vehicleTypes_file)
    vehicleTypes['emfacFuel'] = vehicleTypes['emfacId'].str.split('-').str[-1]
    vehicleTypes['class'] = vehicleTypes['vehicleCategory'].str.replace('Vocational|Tractor', '', regex=True).str.strip()
    vehicleTypes['beamFuel'] = np.where(
        (vehicleTypes['primaryFuelType'].str.lower() == fuel_emfac2beam_map["Elec"]) & vehicleTypes[
            'secondaryFuelType'].notna(),
        'Phe',
        vehicleTypes['primaryFuelType'].str.lower().map(fuel_beam2emfac_map)
    )

    # Initialize an empty list to store the processed chunks
    result_chunks = []

    # Set up the CSV reader with chunking
    csv_reader = pv.open_csv(
        skims_file,
        read_options=pv.ReadOptions(block_size=chunk_size, use_threads=True),
        parse_options=pv.ParseOptions(delimiter=','),
        convert_options=pv.ConvertOptions(column_types=skims_schema)
    )

    # Get total file size for progress bar
    total_size = os.path.getsize(skims_file)

    # Initialize progress bar
    pbar = tqdm(total=total_size, unit='B', unit_scale=True, desc="Processing chunks",
                position=0, leave=True, mininterval=1.0, maxinterval=10.0, miniters=1)

    # Process the skims file in chunks
    for chunk in csv_reader:
        chunk_size = chunk.nbytes

        # Filter the chunk
        mask = pc.match_substring(chunk['vehicleTypeId'], pattern=vehicleTypeId_filter)
        filtered_chunk = chunk.filter(mask)
        # del chunk  # Explicitly remove reference to the original chunk

        # Perform calculations in PyArrow
        observations_expansion = pc.multiply(
            filtered_chunk['observations'], pc.cast(pa.scalar(expansion_factor), pa.float64())
        )

        new_columns = []
        new_fields = []
        for pollutant in pollutant_columns.keys():
            new_fields.append(pa.field(f'scaled_{pollutant}', pa.float64(), True))
            new_columns.append(pc.multiply(
                pc.divide(
                    filtered_chunk[pollutant], pc.cast(pa.scalar(1e6), pa.float64())
                ),
                observations_expansion
            ))

        new_fields.append(pa.field('kwh', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(
                    filtered_chunk['energyInJoule'], pc.cast(pa.scalar(3.6e6), pa.float64())
                ),
                observations_expansion
            )
        )

        new_fields.append(pa.field('vht', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(
                    filtered_chunk['travelTimeInSecond'], pc.cast(pa.scalar(3.6e3), pa.float64())
                ),
                observations_expansion
            )
        )

        # Create a new RecordBatch with additional columns
        # new_schema = filtered_chunk.schema.append(new_columns[::2])
        # new_columns = filtered_chunk.columns + new_columns[1::2]
        new_schema = filtered_chunk.schema
        for field in new_fields:
            new_schema = new_schema.append(field)

        new_columns = filtered_chunk.columns + new_columns
        filtered_chunk = pa.RecordBatch.from_arrays(new_columns, schema=new_schema)

        # Convert to pandas
        df_chunk = filtered_chunk.to_pandas()
        # del filtered_chunk

        # Merge with vehicleTypes and network
        df_chunk_merged = (
            df_chunk
            .merge(vehicleTypes[['vehicleTypeId', 'class', 'beamFuel', 'emfacFuel', 'emfacId']], on='vehicleTypeId', how='left')
            .merge(network[['linkId', 'linkLength']], on='linkId', how='left')
        )
        # del df_chunk

        # Calculate annualHourlyMVMT
        df_chunk_merged['vmt'] = (df_chunk_merged['linkLength'] * 6.21371192e-4) * observations_expansion

        # Rename column
        df_chunk_merged.rename(columns={'emissionsProcess': 'process'}, inplace=True)

        # Melt the dataframe
        id_vars = ['hour', 'linkId', 'tazId', 'emfacId', 'class', 'beamFuel', 'emfacFuel', 'process', 'kwh', 'vmt', 'vht']
        value_vars = [f'scaled_{pollutant}' for pollutant in pollutant_columns.keys()]
        melted_chunk = df_chunk_merged.melt(
            id_vars=id_vars,
            value_vars=value_vars,
            var_name='pollutant',
            value_name='rate'
        )
        # del df_chunk_merged
        melted_chunk['pollutant'] = melted_chunk['pollutant'].str.replace('scaled_', '')
        melted_chunk['scenario'] = scenario_name

        result_chunks.append(melted_chunk)

        # Update progress bar
        pbar.update(chunk_size)

    # Close progress bar
    pbar.close()

    # Combine all processed chunks
    melted = pd.concat(result_chunks, ignore_index=True)

    end_time = time.time()
    print(f"Time taken to read the file: {end_time - start_time:.2f} seconds to read file {skims_file}")

    return melted


def generate_h3_intersections(network_df, resolution, output_dir):
    print(f"Initial network_df shape: {network_df.shape}")

    # Remove rows with NaN values in coordinate columns
    coord_columns = ['fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']
    network_clean = network_df.dropna(subset=coord_columns)
    print(f"Clean network_df shape: {network_clean.shape}")

    # Create bounding box
    lats = network_clean[['fromLocationY', 'toLocationY']].values.flatten()
    lons = network_clean[['fromLocationX', 'toLocationX']].values.flatten()
    bbox = [[
        [min(lats), min(lons)],
        [min(lats), max(lons)],
        [max(lats), max(lons)],
        [max(lats), min(lons)],
        [min(lats), min(lons)]  # Close the polygon
    ]]

    # Generate H3 cells
    h3_cells = list(h3.polyfill({'type': 'Polygon', 'coordinates': bbox}, resolution))
    print(f"Number of H3 cells: {len(h3_cells)}")

    if len(h3_cells) == 0:
        print("No H3 cells created. Check your bounding box and resolution.")
        return pd.DataFrame()

    # Create GeoDataFrame of H3 cells
    h3_gdf = gpd.GeoDataFrame(
        {'h3_cell': h3_cells},
        geometry=[Polygon(h3.h3_to_geo_boundary(h, geo_json=True)) for h in h3_cells],
        crs="EPSG:4326"
    )

    # Create network GeoDataFrame
    def create_linestring(row):
        return LineString([(row['fromLocationX'], row['fromLocationY']),
                           (row['toLocationX'], row['toLocationY'])])

    network_gdf = gpd.GeoDataFrame(
        network_clean,
        geometry=network_clean.apply(create_linestring, axis=1),
        crs="EPSG:4326"
    )

    # Spatial join
    joined = gpd.sjoin(h3_gdf, network_gdf, how="inner", predicate="intersects")
    print(f"Joined DataFrame shape after spatial join: {joined.shape}")

    if joined.empty:
        print("No intersections found between H3 cells and network geometries.")
        return pd.DataFrame()

    # Calculate intersections and lengths
    def calculate_intersection(row):
        try:
            h3_poly = Polygon(h3.h3_to_geo_boundary(row['h3_cell'], geo_json=True))
            line = row['geometry']
            intersection = h3_poly.intersection(line)
            return pd.Series({'intersection_length': intersection.length})
        except Exception as e:
            print(f"Error in calculate_intersection: {e}")
            return pd.Series({'intersection_length': 0})

    tqdm.pandas(desc="Calculating intersections")
    joined['intersection_length'] = joined.progress_apply(calculate_intersection, axis=1)

    # Calculate length ratios
    joined['length_ratio'] = joined['intersection_length'] / joined['linkLength']

    # Keep only necessary columns
    intersection_df = joined[['h3_cell', 'linkId', 'length_ratio']]

    intersection_df.to_csv(f'{output_dir}/network.h3.csv', index=False)

    return intersection_df


def process_h3_data(h3_df, data_df, data_col):
    print(f"Initial emissions_df shape: {data_df.shape}")

    # Filter emissions data for the specific pollutant
    data_df[data_col] = pd.to_numeric(data_df[data_col], errors='coerce')
    data_df_filtered = data_df.dropna()
    print(f"Filtered emissions shape: {data_df_filtered.shape}")

    # Merge with intersection data
    merged = pd.merge(h3_df, data_df_filtered, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'weighted_{data_col}'] = merged[data_col] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'weighted_{data_col}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result


def process_h3_emissions(emissions_df, intersection_df, pollutant):
    print(f"Initial emissions_df shape: {emissions_df.shape}")

    # Filter emissions data for the specific pollutant
    filtered_emissions = emissions_df[emissions_df['pollutant'] == pollutant][['scenario', 'linkId', 'rate']]
    filtered_emissions['rate'] = pd.to_numeric(filtered_emissions['rate'], errors='coerce')
    filtered_emissions = filtered_emissions.dropna()
    print(f"Filtered emissions shape: {filtered_emissions.shape}")

    # Merge with intersection data
    merged = pd.merge(intersection_df, filtered_emissions, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'{pollutant}'] = merged['rate'] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'{pollutant}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result

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


def create_model_vmt_comparison_chart(emfac_vmt_file, emfac_area, emfac_scenario, skims_data, famos_scenario, output_dir):
    df = pd.read_csv(emfac_vmt_file)
    _, ft_emfac_class_map = create_vehicle_class_mapping(df["vehicle_class"].unique())
    filtered_df = df[
        (df['calendar_year'] == emfac_scenario) &
        (df['sub_area'].str.contains(fr'\({region_to_emfac_area[emfac_area]}\)')) &
        (df['vehicle_class'].map(ft_emfac_class_map))].copy()
    filtered_df["class"] = df['vehicle_class'].map(ft_emfac_class_map).map(
        {
            'Class 4-6 Vocational': 'Class456',
            'Class 7&8 Vocational': 'Class78',
            'Class 7&8 Tractor': 'Class78'
        }
    )
    filtered_df["fuel_class"] = filtered_df["fuel"] + "-" + filtered_df["class"]
    emfac_vmt = filtered_df.groupby(["fuel_class"])["total_vmt"].sum().reset_index()
    emfac_vmt.rename(columns={'total_vmt': 'mvmt'}, inplace=True)
    emfac_vmt["model"] = "emfac"
    famos_vmt = skims_data[skims_data["scenario"] == famos_scenario].groupby(
        ["class", "beamFuel"]
    )["vmt"].sum().reset_index()
    famos_vmt["fuel_class"] = famos_vmt["beamFuel"] + "-" + famos_vmt["class"]
    famos_vmt = famos_vmt[["fuel_class", "vmt"]].copy()
    famos_vmt.rename(columns={'vmt': 'mvmt'}, inplace=True)
    famos_vmt["model"] = "famos"
    emfac_famos_vmt = pd.concat([emfac_vmt, famos_vmt], axis=0)
    emfac_famos_vmt.to_csv(f"{output_dir}/emfac_famos_vmt_by_fuel_class.csv")
    return emfac_famos_vmt

if __name__ == "__main__":
    # Example usage
    road_dust_dir = os.path.expanduser("~/Workspace/Models/emfac/road_dust/CA_input")
    rainy_days_file = f"{road_dust_dir}/rainy_days.csv"
    silt_loading_file = f"{road_dust_dir}/silt_loading.csv"
    air_basin_region = ["SF"]  # Example air basin

    # Create output file name based on air basin
    basin_str = "_".join([b.replace(" ", "") for b in air_basin_region])
    output_file = f"road_dust_emission_rates_{basin_str}.csv"

    # Process road dust emission rates
    emissions_df = process_road_dust(rainy_days_file, silt_loading_file, air_basin_region, output_file)

    print("\nSample of processed road dust emission rates:")
    print(emissions_df.head())