import pandas as pd
import numpy as np
import pyarrow as pa
import pyarrow.csv as pv
import pyarrow.compute as pc
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import matplotlib.colors as colors
import geopandas as gpd
from matplotlib import colors
from pyproj import Transformer
from shapely.geometry import LineString, Polygon
from tqdm import tqdm
from tqdm.auto import tqdm
from matplotlib.colors import LogNorm
import contextily as cx
import seaborn as sns
import gzip
import io
import h3
import time
import os
import re
import shutil
import math
import warnings

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

# Fuel Color Map
fuel_color_map = {
    'Elec': '#2070b4',  # Darker Blue (Cleanest)
    'H2fc': '#00CED1',  # Turquoise (Clean)
    'NG': '#1a8c4a',    # Darker Green (Relatively Clean)
    'Phe': '#90EE90',   # Light Green (Hybrid, intermediate)
    'Gas': '#cc5500',   # Darker Orange (Less Clean)
    'BioDsl': '#A52A2A',  # Brown (Biofuel, less clean than traditional diesel)
    'Dsl': '#5a6268',   # Darker Gray (Least Clean)
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
    # First, replace forward slashes with dashes
    sanitized = filename.replace('/', '-')
    # Then remove or replace any other non-alphanumeric characters (except dashes)
    sanitized = re.sub(r'[^\w\-]', '-', sanitized)
    # Replace any sequence of dashes with a single dash
    sanitized = re.sub(r'-+', '-', sanitized)
    # Remove leading and trailing dashes
    sanitized = sanitized.strip('-')
    return sanitized


def get_vehicle_class_from_freight(vehicle_type):
    if 'md' in vehicle_type:
        return class_46
    elif 'hdt' in vehicle_type:
        return class_78_v
    elif 'hdv' in vehicle_type:
        return class_78_t
    else:
        return 'Unknown'


def prepare_emfac_emissions_for_mapping(emissions_rates, emfac_class_map):
    data = emissions_rates.copy()
    data = data.fillna({'speed_time': ''})  # Replace NaN with empty string
    data = data.reset_index(drop=True)  # Reset index
    grouped_data = data.groupby(
        ['sub_area', "vehicle_class", 'fuel', 'process', "speed_time", "pollutant"]
    )['emission_rate'].mean().reset_index()
    # Extract county and area from sub_area
    grouped_data['beamClass'] = grouped_data['vehicle_class'].map(emfac_class_map)
    grouped_data.dropna(subset=['beamClass'], inplace=True)
    grouped_data[['county', 'area']] = grouped_data['sub_area'].str.extract(r'^([^()]+)\s*\(([^)]+)\)')
    # Clean up the extracted data
    grouped_data['county'] = grouped_data['county'].str.strip().str.lower()
    grouped_data['area'] = grouped_data['area'].str.strip()
    grouped_data.drop(['sub_area'], axis=1, inplace=True)
    # Create emfacId
    grouped_data['emfacId'] = grouped_data.apply(
        lambda row: sanitize_name(f"{row['vehicle_class']}-{row['fuel']}"),
        axis=1
    )
    grouped_data.rename(columns={'vehicle_class': 'emfacClass', 'fuel': 'emfacFuel'}, inplace=True)
    return grouped_data


def prepare_emfac_population_for_mapping(emfac_population, year, emfac_class_map, fuel_assumption_mapping,
                                         ignore_model_year=True):
    df = emfac_population[(emfac_population["calendar_year"] == str(year))].drop(["calendar_year"], axis=1)
    if ignore_model_year:
        # Group by vehicle_class and fuel, aggregating population
        df = df.groupby(['vehicle_class', 'fuel'], as_index=False)['population'].sum()

    df['beamClass'] = df['vehicle_class'].map(emfac_class_map)
    df.dropna(subset=['beamClass'], inplace=True)

    # Validation checks
    if len(df["vehicle_class"].unique()) != len(emfac_class_map):
        print("Warning: Mismatch in vehicle class mapping")
    if not df['fuel'].isin(fuel_emfac2beam_map.keys()).all():
        print("Warning: Missing fuel type from dictionary")

    df["mappedFuel"] = df['fuel'].map(fuel_assumption_mapping)
    df['emfacId'] = df.apply(
        lambda row: sanitize_name(f"{row['vehicle_class']}-{row['fuel']}"),
        axis=1
    )
    df.rename(columns={'vehicle_class': 'emfacClass', 'fuel': 'emfacFuel'}, inplace=True)
    return df


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


def prepare_pax_vehicle_population_for_mapping(vehicletypes, fuel_assumption_mapping):
    # Apply the parsing function to create a new DataFrame with the parsed values
    data = vehicletypes.copy()
    data = data[
        (data['vehicleCategory'].isin([class_car, class_bike])) |
        ((data['vehicleCategory'] == class_mdp) & (data['vehicleTypeId'].str.lower().str.contains('bus')))
        ]

    # parsed_probs = data['sampleProbabilityString'].apply(parse_probability_string).apply(pd.Series)
    # Merge the new columns with the original DataFrame
    # data = pd.concat([data, parsed_probs], axis=1)
    # Fill NaN values with 0 for the new probability columns
    # prob_columns = [col for col in data.columns if col.startswith('ridehail_prob') or col.startswith('private_prob')]
    # data[prob_columns] = data[prob_columns].fillna(0)
    # Load and process vehicle types
    data['beamClass'] = data['vehicleCategory']
    data['beamFuel'] = np.where(
        (data['primaryFuelType'] == fuel_emfac2beam_map["Elec"]) &
        data['secondaryFuelType'].notna(),
        fuel_emfac2beam_map['Phe'],
        data['primaryFuelType']
    )

    def handle_missing_fuel(x):
        try:
            return fuel_assumption_mapping[fuel_beam2emfac_map[x.lower()]]
        except KeyError:
            warnings.warn(f"Fuel type '{x}' not found in mapping. Using original value.")
            return None

    data['mappedFuel'] = data['beamFuel'].map(handle_missing_fuel)
    return data


def prepare_ft_vehicle_population_for_mapping(carriers, payloads_raw, ft_vehicletypes,
                                              fuel_assumption_mapping):
    carriers_formatted = carriers[['tourId', 'vehicleId', 'vehicleTypeId']]
    payloads = payloads_raw[['payloadId', 'tourId', 'payloadType']].copy()
    ft_vehicletypes = ft_vehicletypes[['vehicleTypeId', 'primaryFuelType', 'secondaryFuelType']].copy()

    ft_vehicletypes['beamClass'] = ft_vehicletypes['vehicleTypeId'].apply(get_vehicle_class_from_freight)

    # Summarize data
    payloads.loc[:, 'payloadType'] = payloads['payloadType'].astype(str)
    payloads_summary = payloads.groupby(['tourId'])['payloadType'].agg('|'.join).reset_index()

    # Merge payload summary with carriers
    payloads_merged = pd.merge(payloads_summary, carriers_formatted, on='tourId', how='left')

    # Load and process vehicle types
    ft_vehicletypes['beamFuel'] = np.where(
        (ft_vehicletypes['primaryFuelType'] == fuel_emfac2beam_map["Elec"]) &
        ft_vehicletypes['secondaryFuelType'].notna(),
        fuel_emfac2beam_map['Phe'],
        ft_vehicletypes['primaryFuelType']
    )

    def handle_missing_fuel(x):
        try:
            return fuel_assumption_mapping[fuel_beam2emfac_map[x.lower()]]
        except KeyError:
            warnings.warn(f"Fuel type '{x}' not found in mapping. Using original value.")
            return x

    ft_vehicletypes['mappedFuel'] = ft_vehicletypes['beamFuel'].map(handle_missing_fuel)

    # Merge payloads with vehicle types
    payloads_vehtypes = pd.merge(
        payloads_merged,
        ft_vehicletypes[['vehicleTypeId', 'beamClass', 'beamFuel', 'mappedFuel']],
        on='vehicleTypeId',
        how='left'
    )

    # Check for missing fuel types
    if payloads_vehtypes['beamFuel'].isna().any():
        print("Warning: Missing fuel types for some vehicle IDs")
        print(payloads_vehtypes[payloads_vehtypes['beamFuel'].isna()])

    # Remove duplicates and return
    return payloads_vehtypes.drop_duplicates('vehicleId', keep='first')


def distribution_based_vehicle_classes_assignment(ft_df, emfac_df):
    # Remove 'Class 2b&3 Vocational' from EMFAC data
    emfac_df = emfac_df[emfac_df['beamClass'] != class_2b3]

    def sample_emfac(the_class, ft_mapped_fuel):
        emfac_grouped = emfac_df[
            (emfac_df['beamClass'] == the_class) & (emfac_df['mappedFuel'] == ft_mapped_fuel)]
        if emfac_grouped.empty:
            print(f"failed to match this fuel: {ft_mapped_fuel}")
            emfac_grouped = emfac_df[emfac_df['beamClass'] == the_class]
        return emfac_grouped.sample(n=1, weights='population')['emfacId'].iloc[0]

    total_emfac = emfac_df["population"].sum()
    class_46_share = emfac_df[emfac_df['beamClass'] == class_46]["population"].sum() / total_emfac
    class_78_v_share = emfac_df[emfac_df['beamClass'] == class_78_v]["population"].sum() / total_emfac
    total_freight = len(ft_df)
    class_46_target = int(class_46_share * total_freight)
    class_78_v_target = int(class_78_v_share * total_freight)

    class_46_count = 0
    class_78_v_count = 0

    def sample_emfac_class(row):
        nonlocal class_46_count, class_78_v_count

        if class_46_count < class_46_target:
            if row['beamClass'] == class_46:
                class_46_count += 1
                return sample_emfac(class_46, row['mappedFuel'])

            if row['beamClass'] == class_78_v:
                class_46_count += 1
                return sample_emfac(class_46, row['mappedFuel'])

            if row['beamClass'] == class_78_t:
                class_46_count += 1
                return sample_emfac(class_46, row['mappedFuel'])

        if class_78_v_count < class_78_v_target:
            if row['beamClass'] == class_78_v:
                class_78_v_count += 1
                return sample_emfac(class_78_v, row['mappedFuel'])

            if row['beamClass'] == class_78_t:
                class_78_v_count += 1
                return sample_emfac(class_78_v, row['mappedFuel'])

        return sample_emfac(class_78_t, row['mappedFuel'])

    ft_df['beamClassBis'] = ft_df['beamClass'].map({class_46: 1, class_78_v: 2, class_78_t: 3})
    ft_df['emfacId'] = ft_df.sort_values('beamClassBis').apply(sample_emfac_class, axis=1)
    ft_df["oldVehicleTypeId"] = ft_df["vehicleTypeId"]
    ft_df['vehicleTypeId'] = ft_df.apply(
        lambda row: f"EMFAC-{row['emfacId']}--TRUCK-{'-'.join(row['oldVehicleTypeId'].split('-')[:-1])}",
        axis=1
    )
    merged = pd.merge(ft_df, emfac_df.drop(["beamClass", "mappedFuel"], axis=1), on="emfacId", how="left").drop(
        ["beamClassBis"], axis=1)
    return merged


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


def format_rates_for_beam(emissions_rates):
    from joblib import Parallel, delayed

    # Assuming emissions_rates is already loaded into a DataFrame `df`
    group_by_cols = ["county", "emfacId"]
    df_unique = emissions_rates[group_by_cols].drop_duplicates().reset_index(drop=True)

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

    # Reorder columns to ensure 'county' is at the front
    columns = df_output.columns.tolist()
    columns = ['county'] + [col for col in columns if col != 'county']
    df_output = df_output[columns]
    return df_output, filtered_out


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


def assign_emissions_rates_to_vehtypes(emissions_rates, vehicle_types, output_dir, emissions_rates_relative_filepath):
    from joblib import Parallel, delayed
    emissions_rates_dir = os.path.abspath(os.path.join(output_dir, emissions_rates_relative_filepath))
    if ensure_empty_directory(emissions_rates_dir):
        print(f"Ready to write new data to the directory {emissions_rates_dir}")
    else:
        print(f"Failed to prepare the directory {emissions_rates_dir}. Please check permissions and try again.")
    os.makedirs(emissions_rates_dir, exist_ok=True)

    # Use parallel processing with error handling and chunking
    chunk_size = 100  # Adjust this value based on your data size and available memory
    results = []

    for i in range(0, len(vehicle_types), chunk_size):
        chunk = vehicle_types.iloc[i:i + chunk_size]

        chunk_results = Parallel(n_jobs=-1, timeout=600)(  # 10-minute timeout
            delayed(process_single_vehicle_type)(
                veh_type,
                emissions_rates,
                f"{output_dir}/{emissions_rates_relative_filepath}/TrAP--"
            ) for _, veh_type in chunk.iterrows()
        )

        results.extend(chunk_results)

        # Clear some memory
        del chunk_results

    # Update the vehicle_types DataFrame with the new emissionsRatesFile information
    for veh_type_id in results:
        if veh_type_id:
            relative_rates_filepath = f"{emissions_rates_relative_filepath}/TrAP--{veh_type_id}.csv"
            vehicle_types.loc[
                vehicle_types['vehicleTypeId'] == veh_type_id, 'emissionsRatesFile'] = relative_rates_filepath

    return vehicle_types


def build_new_pax_vehtypes(pax_emfac_population_for_mapping, pax_population_for_mapping):
    df_merged = pd.merge(pax_population_for_mapping, pax_emfac_population_for_mapping,
                         on=['beamClass', 'mappedFuel'], how='left')
    df_merged_car = df_merged[df_merged["beamClass"] == class_car].copy()
    df_merged_others = df_merged[df_merged["beamClass"] != class_car].copy()

    df_merged_car['population_share'] = df_merged_car['population'] / df_merged_car['population'].sum()
    df_merged_car['updated_sampleProbabilityString'] = df_merged_car.apply(
        lambda row: update_sample_probability_string(row),
        axis=1
    )
    df_merged_car['updated_sampleProbabilityWithinCategory'] = df_merged_car.apply(
        lambda row: row['sampleProbabilityWithinCategory'] * row['population_share'],
        axis=1
    )
    # Update vehicleTypeId only for eligible rows
    df_merged_car['updated_vehicleTypeId'] = df_merged_car.apply(
        lambda row: f"EMFAC-{row['emfacId']}--ADOPT-{row['vehicleTypeId']}",
        axis=1
    )
    # Update the original dataframe with new probabilities and vehicleTypeId
    df_merged_car['sampleProbabilityString'] = df_merged_car['updated_sampleProbabilityString']
    df_merged_car['sampleProbabilityWithinCategory'] = df_merged_car['updated_sampleProbabilityWithinCategory']
    df_merged_car['vehicleTypeId'] = df_merged_car['updated_vehicleTypeId']
    updated_pax_vehicle_types = pd.concat([df_merged_car[df_merged_others.columns], df_merged_others], axis=0)

    return updated_pax_vehicle_types


def build_new_ft_vehtypes(updated_ft_population, ft_vehicle_types):
    # Create a copy of the original vehicleTypeId and set up a lookup dictionary
    ft_vehicle_types_dict = ft_vehicle_types.set_index("vehicleTypeId").to_dict('index')

    # Remove duplicates based on vehicleTypeId, keeping the first occurrence
    unique_vehicle_types = updated_ft_population.drop_duplicates(subset='vehicleTypeId', keep='first')

    def process_row(row):
        new_row = ft_vehicle_types_dict[row["oldVehicleTypeId"]].copy()
        new_row["vehicleTypeId"] = row["vehicleTypeId"]
        new_row['vehicleClass'] = row["beamClass"]
        new_row['vehicleCategory'] = class_to_category.get(row['beamClass'], 'Unknown')
        new_row["emfacId"] = row['emfacId']
        return new_row

    # Apply process_row to the unique vehicle types
    result_df = pd.DataFrame(unique_vehicle_types.apply(process_row, axis=1).tolist())

    # Define the desired column order with 'vehicleTypeId' at the front
    columns_order = ['vehicleTypeId'] + [
        col for col in result_df.columns if col not in {'vehicleTypeId'}
    ]

    # Reorder the columns
    result_df = result_df[columns_order]

    return result_df


def assign_new_ft_vehtypes_to_carriers(carrier_df, updated_ft_population, carriers_emissions_file):
    vehicle_id_to_type_mapping = dict(zip(updated_ft_population['vehicleId'],
                                          updated_ft_population['vehicleTypeId']))

    def update_vehicle_type(row):
        return vehicle_id_to_type_mapping.get(row['vehicleId'])

    carrier_df_new = carrier_df.copy()
    carrier_df_new['vehicleTypeId'] = carrier_df.apply(update_vehicle_type, axis=1)
    carrier_df_new.dropna(subset=['vehicleTypeId'], inplace=True)
    print(f"Writing {carriers_emissions_file}")
    carrier_df_new.to_csv(carriers_emissions_file, index=False)
    return carrier_df_new


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


def create_vehicle_class_mapping(vehicle_list):
    mapping = {}

    for vehicle in vehicle_list:
        if 'Utility' in vehicle or 'Public' in vehicle:
            mapping[vehicle] = not_matched
        elif 'Port' in vehicle or 'POLA' in vehicle or 'POAK' in vehicle:
            mapping[vehicle] = not_matched
        elif 'SWCV' in vehicle or 'PTO' in vehicle or 'T6TS' in vehicle:
            mapping[vehicle] = not_matched

        elif vehicle in ['LDA', 'LDT1', 'LDT2', 'MDV']:
            mapping[vehicle] = class_car
        elif vehicle in ['MCY']:
            mapping[vehicle] = class_bike
        elif vehicle in ['UBUS']:
            mapping[vehicle] = class_mdp
        elif 'LHD' in vehicle:
            mapping[vehicle] = class_2b3

        elif 'Class 4' in vehicle or 'Class 5' in vehicle or 'Class 6' in vehicle:
            mapping[vehicle] = class_46

        elif 'Class 7' in vehicle or 'Class 8' in vehicle:
            if 'Tractor' in vehicle or 'CAIRP' in vehicle:
                mapping[vehicle] = class_78_t
            else:
                mapping[vehicle] = class_78_v
        elif "T7IS" in vehicle:
            mapping[vehicle] = class_78_t

        else:
            mapping[vehicle] = not_matched

    from collections import defaultdict
    class_groups = defaultdict(list)
    for vehicle, vehicle_class in mapping.items():
        class_groups[vehicle_class].append(vehicle)
    for vehicle_class, vehicles in class_groups.items():
        print(f"Category: {vehicle_class}")
        for vehicle in vehicles:
            print(f"  - {vehicle}")

    ft_emfac_class_map = {emfac: beam for emfac, beam in mapping.items() if
                          beam in [class_46, class_78_v, class_78_t]}
    pax_emfac_class_map = {emfac: beam for emfac, beam in mapping.items() if
                           beam in [class_car, class_bike, class_mdp]}

    return pax_emfac_class_map, ft_emfac_class_map


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
    vehicleTypes['fuel'] = vehicleTypes['emfacId'].str.split('-').str[-1]
    vehicleTypes['class'] = vehicleTypes['vehicleClass'].str.replace('Vocational|Tractor', '', regex=True).str.strip()

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
            .merge(vehicleTypes[['vehicleTypeId', 'class', 'fuel']], on='vehicleTypeId', how='left')
            .merge(network[['linkId', 'linkLength']], on='linkId', how='left')
        )
        # del df_chunk

        # Calculate annualHourlyMVMT
        df_chunk_merged['vmt'] = (df_chunk_merged['linkLength'] * 6.21371192e-4) * observations_expansion

        # Rename column
        df_chunk_merged.rename(columns={'emissionsProcess': 'process'}, inplace=True)

        # Melt the dataframe
        id_vars = ['hour', 'linkId', 'tazId', 'class', 'fuel', 'process', 'kwh', 'vmt', 'vht']
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


