import os
import re
import pandas as pd


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


if __name__ == "__main__":
    file_path = os.path.expanduser(
        "~/Workspace/Simulation/sfbay/vehicle-tech/vehicleTypes--atlas--baseline-projection.csv")
    filtered_df = filter_vehicles_by_year(file_path, 2023)
    print(f"Found {len(filtered_df)} vehicles after filtering")

    # Print some examples of what was kept
    for i, (index, row) in enumerate(filtered_df.head().iterrows()):
        print(f"{i + 1}. Vehicle ID: {row.get('vehicleTypeId')}")

    # Save the filtered data to a new CSV file
    dir_path = os.path.dirname(file_path)
    output_path = f"{dir_path}/vehicleTypes--atlas--2023-Baseline.csv"
    filtered_df.to_csv(output_path, index=False)
    print(f"Filtered data saved to: {output_path}")