import pandas as pd
import numpy as np
import os
import re
import shutil
import math
import warnings

class_2b3 = 'Class 2b&3 Vocational'
class_46 = 'Class 4-6 Vocational'
class_78_v = 'Class 7&8 Vocational'
class_78_t = 'Class 7&8 Tractor'
class_car = "Car"  # these include light and medium duty trucks
class_bike = "Bike"
class_mdp = "MediumDutyPassenger"

class_to_category = {
    class_2b3: 'Class2b3Vocational',
    class_46: 'Class456Vocational',
    class_78_v: 'Class78Vocational',
    class_78_t: 'Class78Tractor'
}

ft_emfac_class_map = {
    # Class 2b&3 Vocational
    'LHD1': class_2b3,
    'LHD2': class_2b3,

    # Class 4-6 Vocational
    'T6 Instate Delivery Class 4': class_46,
    'T6 Instate Delivery Class 5': class_46,
    'T6 Instate Delivery Class 6': class_46,
    'T6 Instate Other Class 4': class_46,
    'T6 Instate Other Class 5': class_46,
    'T6 Instate Other Class 6': class_46,
    'T6 CAIRP Class 4': class_46,
    'T6 CAIRP Class 5': class_46,
    'T6 CAIRP Class 6': class_46,
    'T6 OOS Class 4': class_46,
    'T6 OOS Class 5': class_46,
    'T6 OOS Class 6': class_46,
    'T6 Instate Tractor Class 6': class_46,

    # Class 7&8 Vocational
    'T6 Instate Delivery Class 7': class_78_v,
    'T6 Instate Other Class 7': class_78_v,
    'T7 Single Concrete/Transit Mix Class 8': class_78_v,
    'T7 Single Dump Class 8': class_78_v,
    'T7 Single Other Class 8': class_78_v,
    'T7IS': class_78_v,  # In-State

    # Class 7&8 Tractor
    'T6 Instate Tractor Class 7': class_78_t,
    'T7 Tractor Class 8': class_78_t,
    'T6 CAIRP Class 7': class_78_t,
    'T6 OOS Class 7': class_78_t,
    'T7 CAIRP Class 8': class_78_t,
    'T7 NNOOS Class 8': class_78_t,
    'T7 NOOS Class 8': class_78_t,
}

pax_emfac_class_map = {
    "LDA": class_car,  # Passenger Cars
    "LDT1": class_car,  # Light-Duty Trucks (GVWR* <6000 lbs and ETW** <= 3750 lbs)
    "LDT2": class_car,  # Light-Duty Trucks (GVWR <6000 lbs and ETW 3751-5750 lbs)
    "MDV": class_car,  # Medium-Duty Trucks (GVWR 5751-8500 lbs)
    "MCY": class_bike,  # Motorcycles
    "UBUS": class_mdp  # Urban Buses
}

non_mapped_emfac_classes = [
    "SBUS",  # School Buses
    "Motor Coach",  # Motor Coach
    "OBUS",  # Other Buses
    "All Other Buses",  # All Other Buses
    "MH",  # Motor Homes
    "T6 Utility Class 5",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Utility Class 6",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Utility Class 7",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 26001-33000 lbs)

    ## used by public entities such as municipalities, state governments, and other public agencies
    "T6 Public Class 4",  # Medium-Heavy Duty Public Fleet Truck (GVWR 14001-16000 lbs)
    "T6 Public Class 5",  # Medium-Heavy Duty Public Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Public Class 6",  # Medium-Heavy Duty Public Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Public Class 7",  # Medium-Heavy Duty Public Fleet Truck (GVWR 26001-33000 lbs)
    "T7 Public Class 8",  # Heavy-Heavy Duty Public Fleet Truck (GVWR 33001 lbs and over)

    "T6TS",
    # Medium-Heavy Duty Truck; "Transit/Specialized," these trucks are designed for specific types of operations
    "T7 Utility Class 8",  # Heavy-Heavy Duty Utility Fleet Truck (GVWR 33001 lbs and over)
    "T7 Other Port Class 8",  # Heavy-Heavy Duty Drayage Truck at Other Facilities (GVWR 33001 lbs and over)
    "T7 POAK Class 8",  # Heavy-Heavy Duty Drayage Truck in Bay Area (GVWR 33001 lbs and ove
    "T7 POLA Class 8",  # Heavy-Heavy Duty Drayage Truck near South Coast (GVWR 33001 lbs and over)
    "T7 SWCV Class 8",  # Heavy-Heavy Duty Solid Waste Collection Truck (GVWR 33001 lbs and over)
    "PTO",  # Power Take Off
]

emfac_fuel_to_beam_fuel_map = {
    'Dsl': 'diesel',
    'Gas': 'gasoline',
    'NG': 'naturalgas',
    'Elec': 'electricity',
    'Phe': 'pluginhybridelectricity',
    'H2fc': 'hydrogen',
    'BioDsl': 'biodiesel'
}
beam_fuel_to_emfac_fuel_map = {
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

warnings.filterwarnings("ignore", category=FutureWarning, message="The default dtype for empty Series will be 'object' instead of 'float64' in a future version. Specify a dtype explicitly to silence this warning.")


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


def prepare_emfac_population_for_mapping(emfac_population, year, emfac_class_map, fuel_assumption_mapping, ignore_model_year=True):
    df = emfac_population[(emfac_population["calendar_year"] == str(year))].drop(["calendar_year"], axis=1)
    if ignore_model_year:
        # Group by vehicle_class and fuel, aggregating population
        df = df.groupby(['vehicle_class', 'fuel'], as_index=False)['population'].sum()

    df['beamClass'] = df['vehicle_class'].map(emfac_class_map)
    df.dropna(subset=['beamClass'], inplace=True)

    # Validation checks
    if len(df["vehicle_class"].unique()) != len(emfac_class_map):
        print("Warning: Mismatch in vehicle class mapping")
    if not df['fuel'].isin(emfac_fuel_to_beam_fuel_map.keys()).all():
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
        (data['primaryFuelType'] == emfac_fuel_to_beam_fuel_map["Elec"]) &
        data['secondaryFuelType'].notna(),
        emfac_fuel_to_beam_fuel_map['Phe'],
        data['primaryFuelType']
    )

    def handle_missing_fuel(x):
        try:
            return fuel_assumption_mapping[beam_fuel_to_emfac_fuel_map[x.lower()]]
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
        (ft_vehicletypes['primaryFuelType'] == emfac_fuel_to_beam_fuel_map["Elec"]) &
        ft_vehicletypes['secondaryFuelType'].notna(),
        emfac_fuel_to_beam_fuel_map['Phe'],
        ft_vehicletypes['primaryFuelType']
    )

    def handle_missing_fuel(x):
        try:
            return fuel_assumption_mapping[beam_fuel_to_emfac_fuel_map[x.lower()]]
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
    has_non_empty_speed_time = any(len(str(x)) > 0 for x in unique_speed_time) and not pd.isnull(unique_speed_time).all()
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
                df_temp = numerical_column_to_binned_and_pivot(df_temp, 'speed_time', 'speed_mph_float_bins', [0.0, 200.0])
            elif process == 'STREX':
                df_temp = numerical_column_to_binned_and_pivot(df_temp, 'speed_time', 'time_minutes_float_bins', [0.0, 3600.0])
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