def emissions_by_scenario_hour_class_fuel(emissions_skims, pollutant, output_dir):
    data = emissions_skims[emissions_skims['pollutant'] == pollutant].copy()
    grouped_data = data.groupby(['scenario', 'hour', 'class', 'fuel'])['rate'].sum().reset_index()

    plt.figure(figsize=(20, 10))

    grouped_data['fuel_class'] = grouped_data['fuel'].astype(str) + ', ' + grouped_data['class'].astype(str)
    scenarios = grouped_data['scenario'].unique()
    fuel_classes = sorted(grouped_data['fuel_class'].unique())
    all_hours = sorted(grouped_data['hour'].unique())

    # Function to darken a color
    def darken_color(color, factor=0.7):
        return mcolors.to_rgba(mcolors.to_rgb(color), alpha=None)[:3] + (factor,)

    # Create color map for fuel_classes
    fuel_class_colors = {}
    for fc in fuel_classes:
        fuel, vehicle_class = fc.split(',')
        fuel = fuel.strip()
        vehicle_class = vehicle_class.strip()
        base_color = fuel_color_map.get(fuel, '#000000')  # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            fuel_class_colors[fc] = darken_color(base_color)
        else:
            fuel_class_colors[fc] = base_color

    x = np.arange(len(all_hours))
    width = 0.35 / len(scenarios)

    scenarios_labeling = []
    for i, scenario in enumerate(scenarios):
        scenarios_labeling.append(scenario)
        scenario_data = grouped_data[grouped_data['scenario'] == scenario]

        bottom = np.zeros(len(all_hours))
        for fuel_class in fuel_classes:
            fuel_class_data = scenario_data[scenario_data['fuel_class'] == fuel_class]

            # Create an array of rates for all hours, filling with zeros where data is missing
            rates = np.zeros(len(all_hours))
            for _, row in fuel_class_data.iterrows():
                hour_index = all_hours.index(row['hour'])
                rates[hour_index] = row['rate']

            plt.bar(x + i * width, rates, width, bottom=bottom,
                    label=f"{fuel_class}" if i == 0 else "",
                    color=fuel_class_colors[fuel_class])
            bottom += rates

    plt.title(
        f'{pollutant.replace("_", ".")} Annual Emissions by Hour, Scenario, Class and Fuel:\n{" vs ".join(scenarios_labeling)}',
        fontsize=28)
    plt.xlabel('Hour', fontsize=24)
    plt.ylabel('Emissions (tons)', fontsize=24)
    plt.xticks(x + width * (len(scenarios) - 1) / 2, all_hours, fontsize=24)
    plt.yticks(fontsize=24)
    plt.legend(title='Fuel, Class', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=28, title_fontsize=28)
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    plt.tight_layout()
    plt.savefig(f'{output_dir}/{pollutant}_emissions_by_scenario_hour_class_fuel.png', dpi=300, bbox_inches='tight')


