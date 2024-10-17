import logging
import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from geopy import distance

## Main
# city, batch, scenario, sample = "sfbay", "baseline", "2018", 0.1
city, batch, scenario, sample = "sfbay", "2024-08-07", "2018_Baseline", 0.1
# city, batch, scenario, sample = "seattle", "2024-09-24", "2018_Baseline", 0.1
# city, batch, scenario, sample = "seattle", "2024-04-20", "2018_Baseline", 0.3
# work_dir = os.path.expanduser(f"/Volumes/HG40/Workspace/Simulation/{city}")
work_dir = os.path.expanduser(f"~/Workspace/Simulation/{city}")
events_filename = f"0.events.csv.gz"
linkstats_filename = f"0.linkstats.csv.gz"
# pd.set_option('display.max_columns',10)
scale_up_factor = 1 / sample


def main():
    setup_logging(f'{work_dir}/beam-runs/{batch}/{scenario}/freight_events_processing.log')
    log_and_print(f"Run: {city}/{batch}/{scenario}/{sample}")
    scenario_dir = os.path.join(work_dir, "beam-freight", batch, scenario)

    linkstats_file = os.path.join(get_local_work_directory(scenario), linkstats_filename)
    if os.path.exists(linkstats_file):
        linkstats_df = pd.read_csv(linkstats_file)
        calc_vmt_from_linkstats(linkstats_df, scenario)

    scenario_label = scenario.replace("_", "-")
    batch_label = batch.replace("-", "")
    carrier_df = pd.read_csv(os.path.join(scenario_dir, f"carriers--{scenario_label}.csv"))
    tour_df = pd.read_csv(os.path.join(scenario_dir, f"tours--{scenario_label}.csv"))
    payload_df = pd.read_csv(os.path.join(scenario_dir, f"payloads--{scenario_label}.csv"))
    vehicle_types = pd.read_csv(
        os.path.join(scenario_dir, "vehicle-tech", f"ft-vehicletypes--{batch_label}--{scenario_label}.csv"))
    vehicle_types_combined = merge_vehicle_types(vehicle_types, carrier_df, tour_df)
    processed_event = process_events(scenario, vehicle_types_combined)
    calc_vmt_from_events(processed_event, scenario)

    log_and_print(f"[FRISM] Total number of vehicles: {int(len(carrier_df['vehicleId'].unique()) * scale_up_factor)}")
    trips_df = convert_payload_to_trips(payload_df, scenario)
    summary = trips_by_vehicle_class(trips_df, carrier_df, vehicle_types)

    log_and_print("END")


def merge_vehicle_types(vehicle_types: pd.DataFrame, carrier_df: pd.DataFrame, tour_df: pd.DataFrame) -> pd.DataFrame:
    # Merge carrier and tour dataframes
    merged_df = pd.merge(carrier_df, tour_df, on=['tourId'], how='inner')

    # Keep only unique vehicleTypeId and tourId combinations
    result_df = merged_df[['vehicleTypeId', 'vehicleId', 'tourId']].drop_duplicates()

    # Merge with vehicle types
    result2_df = pd.merge(
        result_df,
        vehicle_types[['vehicleTypeId', 'vehicleCategory']],
        on='vehicleTypeId',
        how='left'
    )

    # Ensure uniqueness of vehicleTypeId
    result2_df = result2_df.groupby(['vehicleTypeId', 'vehicleId', 'tourId']).first().reset_index()

    return result2_df


def process_events(scenario, vehicle_types_combined):
    events_filepath = os.path.join(get_local_work_directory(scenario), events_filename)
    processed_events_filepath = os.path.join(get_local_work_directory(scenario), f"updated.filtered.{events_filename}")

    if not os.path.exists(processed_events_filepath):
        events = read_events_file(events_filepath, scenario)
        vehicle_types_combined['vehicleId'] = 'freightVehicle-' + vehicle_types_combined['vehicleId'].astype(
            str)
        processed_event_updated = pd.merge(
            events,
            vehicle_types_combined[['vehicleId', 'tourId', 'vehicleCategory']],
            left_on='vehicle',
            right_on='vehicleId',
            how='left'
        )
        processed_event_updated['powertrain'] = processed_event_updated.apply(determine_powertrain, axis=1)
        processed_event_updated['business'] = processed_event_updated['tourId'].apply(determine_business)
        processed_event_updated.to_csv(processed_events_filepath, index=False)
    else:
        processed_event_updated = pd.read_csv(processed_events_filepath)

    return processed_event_updated


