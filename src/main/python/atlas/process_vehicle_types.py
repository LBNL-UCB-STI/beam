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


def extract_vehicle_components(vehicle_id, id_type):
    """
    Extracts components (year, body type, fuel type) from vehicle IDs.

    Args:
        vehicle_id (str): The vehicle ID (Atlas or RouteE format)
        id_type (str): Either 'atlas' or 'routee'

    Returns:
        dict: A dictionary with extracted components
    """
    if id_type == 'atlas':
        # Atlas format: YYYY-bodytype-fueltype
        year, body_type, fuel_type = vehicle_id.split('-')
        return {
            'year': year,
            'body_type': body_type,
            'fuel_type': fuel_type
        }
    elif id_type == 'routee':
        # RouteE format: YYYY_Make_Model_Details
        parts = vehicle_id.split('_')
        year = parts[0]
        make = parts[1]
        model_parts = parts[2:]

        # Determine body type based on model
        model = '_'.join(model_parts).lower()
        if 'pickup' in model or 'silverado' in model or 'f150' in model:
            body_type = 'pickup'
        elif 'highlander' in model or 'qx60' in model:
            body_type = 'suv'
        elif 'quest' in model or 'sienna' in model:
            body_type = 'van'
        else:
            body_type = 'car'

        # Determine fuel type based on model
        if 'hybrid' in vehicle_id.lower() or 'eassist' in vehicle_id.lower():
            fuel_type = 'hybrid'
        elif 'volt' in vehicle_id.lower() or 'phev' in vehicle_id.lower():
            fuel_type = 'phev'
        elif 'tesla' in vehicle_id.lower() or 'model_s' in vehicle_id.lower() or 'model_x' in vehicle_id.lower():
            fuel_type = 'ev'
        else:
            fuel_type = 'conv'

        return {
            'year': year,
            'make': make,
            'body_type': body_type,
            'fuel_type': fuel_type
        }
    return None


def generate_mapping_from_csv(atlas_vehicles, routee_vehicles):
    """
    Generate a mapping between Atlas and RouteE vehicle types from CSV files.

    Args:
        atlas_vehicles (Dataframe):
        routee_vehicles (Dataframe):

    Returns:
        dict: A dictionary mapping Atlas vehicle IDs to RouteE vehicle IDs
    """
    # Create mapping
    mapping = {}

    for atlas_id in atlas_vehicles:
        best_match = None
        best_score = -1

        year, body_type, fuel_type = atlas_id.split('-')

        for routee_id in routee_vehicles:
            routee_components = extract_vehicle_components(routee_id, 'routee')

            score = 0

            # Year match (exact is best, but close years are acceptable)
            year_diff = abs(int(year) - int(routee_components['year']))
            if year_diff == 0:
                score += 3  # Exact year match
            elif year_diff <= 2:
                score += 2  # Close year match
            elif year_diff <= 5:
                score += 1  # Somewhat close

            # Fuel type match (prioritized with higher weight)
            if fuel_type == routee_components['fuel_type']:
                score += 5  # Exact fuel type match (increased weight)
            elif (
                    (fuel_type == 'hybrid' and routee_components['fuel_type'] == 'phev') or
                    (fuel_type == 'phev' and routee_components['fuel_type'] == 'hybrid')
            ):
                score += 2  # Similar alternative fuel types (increased weight)

            # Body type match (lower priority than fuel type)
            if body_type == routee_components['body_type']:
                score += 3  # Exact body type match

            # Update best match if this one is better
            if score > best_score:
                best_score = score
                best_match = routee_id

        if best_match:
            mapping[atlas_id] = best_match

    return mapping