def plot_hourly_activity(tours_types, output_dir):
    # Preprocess the data
    tours_types['class'] = tours_types['vehicleCategory'].str.replace('Vocational|Tractor', '', regex=True).str.strip()
    tours_types['fuel'] = tours_types['primaryFuelType'].str.lower().map(fuel_beam2emfac_map)
    tours_types['fuel'] = np.where((tours_types['fuel'] == "Elec") & tours_types['secondaryFuelType'].notna(), 'Phe',
                                   tours_types['fuel'])
    tours_types['fuel_class'] = tours_types['fuel'] + '-' + tours_types['class']
    tours_types['departure_hour'] = (tours_types['departureTimeInSec'] / 3600).astype(int) % 24

    # Group by scenario, hour, and fuel_class, count the number of tours
    hourly_activity = tours_types.groupby(['scenario', 'departure_hour', 'fuel_class']).size().unstack(
        level=[0, 2], fill_value=0
    )

    scenarios = tours_types['scenario'].unique()
    # If the DataFrame is empty, create a default one with all hours
    if hourly_activity.empty:
        fuel_classes = tours_types['fuel_class'].unique()
        index = pd.Index(range(24), name='departure_hour')
        columns = pd.MultiIndex.from_product([scenarios, fuel_classes], names=['scenario', 'fuel_class'])
        hourly_activity = pd.DataFrame(0, index=index, columns=columns)
    else:
        # Ensure all hours are present
        for hour in range(24):
            if hour not in hourly_activity.index:
                hourly_activity.loc[hour] = 0
        hourly_activity = hourly_activity.sort_index()

    # Create the plot
    plt.figure(figsize=(20, 10))
    x = np.arange(24)  # 24 hours
    width = 0.35  # width of the bars
    scenarios = hourly_activity.columns.levels[0]

    # Get all unique fuel classes across all scenarios
    all_fuel_classes = set()
    for scenario in scenarios:
        all_fuel_classes.update(hourly_activity[scenario].columns)

    # Define the order of fuels from most to least sustainable
    fuel_order = ['Elec', 'H2fc', 'Phe', 'NG', 'BioDsl', 'Dsl', 'Gas']

    # Sort fuel classes based on the defined order
    sorted_fuel_classes = sorted(all_fuel_classes,
                                 key=lambda x: (
                                 fuel_order.index(x.split('-')[0]) if x.split('-')[0] in fuel_order else len(
                                     fuel_order), x))

    # Create a color map for all fuel types
    color_map = {fuel: fuel_color_map[fuel] for fuel in fuel_order}

    # Plot stacked bars for each scenario
    legend_handles = []
    legend_labels = []
    for i, scenario in enumerate(scenarios):
        bottom = np.zeros(24)
        for fuel_class in sorted_fuel_classes:
            fuel_type = fuel_class.split('-')[0]
            color = color_map[fuel_type]

            if fuel_class in hourly_activity[scenario].columns:
                values = hourly_activity[scenario][fuel_class]
            else:
                values = np.zeros(24)

            bar = plt.bar(x + i * width, values, width, bottom=bottom, color=color)
            bottom += values

            if fuel_class not in legend_labels:
                legend_handles.append(bar)
                legend_labels.append(fuel_class)

    plt.title(f'Hourly Tour Activity by Scenario and Fuel Class, {" vs ".join(scenarios).replace("_", " ")}', fontsize=20)
    plt.xlabel('Hour of Day', fontsize=16)
    plt.ylabel('Number of Tours Departing', fontsize=16)
    plt.xticks(x + width / 2, range(24), fontsize=12)
    plt.yticks(fontsize=12)

    # Create legend with ordered fuel classes
    plt.legend(legend_handles, legend_labels, fontsize=12, loc='upper left', bbox_to_anchor=(1, 1))

    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(f'{output_dir}/hourly_tour_activity_by_scenario_fuel_class.png', dpi=300, bbox_inches='tight')
    plt.close()

    print(f"Plot saved as {output_dir}/hourly_tour_activity_by_scenario_fuel_class.png")


