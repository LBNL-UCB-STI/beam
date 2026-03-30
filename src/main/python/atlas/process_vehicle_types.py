import os
import re
import sys
import pandas as pd

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.files_utils import sanitize_name


def filter_vehicles_by_year(file_path, max_year=2018):
    """
    Reads a vehicle types CSV file using pandas and filters out vehicles with IDs
    where the year is greater than max_year.

    Args:
        file_path (str): Path to the CSV file
        max_year (int): Maximum year to include (default: 2018)

    Returns:
        pandas.DataFrame: DataFrame containing filtered vehicle data
    """
    # Read the CSV file
    df = pd.read_csv(file_path)

    # Extract year from vehicleTypeId and create a filter
    def extract_year(vehicle_id):
        match = re.match(r'^(\d{4})_', str(vehicle_id))
        if match:
            return int(match.group(1))
        return None

    # Apply the year extraction to create a new column
    df['year'] = df['vehicleTypeId'].apply(extract_year)

    # Filter out vehicles with year > max_year
    filtered_df = df[df['year'].isna() | (df['year'] <= max_year)]

    # Drop the temporary year column
    filtered_df = filtered_df.drop(columns=['year'])

    return filtered_df


def map_vehicle_types(atlas_2017, atlas_vehicles_2023, vehicle_types_2023):
    """
    Maps vehicle types from 2023 to 2017 based on bodytype, modelyear, and adopt_fuel.

    Args:
        atlas_2017 (pd.DataFrame): DataFrame containing 2017 vehicle data with proportion column
        atlas_vehicles_2023 (pd.DataFrame): DataFrame containing 2023 vehicle data with proportion column
        vehicle_types_2023 (pd.DataFrame): DataFrame containing 2023 vehicle types data

    Returns:
        tuple: (new_vehicle_types_2017_df, vehicle_id_map)
            - new_vehicle_types_2017_df: DataFrame with mapped 2017 vehicle types
            - vehicle_id_map: Dictionary mapping old vehicle IDs to new vehicle IDs
    """
    # Sort DataFrames by proportion
    atlas_vehicles_2023.sort_values(by='proportion', ascending=False, inplace=True)
    atlas_2017.sort_values(by='proportion', ascending=False, inplace=True)

    # Create a copy of vehicles_2017 that we'll modify as we go
    remaining_2017 = atlas_2017.copy()

    # Create a copy of vehicles_2023 to store the results
    result_df = atlas_vehicles_2023.copy()

    # Add new columns for the mapped values
    result_df["mapped_bodytype"] = None
    result_df["mapped_modelyear"] = None
    result_df["mapped_adopt_fuel"] = None
    result_df["routee"] = None

    # Filter for IDs that start with a year <= 2018
    atlas_vehicles_by_2018 = atlas_vehicles_2023[
        atlas_vehicles_2023["vehicleTypeId"].str.extract(r"^(\d{4})", expand=False).astype(float) <= 2018
        ]

    # Map vehicle types
    for row in atlas_vehicles_2023.itertuples():
        if len(remaining_2017) > 0:
            remaining_2017_reset = remaining_2017.reset_index()
            weights = remaining_2017_reset['proportion']

            sampled_idx = remaining_2017_reset.sample(n=1, weights=weights).index[0]
            sampled_row = remaining_2017_reset.iloc[sampled_idx]
            original_idx = sampled_row['index']

            result_df.loc[row.Index, "mapped_bodytype"] = sampled_row.bodytype
            result_df.loc[row.Index, "mapped_modelyear"] = sampled_row.modelyear
            result_df.loc[row.Index, "mapped_adopt_fuel"] = sampled_row.adopt_fuel

            remaining_2017 = remaining_2017.drop(original_idx)

            bodytype_mask = atlas_vehicles_by_2018["bodytype"] == sampled_row.bodytype
            modelyear_mask = atlas_vehicles_by_2018["modelyear"] <= sampled_row.modelyear
            fuel_mask = atlas_vehicles_by_2018["adopt_fuel"] == sampled_row.adopt_fuel

            match_conditions = [
                bodytype_mask & modelyear_mask & fuel_mask,  # All criteria
                bodytype_mask & fuel_mask,  # Body type and fuel
                fuel_mask,  # Just fuel
                bodytype_mask,  # Just body type
                pd.Series(True, index=atlas_vehicles_by_2018.index)  # Everything remaining
            ]

            match = None
            for condition in match_conditions:
                temp_match = atlas_vehicles_by_2018[condition]
                if not temp_match.empty:
                    match = temp_match
                    break

            match_sampled_idx = match.sample(n=1, weights=match['proportion']).index[0]
            match_sampled_row = atlas_vehicles_by_2018.loc[match_sampled_idx]

            result_df.loc[row.Index, "routee"] = match_sampled_row["vehicleTypeId"]
        else:
            print("No 2017 vehicles available for matching")
            break

    # Generate new vehicle type IDs
    result_df["oldVehicleTypeId"] = result_df["vehicleTypeId"]
    result_df["vehicleTypeId"] = result_df.apply(
        lambda x: f"{str(int(x['mapped_modelyear']))}"
                  f"{sanitize_name(x['mapped_bodytype']).replace('_', '').title()}"
                  f"{sanitize_name(x['mapped_adopt_fuel']).replace('_', '').title()}--"
                  f"{sanitize_name(x['routee']).replace('_', '')}", axis=1
    )

    # Create new vehicle types based on mapped values
    vehicle_id_map = {}
    new_rows = []
    for row in result_df.itertuples():
        new_row = vehicle_types_2023[vehicle_types_2023['vehicleTypeId'] == row.routee].iloc[0].copy()
        new_row["oldVehicleTypeId"] = row.oldVehicleTypeId
        new_row['vehicleTypeId'] = row.vehicleTypeId
        new_row["bodytype"] = row.mapped_bodytype
        new_row["modelyear"] = row.mapped_modelyear
        new_row["adopt_fuel"] = row.mapped_adopt_fuel
        vehicle_id_map[row.oldVehicleTypeId] = row.vehicleTypeId
        new_rows.append(new_row)

    # Create DataFrame for new vehicle types
    new_vehicle_types_2017_df = pd.DataFrame(new_rows)
    new_vehicle_types_2017_df.drop(columns=["oldVehicleTypeId"], inplace=True)

    return new_vehicle_types_2017_df, vehicle_id_map