if __name__ == "__main__":
    work_dir = os.path.expanduser("~/Workspace/Simulation/sfbay")
    # vehicles_types_2050 = f"{work_dir}/vehicleTypes--atlas--baseline-projection.csv"
    # filtered_df = filter_vehicles_by_year(vehicles_types_2050, 2023)
    atlas_2017_file = f"{work_dir}/atlas/vehicles_2017.csv"
    vehicles_2023_file = f"{work_dir}/beam-pax/2023-Baseline/vehicles--atlas--2023-Baseline.csv.gz"
    atlas_routee_mapping_file = f"{work_dir}/atlas/vehicle_type_mapping_baseline.csv"
    vehicle_types_2023_file = f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2023-Baseline.csv"

    output_vehicle_types_2023_file = f"{work_dir}/vehicle-tech/vehicleTypes--atlas--2017-Baseline.csv"
    output_vehicles_2017_file = f"{work_dir}/beam-pax/2023-Baseline/vehicles--atlas--2017-Baseline.csv.gz"

    atlas_2017_raw = pd.read_csv(atlas_2017_file)
    atlas_2017 = atlas_2017_raw.groupby(['bodytype', 'modelyear', 'adopt_fuel']).size().reset_index(name='count')
    atlas_2017_sum = atlas_2017["count"].sum()
    atlas_2017["proportion"] = atlas_2017["count"] / atlas_2017_sum

    vehicles_2023_raw = pd.read_csv(vehicles_2023_file)
    vehicles_2023_bike = vehicles_2023_raw[vehicles_2023_raw['vehicleTypeId']=="BIKE-DEFAULT"].copy()
    vehicles_2023_no_bike = vehicles_2023_raw[vehicles_2023_raw['vehicleTypeId']!="BIKE-DEFAULT"].copy()
    atlas_routee_mapping = pd.read_csv(atlas_routee_mapping_file)
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

    for row in atlas_vehicles_2023.itertuples():
        if len(remaining_2017) > 0:
            remaining_2017_reset = remaining_2017.reset_index()
            weights = remaining_2017_reset['proportion']

            # Sample one row based on weights
            sampled_idx = remaining_2017_reset.sample(n=1, weights=weights).index[0]
            sampled_row = remaining_2017_reset.iloc[sampled_idx]

            # Store the original index to use for dropping from remaining_2017
            original_idx = sampled_row['index']  # This is the original index stored as a column after reset_index

            for row in atlas_vehicles_2023.itertuples():
                if len(remaining_2017_reset) == 0:
                    print(f"No more 2017 vehicles to match with {row.Index}")
                    break

                # Create all masks upfront
                bodytype_mask = remaining_2017_reset["bodytype"] == row.bodytype
                modelyear_mask = remaining_2017_reset["modelyear"] <= row.modelyear
                fuel_mask = remaining_2017_reset["adopt_fuel"] == row.adopt_fuel

                # Try different combinations in order of specificity
                match_conditions = [
                    bodytype_mask & modelyear_mask & fuel_mask,  # All criteria
                    bodytype_mask & fuel_mask,  # Body type and fuel
                    fuel_mask,  # Just fuel
                    bodytype_mask,  # Just body type
                    pd.Series(True, index=remaining_2017_reset.index)  # Everything remaining
                ]

                # Find the first non-empty match
                match = None
                for condition in match_conditions:
                    temp_match = remaining_2017_reset[condition]
                    if not temp_match.empty:
                        match = temp_match
                        break

                # Sample one row based on weights (adjusted for the filtered DataFrame)
                sampled_idx = match.sample(n=1, weights=match['proportion']).index[0]
                sampled_row = remaining_2017_reset.loc[sampled_idx]

                # Assign the mapped values to the specific row in result_df
                result_df.loc[row.Index, "mapped_bodytype"] = sampled_row.bodytype
                result_df.loc[row.Index, "mapped_modelyear"] = sampled_row.modelyear
                result_df.loc[row.Index, "mapped_adopt_fuel"] = sampled_row.adopt_fuel

                # Remove the used row from remaining_2017_reset
                remaining_2017_reset = remaining_2017_reset.drop(sampled_idx)
        else:
            print("No 2017 vehicles available for matching")
            break

    result_df["atlasId"] = result_df.apply(
        lambda x: f"{str(int(x['mapped_modelyear']))}-"
                  f"{sanitize_name(x['mapped_bodytype']).replace("_","").title()}-"
                  f"{sanitize_name(x['mapped_adopt_fuel']).replace("_","").title()}", axis=1
    )

    unique_atlas_ids_by_2017 = result_df["atlasId"].unique()
    # Filter for IDs that start with a year <= 2017
    unique_routee_ids_by_2018 = [vid for vid in result_df["vehicleTypeId"].unique() if
                                  re.match(r"^(\d{4})", vid) and
                                  int(re.match(r"^(\d{4})", vid).group(1)) <= 2018]

    mapping = generate_mapping_from_csv(unique_atlas_ids_by_2017, unique_routee_ids_by_2018)
    result_df["routee"] = result_df["atlasId"].map(mapping)
    result_df["oldVehicleTypeId"] = result_df["vehicleTypeId"]
    result_df["vehicleTypeId"] = result_df.apply(
        lambda x: f"{x['atlasId'].replace("-","")}--"
                  f"{sanitize_name(x['routee']).replace("_","")}", axis=1
    )

    vehicle_types_2023 = pd.read_csv(vehicle_types_2023_file)
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

    new_vehicle_types_2017_df = pd.DataFrame(new_rows)
    new_vehicle_types_2017_df.drop(columns=["oldVehicleTypeId"], inplace=True)
    new_vehicle_types_2017_df.to_csv(output_vehicle_types_2023_file, index=False)

    vehicles_2017_no_bike = vehicles_2023_no_bike.copy()
    vehicles_2017_no_bike["oldVehicleTypeId"] = vehicles_2017_no_bike["vehicleTypeId"]
    vehicles_2017_no_bike["vehicleTypeId"] = vehicles_2017_no_bike["oldVehicleTypeId"].map(vehicle_id_map)
    vehicles_2017_no_bike.drop(columns=["oldVehicleTypeId"], inplace=True)
    vehicles_2017_new = pd.concat([vehicles_2017_no_bike, vehicles_2023_bike])
    vehicles_2017_new.to_csv(output_vehicles_2017_file, index=False)