def plot_hourly_vmt(df_filtered, output_dir):
    # Group by scenario, hour, fuel, class and sum annualHourlyMVMT
    hourly_vmt = df_filtered.groupby(['scenario', 'hour', 'fuel', 'class'])[
        'annualHourlyMVMT'].sum().reset_index()

    # Create a unique identifier for fuel and class combination
    hourly_vmt['fuel_class'] = hourly_vmt['fuel'] + ', ' + hourly_vmt['class']

    # Pivot the data to have scenarios as columns
    hourly_vmt_pivot = hourly_vmt.pivot_table(
        values='annualHourlyMVMT',
        index=['hour', 'fuel_class'],
        columns='scenario',
        fill_value=0
    ).reset_index()

    # Ensure all hours are present
    for hour in range(24):
        if hour not in hourly_vmt_pivot['hour'].values:
            hourly_vmt_pivot = hourly_vmt_pivot.append({'hour': hour}, ignore_index=True)

    hourly_vmt_pivot = hourly_vmt_pivot.sort_values('hour').fillna(0)

    # Get unique scenarios and fuel_class combinations
    scenarios = hourly_vmt['scenario'].unique()
    fuel_classes = sorted(hourly_vmt['fuel_class'].unique())

    # Set up the plot
    plt.figure(figsize=(20, 10))
    x = np.arange(24)  # 24 hours
    width = 0.35  # Width of a group of bars for one scenario

    # Define base color map for fuel types
    base_color_map = {
        'Elec': '#2070b4',  # Darker Blue
        'NG': '#1a8c4a',  # Darker Green
        'Gas': '#cc5500',  # Darker Orange
        'Dsl': '#5a6268',  # Darker Gray
    }

    # Function to darken a color
    def darken_color(color, factor=0.7):
        return mcolors.to_rgba(mcolors.to_rgb(color), alpha=None)[:3] + (factor,)

    # Create color map for fuel_classes
    fuel_class_colors = {}
    for fc in fuel_classes:
        fuel, vehicle_class = fc.split(',')
        fuel = fuel.strip()
        vehicle_class = vehicle_class.strip()
        base_color = base_color_map.get(fuel, '#000000')  # Default to black if fuel not found
        if any(c in vehicle_class for c in ['7', '8']):
            fuel_class_colors[fc] = darken_color(base_color)
        else:
            fuel_class_colors[fc] = base_color

    # Plot bars for each scenario and fuel_class combination
    scenarios_labeling = []
    for i, scenario in enumerate(scenarios):
        scenarios_labeling.append(scenario)
        bottom = np.zeros(24)
        for fuel_class in fuel_classes:
            values = hourly_vmt_pivot[hourly_vmt_pivot['fuel_class'] == fuel_class][scenario].values
            if len(values) < 24:
                values = np.pad(values, (0, 24 - len(values)), 'constant')
            elif len(values) > 24:
                values = values[:24]

            plt.bar(x + i * width, values, width / len(scenarios), bottom=bottom,
                    label=f"{fuel_class}" if i == 0 else "",
                    color=fuel_class_colors[fuel_class])
            bottom += values

    plt.title(
        f'Annual VMT by Scenario, Fuel, and Vehicle Class:\n{" vs ".join(scenarios_labeling)}',
        fontsize=28)

    plt.xlabel('Hour of Day', fontsize=24)
    plt.ylabel('Million Vehicle Miles Traveled (MVMT)', fontsize=24)
    plt.xticks(x + width / 2, range(24), fontsize=24)
    plt.yticks(fontsize=24)
    plt.legend(title='Fuel, Class', bbox_to_anchor=(1.05, 1), loc='upper left', fontsize=28)
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(f'{output_dir}/hourly_vmt_by_scenario_fuel_class.png', dpi=300, bbox_inches='tight')
    plt.close()

    print("Hourly VMT plot has been saved.")


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