def read_events_file(full_filename, run_name):
    log_and_print(f"batch {os.path.basename(os.path.dirname(full_filename))}:")
    log_and_print(f"reading: {full_filename}")

    # Use chunksize for memory efficiency
    chunksize = 1000000  # Increased chunksize for better performance
    dtypes = {'type': 'category', 'actType': 'category'}

    chunks = pd.read_csv(full_filename, sep=",", header=0,
                         compression='gzip' if full_filename.endswith('.gz') else None,
                         usecols=["time", "type", "vehicleType", "vehicle", "actType", "arrivalTime", "departureTime",
                                  "length", "primaryFuelType", "primaryFuelLevel", "primaryFuel", "secondaryFuelType",
                                  "secondaryFuelLevel", "secondaryFuel"],
                         dtype=dtypes,
                         chunksize=chunksize,
                         low_memory=False)

    output_filename = os.path.join(os.path.dirname(full_filename), f"filtered.{os.path.basename(full_filename)}")

    # Process chunks and write directly to output file
    first_chunk = True
    processed_chunks = []
    for chunk in chunks:
        # Convert 'vehicle' column to string type
        chunk['vehicle'] = chunk['vehicle'].astype(str)

        # Apply all filters at once
        mask = (
                chunk['type'].isin(["PathTraversal", "actstart", "actend"]) &
                chunk['vehicle'].str.startswith("freight", na=False) &
                (
                        chunk['actType'].str.split('|', expand=True)[0].isin(["Warehouse", "Unloading", "Loading"]) |
                        chunk['actType'].isnull()
                )
        )

        filtered_chunk = chunk[mask].copy()  # Create a copy to avoid SettingWithCopyWarning
        if not filtered_chunk.empty:
            filtered_chunk.loc[:, 'runName'] = run_name
        else:
            log_and_print(f"The current chunk of {len(chunk)} rows resulted in no freight records in it!",
                          logging.WARNING)

        processed_chunks.append(filtered_chunk)

        # Write chunk to file
        mode = 'w' if first_chunk else 'a'
        filtered_chunk.to_csv(output_filename, index=False, mode=mode,
                              header=first_chunk,
                              compression='gzip' if output_filename.endswith('.gz') else None)
        first_chunk = False

    log_and_print(f"writing completed: {output_filename}")

    # Combine all processed chunks into a single dataframe
    processed_events = pd.concat(processed_chunks, ignore_index=True)
    if processed_events.empty:
        log_and_print(f"No freight records found in this events file!", logging.ERROR)
    return processed_events


def calc_vmt_from_linkstats(linkstats_df, scenario):
    freight_classes = ["Class2b3Vocational", "Class456Vocational", "Class78Vocational", "Class78Tractor"]
    required_columns = ['length', 'hour'] + [f'volume_{col}' for col in freight_classes]
    missing_columns = [col for col in required_columns if col not in linkstats_df.columns]
    if missing_columns:
        raise ValueError(f"Missing required columns: {missing_columns}")

    linkstats_df['hour'] = pd.to_numeric(linkstats_df['hour'], errors='coerce')
    linkstats_df['length'] = pd.to_numeric(linkstats_df['length'], errors='coerce')

    for col in freight_classes:
        linkstats_df[f'volume_{col}'] = pd.to_numeric(linkstats_df[f'volume_{col}'], errors='coerce')
        linkstats_df[f'vmt_{col}'] = linkstats_df['length'] * linkstats_df[f'volume_{col}'] / 1609.34

    total_vmt = linkstats_df[
                    [f'vmt_{col}' for col in freight_classes]].sum().sum() * scale_up_factor / 1_000_000
    log_and_print(
        f"[BEAM] Total VMT from LinkStats for scenario {batch}/{scenario}: {total_vmt:.2f} million miles")

    vmt_by_hour = linkstats_df.groupby('hour')[
                      [f'vmt_{col}' for col in freight_classes]].sum() * scale_up_factor / 1_000_000

    fig, ax = plt.subplots(figsize=(15, 8))
    bar_width = 0.8 / len(freight_classes)
    index = np.arange(len(vmt_by_hour.index))

    for i, col in enumerate(freight_classes):
        ax.bar(index + i * bar_width, vmt_by_hour[f'vmt_{col}'],
               bar_width, label=col.replace('volume_', ''))

    ax.set_xlabel('Hour of Day')
    ax.set_ylabel('VMT (Million Miles)')
    ax.set_title('BEAM VMT by Hour and Vehicle Class')
    ax.set_xticks(index + bar_width * (len(freight_classes) - 1) / 2)
    ax.set_xticklabels(vmt_by_hour.index)
    ax.legend(title='Vehicle Class', bbox_to_anchor=(1.05, 1), loc='upper left')

    plt.tight_layout()

    plot_filename = os.path.join(get_local_work_directory(scenario), f"vmt_by_hour_category_{scenario}.png")
    plt.savefig(plot_filename, bbox_inches='tight')
    # log_and_print(f"Bar plot saved as {plot_filename}")

    return vmt_by_hour