def turn_atlas_route_2023_baseline_into_2017_baseline(
        atlas_2017_file,
        vehicles_2023_file,
        atlas_routee_mapping_file,
        vehicle_types_2023_file,
        output_vehicle_types_2017_file,
        output_vehicles_2017_file):
    # Load and prepare 2017 vehicle data
    atlas_2017_raw = pd.read_csv(atlas_2017_file)
    atlas_2017 = atlas_2017_raw.groupby(['bodytype', 'modelyear', 'adopt_fuel']).size().reset_index(name='count')
    atlas_2017_sum = atlas_2017["count"].sum()
    atlas_2017["proportion"] = atlas_2017["count"] / atlas_2017_sum

    # Load and prepare 2023 vehicle data
    vehicles_2023_raw = pd.read_csv(vehicles_2023_file)
    vehicles_2023_bike = vehicles_2023_raw[vehicles_2023_raw['vehicleTypeId'] == "BIKE-DEFAULT"].copy()
    vehicles_2023_no_bike = vehicles_2023_raw[vehicles_2023_raw['vehicleTypeId'] != "BIKE-DEFAULT"].copy()

    # Load mapping data
    atlas_routee_mapping = pd.read_csv(atlas_routee_mapping_file)

    # Process 2023 vehicles data
    atlas_vehicles_2023 = (pd.merge(vehicles_2023_no_bike, atlas_routee_mapping, on="vehicleTypeId", how="left")
                           .groupby('vehicleTypeId').agg(
        {
            'vehicleTypeId': lambda x: len(x),  # This will be renamed to avoid conflict
            'bodytype': 'first',
            'modelyear': 'first',
            'adopt_fuel': 'first'
        }
    ).rename(columns={'vehicleTypeId': 'count'}).reset_index())

    atlas_vehicles_2023_sum = atlas_vehicles_2023["count"].sum()
    atlas_vehicles_2023["proportion"] = atlas_vehicles_2023["count"] / atlas_vehicles_2023_sum

    # Load vehicle types data
    vehicle_types_2023 = pd.read_csv(vehicle_types_2023_file)

    # Map vehicle types from 2023 to 2017
    new_vehicle_types_2017_df, vehicle_id_map = map_vehicle_types(
        atlas_2017, atlas_vehicles_2023, vehicle_types_2023
    )
    new_vehicle_types_2017_df["bodytype"] = new_vehicle_types_2017_df["bodytype"].str.capitalize()
    new_vehicle_types_2017_df["adopt_fuel"] = new_vehicle_types_2017_df["adopt_fuel"].str.capitalize()
    new_vehicle_types_2017_df["model_year_group"] = new_vehicle_types_2017_df["modelyear"].apply(
        lambda year: (lambda y, bins:
                      str(bins[0]) if y <= bins[0] else
                      next((str(bins[i + 1]) for i in range(len(bins) - 1) if y <= bins[i + 1]), str(bins[-1]))
                      )(year, sorted([1993, 2006, 2018]))
    )

    vehicle_types_2023_non_car = vehicle_types_2023[vehicle_types_2023["vehicleCategory"] != "Car"]
    new_vehicle_types_2017_df = pd.concat([new_vehicle_types_2017_df, vehicle_types_2023_non_car])

    # Save new vehicle types
    new_vehicle_types_2017_df.to_csv(output_vehicle_types_2017_file, index=False)

    # Create and save mapped vehicles file
    vehicles_2017_no_bike = vehicles_2023_no_bike.copy()
    vehicles_2017_no_bike["oldVehicleTypeId"] = vehicles_2017_no_bike["vehicleTypeId"]
    vehicles_2017_no_bike["vehicleTypeId"] = vehicles_2017_no_bike["oldVehicleTypeId"].map(vehicle_id_map)
    vehicles_2017_no_bike.drop(columns=["oldVehicleTypeId"], inplace=True)
    vehicles_2017_new = pd.concat([vehicles_2017_no_bike, vehicles_2023_bike])
    vehicles_2017_new.to_csv(output_vehicles_2017_file, index=False)


if __name__ == "__main__":
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    turn_atlas_route_2023_baseline_into_2017_baseline(
        atlas_2017_file=f"{work_dir}/atlas/vehicles_2017.csv",
        vehicles_2023_file=f"{work_dir}/beam-pax/vehicles--atlas--2023-Baseline.csv.gz",
        atlas_routee_mapping_file=f"{work_dir}/atlas/vehicle_type_mapping_baseline.csv",
        vehicle_types_2023_file=f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2023-Baseline.csv",
        output_vehicle_types_2017_file = f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2017-Baseline.csv",
        output_vehicles_2017_file = f"{work_dir}/beam-pax/vehicles--atlas--2017-Baseline.csv.gz"
    )