def create_h3_heatmap(df, output_dir, pollutant, scenario, remove_outliers, in_log_scale):
    """Create a heatmap using the H3 grid structure with linear or logarithmic color scale and a base map."""
    subset_df = df[df["scenario"] == scenario]

    if remove_outliers:
        subset_df = remove_outliers_zscore(subset_df, pollutant)

    # Create polygons for all H3 cells in the result
    polygons = [Polygon(h3.h3_to_geo_boundary(h3_cell, geo_json=True)) for h3_cell in subset_df['h3_cell']]

    # Create GeoDataFrame
    gdf = gpd.GeoDataFrame({
        'h3_cell': subset_df['h3_cell'],
        'pollutant': subset_df[pollutant],
        'geometry': polygons
    })
    gdf = gdf.set_crs("EPSG:4326")

    # Convert to Web Mercator projection for compatibility with contextily
    gdf_mercator = gdf.to_crs(epsg=3857)

    # Create figure and axis
    fig, ax = plt.subplots(figsize=(15, 10))

    vmin, vmax = gdf_mercator['pollutant'].min(), gdf_mercator['pollutant'].max()

    # Determine if we're dealing with a delta (difference) calculation
    is_delta = 'Delta' in pollutant or 'Δ' in pollutant

    if in_log_scale:
        if is_delta:
            norm = mcolors.SymLogNorm(linthresh=1e-5, vmin=vmin, vmax=vmax)
        else:
            gdf_mercator = gdf_mercator[gdf_mercator['pollutant'] > 0]
            vmin, vmax = gdf_mercator['pollutant'].min(), gdf_mercator['pollutant'].max()
            norm = LogNorm(vmin=vmin, vmax=vmax)
        label_suffix = "in log scale"
        file_suffix = "log"
    else:
        if is_delta:
            norm = mcolors.TwoSlopeNorm(vmin=vmin, vcenter=0, vmax=vmax)
        else:
            norm = None
        label_suffix = ""
        file_suffix = "linear"

    # Choose colormap based on whether it's a delta calculation
    cmap = colors.LinearSegmentedColormap.from_list("", ["blue", "lightblue", "white", "pink", "red"])

    # Plot cells with data
    gdf_mercator.plot(column='pollutant', ax=ax, legend=False, cmap=cmap, edgecolor='none', norm=norm, alpha=0.7)

    # Add base map
    cx.add_basemap(ax, source=cx.providers.CartoDB.Positron)

    # Add colorbar
    sm = plt.cm.ScalarMappable(cmap=cmap, norm=norm)
    sm.set_array([])
    cbar = fig.colorbar(sm, ax=ax, extend='both')
    cbar.set_label(f'{pollutant.replace("_", ".")} {label_suffix}', rotation=270, labelpad=15)

    # Set title and adjust plot
    title_label = f'Emissions Distribution of {pollutant.replace("_", ".")}, {scenario} '
    plt.title(title_label, fontsize=16)
    ax.set_axis_off()
    plt.tight_layout()

    # Save figure
    outlier_status = "no_outliers" if remove_outliers else "with_outliers"
    file_name = f'{output_dir}/{pollutant.split(" ")[0]}_{scenario.replace(" ", "_").lower()}_emissions_heatmap_{file_suffix}_{outlier_status}_with_basemap.png'
    plt.savefig(file_name, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Heatmap with base map saved as {file_name}")


def create_h3_histogram(df, output_dir, pollutant, scenario, remove_outliers, in_log_scale):
    subset_df = df[df["scenario"] == scenario]
    if remove_outliers:
        subset_df = remove_outliers_zscore(subset_df, pollutant)
    # Extract pollutant values
    pollutant_values = subset_df[pollutant].values

    # Create the histogram
    plt.figure(figsize=(12, 6))

    if in_log_scale:
        # Use log-spaced bins, but with adjustments for potential zero values
        bins = np.logspace(np.log10(pollutant_values.min() + 1e-10),
                           np.log10(pollutant_values.max()),
                           num=50)
        x_label = f'{pollutant.replace("_", ".")} Emissions (log scale)'
        title_label = f'Histogram of {pollutant.replace("_", ".")} Emissions by H3 Cell (Log Scale)'
        file_name = f'{output_dir}/{pollutant}_{scenario.replace(" ","_").lower()}_emissions_histogram_log.png'
    else:
        # Use automatic binning based on Sturges' rule
        bins = 'sturges'
        x_label = f'{pollutant.replace("_", ".")} Emissions'
        title_label = f'Histogram of {pollutant.replace("_", ".")} Emissions by H3 Cell'
        file_name = f'{output_dir}/{pollutant}_{scenario.replace(" ","_").lower()}_emissions_histogram.png'

    plt.hist(pollutant_values, bins=bins, edgecolor='black')

    # Set x-axis to log scale if specified
    if in_log_scale:
        plt.xscale('log')

    # Set labels and title
    plt.xlabel(x_label, fontsize=12)
    plt.ylabel('Frequency', fontsize=12)
    plt.title(title_label, fontsize=14)

    # Add grid for better readability
    plt.grid(True, linestyle='--', alpha=0.7)

    # Adjust layout and save
    plt.tight_layout()
    plt.savefig(file_name, dpi=300, bbox_inches='tight')
    plt.close()
    print(f"Histogram saved as {file_name}/")


def remove_outliers_zscore(df, column, threshold=3):
    mean = df[column].mean()
    std = df[column].std()
    z_scores = np.abs((df[column] - mean) / std)
    df_filtered = df[z_scores < threshold].copy()
    removed_rows = df[~df.index.isin(df_filtered.index)]
    summary_df = pd.DataFrame({
        'column': [column],
        'mean': [mean],
        'std': [std],
        'num_outliers': [len(removed_rows)]
    })
    print(summary_df)
    print(removed_rows)
    return df_filtered


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