def calc_vmt_from_events(events, scenario):
    # Filter for PathTraversal events and freight vehicles
    pt = events[
        (events['type'] == 'PathTraversal') &
        (events['vehicle'].str.startswith('freight', na=False))
        ].copy()

    # Check for emergency vehicles
    emergency_vehicles = pt[pt['vehicle'].str.contains('-emergency-', na=False)]

    if len(emergency_vehicles) > 0:
        log_and_print(f"This is a bug. Number of emergency vehicles found: {len(emergency_vehicles)}", logging.ERROR)
        log_and_print(f"Sample of emergency vehicles: {emergency_vehicles['vehicle'].head()}", logging.ERROR)

    # log_and_print(f"powertrains: {pt["primaryFuelType"].unique()}")
    # log_and_print(f"vehicletypes: {pt["vehicleType"].unique()}")
    # Calculate total VMT

    log_and_print(
        f"[BEAM] Total number of vehicles: {int(len(pt['vehicle'].unique()) * scale_up_factor)}")
    log_and_print(
        f"[BEAM] Total number of trips: {len(pt)}")
    total_vmt_million_miles = pt['length'].sum() * scale_up_factor / 1609.34 / 1_000_000
    log_and_print(
        f"[BEAM] Total VMT: {total_vmt_million_miles:.2f} million miles")

    vmt_by_category = pt.groupby(['business', 'vehicleCategory'])[
                          'length'].sum() * scale_up_factor / 1609.34 / 1_000_000

    vmt_by_category = vmt_by_category.unstack(level='business')

    # Create bar plot
    ax = vmt_by_category.plot(kind='bar', figsize=(12, 6), width=0.8)
    plt.title(f'VMT by Business and Vehicle Category for {scenario}')
    plt.xlabel('Vehicle Category')
    plt.ylabel('VMT (Million Miles)')
    plt.legend(title='Business')
    plt.xticks(rotation=45, ha='right')

    # Add value labels on the bars
    for container in ax.containers:
        ax.bar_label(container, fmt='%.2f', padding=3)

    plt.tight_layout()
    png_output = os.path.join(get_local_work_directory(scenario), f"vmt_by_category_{scenario}.png")
    plt.savefig(png_output)

    return pt


def convert_payload_to_trips(payload_df, scenario):
    output_file_path = os.path.join(work_dir, "beam-freight", batch, scenario,
                                    f"trips--{scenario.replace("_", "-")}.csv")
    # log_and_print(f"[FRISM] Total rows in payloads: {len(payload_df)}")

    # Count payloads per tour
    payloads_per_tour = payload_df.groupby('tourId').size().reset_index(name='count')
    single_payload_tours = payloads_per_tour[payloads_per_tour["count"] == 1]
    if len(single_payload_tours) > 0:
        log_and_print(f"[FRISM] Number of tours with only one payload row: {len(single_payload_tours)}")

    payload_df = payload_df.sort_values(['tourId', 'sequenceRank'])

    def create_trips(group):
        if len(group) < 2:
            return pd.DataFrame()

        trips = []
        for i in range(len(group) - 1):
            from_row = group.iloc[i]
            to_row = group.iloc[i + 1]

            trip = {}

            for col in payload_df.columns:
                if col == "tourId":
                    trip[col] = from_row[col]
                else:
                    trip[f'{col}_from'] = from_row[col]
                    trip[f'{col}_to'] = to_row[col]

            # Calculate distance
            trip['distance_meter'] = calculate_distance(
                from_row['locationY'], from_row['locationX'],
                to_row['locationY'], to_row['locationX']
            )

            trips.append(trip)

        return pd.DataFrame(trips)

    trips_list = [create_trips(group) for _, group in payload_df.groupby('tourId')]
    non_empty_trips = [trip for trip in trips_list if not trip.empty]

    if not non_empty_trips:
        log_and_print("No trips were created. All groups had fewer than 2 payloads.", logging.WARNING)
        return pd.DataFrame()

    trips_df = pd.concat(non_empty_trips, ignore_index=True)

    log_and_print(f"[FRISM] Total number of trips: {len(trips_df)}")

    total_distance = trips_df['distance_meter'].sum() * scale_up_factor / 1609.34 / 1_000_000
    log_and_print(f"[FRISM] Total distance of all trips: {total_distance:.2f} million miles")

    # Error check
    # Calculate the number of payloads per tour
    # log_and_print("Number of tours with different payload counts:")
    # payloads_per_tour = payload_df.groupby('tourId').size().reset_index(name='payload_count')
    # payload_count_summary = payloads_per_tour.groupby('payload_count').size().reset_index(name='tour_count')
    # payload_count_dict = payload_count_summary.set_index('payload_count')['tour_count'].to_dict()
    # weighted_sum = 0
    # for payload_count, tour_count in payload_count_dict.items():
    #     weighted_sum += (payload_count - 1) * tour_count
    # log_and_print(f"Weighted sum of (payload count - 1) * number of tours: {weighted_sum}")

    trips_df['business'] = trips_df['tourId'].apply(determine_business)
    business_summary = trips_df.groupby('business').agg(
        total_distance_million_miles=pd.NamedAgg(column='distance_meter',
                                                 aggfunc=lambda x: x.sum() * scale_up_factor / 1609.34 / 1_000_000),
        trip_count_thousands=pd.NamedAgg(column='tourId', aggfunc=lambda x: len(x) * scale_up_factor / 1_000)
    ).reset_index()
    log_and_print(f"[FRISM] Trips and distance by business:\n {business_summary}")

    trips_df.to_csv(output_file_path, index=False)
    # log_and_print(f"Trips file saved to {output_file_path}")

    return trips_df


def trips_by_vehicle_class(trips_df, carriers_df, vehicle_types_df):
    # Merge trips with carriers
    merged_df = pd.merge(trips_df, carriers_df, on=['tourId'], how='inner')

    # Merge with vehicle types
    result_df = pd.merge(
        merged_df,
        vehicle_types_df[['vehicleTypeId', 'vehicleCategory']],
        on='vehicleTypeId',
        how='left'
    )

    summary = result_df.groupby('vehicleCategory').agg(
        total_distance_million_miles=pd.NamedAgg(column='distance_meter',
                                                 aggfunc=lambda x: x.sum() * scale_up_factor / 1609.34 / 1_000_000),
        trip_count_thousands=pd.NamedAgg(column='tourId', aggfunc=lambda x: len(x) * scale_up_factor / 1_000)
    ).reset_index()

    # Calculate average trip distance (in miles)
    summary['avg_trip_distance_miles'] = (summary['total_distance_million_miles'] * 1_000_000) / (
            summary['trip_count_thousands'] * 1000)

    # Sort by trip count in descending order
    summary = summary.sort_values('trip_count_thousands', ascending=False)

    # Calculate percentages
    total_trips = summary['trip_count_thousands'].sum()
    total_distance = summary['total_distance_million_miles'].sum()
    summary['trip_percentage'] = (summary['trip_count_thousands'] / total_trips) * 100
    summary['distance_percentage'] = (summary['total_distance_million_miles'] / total_distance) * 100

    # Round numeric columns
    summary = summary.round({
        'total_distance_million_miles': 3,
        'trip_count_thousands': 2,
        'avg_trip_distance_miles': 2,
        'trip_percentage': 2,
        'distance_percentage': 2
    })

    # Reorder columns
    summary = summary[
        ['vehicleCategory', 'trip_count_thousands', 'trip_percentage', 'total_distance_million_miles',
         'distance_percentage', 'avg_trip_distance_miles']]

    pd.set_option('display.max_columns', 10)
    log_and_print(f'[FRISM] Trips and distance by vehicle category: \n {summary}')

    return summary


def get_local_work_directory(scenario):
    local_work_directory = f'{work_dir}/beam-runs/{batch}/{scenario}/'
    return local_work_directory


def determine_powertrain(row):
    if row['primaryFuelType'].lower() == 'electricity':
        if pd.isnull(row['secondaryFuelType']) or row['secondaryFuelType'] == '':
            return 'BEV'
        else:
            return 'PHEV'
    elif row['primaryFuelType'].lower() == 'hydrogen':
        return 'H2FC'
    else:
        return row['primaryFuelType']


def determine_business(tour_id):
    if pd.isnull(tour_id):
        return None
    tour_id_lower = str(tour_id).lower()
    if 'b2c' in tour_id_lower:
        return 'b2c'
    elif 'b2b' in tour_id_lower:
        return 'b2b'
    else:
        return None


def calculate_distance(lat1, lon1, lat2, lon2):
    """
    Calculate the great circle distance between two points
    on the earth (specified in decimal degrees)
    """
    coord1 = (lat1, lon1)
    coord2 = (lat2, lon2)

    # Calculate distance in meters
    distance_meters = distance.distance(coord1, coord2).meters

    return distance_meters


def setup_logging(log_file):
    logging.basicConfig(level=logging.INFO,
                        format='%(asctime)s - %(levelname)s - %(message)s',
                        handlers=[logging.FileHandler(log_file, mode='w'),
                                  logging.StreamHandler()])


def log_and_print(message, level=logging.INFO):
    # print(message)
    if level == logging.INFO:
        logging.info(message)
    elif level == logging.WARNING:
        logging.warning(message)
    elif level == logging.ERROR:
        logging.error(message)


if __name__ == "__main__":
    main()
