import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import os
from scipy import stats
from datetime import datetime, timedelta
import seaborn as sns



# Load the CSV file
def generate_duration_histogram(durations, bins=50):
    # Convert seconds to minutes
    durations_minutes = durations / 60

    # Create a figure
    plt.figure(figsize=(12, 6))

    # Generate histogram
    plt.hist(durations_minutes, bins=bins, alpha=0.75, color='steelblue', edgecolor='black')

    # Add title and labels
    plt.title('Distribution of Operation Durations', fontsize=14)
    plt.xlabel('Operation Duration (minutes)', fontsize=12)
    plt.ylabel('Frequency', fontsize=12)

    # Add grid
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Calculate and display statistics
    mean_duration = durations_minutes.mean()
    median_duration = durations_minutes.median()

    # Add text with statistics
    stats_text = f'Mean: {mean_duration:.2f} minutes\nMedian: {median_duration:.2f} minutes'
    plt.annotate(stats_text, xy=(0.95, 0.95), xycoords='axes fraction',
                 fontsize=10, ha='right', va='top',
                 bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.7))

    # Show the plot
    plt.tight_layout()
    plt.savefig('operation_duration_histogram_minutes.png')
    plt.show()

    print(f"Histogram saved as 'operation_duration_histogram_minutes.png'")

    # Return basic statistics
    return {
        'mean': mean_duration,
        'median': median_duration,
        'min': durations_minutes.min(),
        'max': durations_minutes.max()
    }


def plot_duration_vs_weight(df, duration_col, weight_col):
    """
    Create a scatter plot showing the relationship between stop durations and weight.

    Parameters:
    -----------
    df : pandas.DataFrame
        The DataFrame containing the data
    duration_col : str
        Column name for operation duration in seconds
    weight_col : str
        Column name for weight in pounds

    Returns:
    --------
    dict
        Dictionary containing correlation statistics
    """
    # Convert seconds to minutes
    durations_minutes = df[duration_col] / 60
    weights_lbs = df[weight_col]

    # Create a figure
    plt.figure(figsize=(12, 8))

    # Generate scatter plot
    plt.scatter(durations_minutes, weights_lbs, alpha=0.5, color='steelblue', edgecolor='none')

    # Add title and labels
    plt.title('Relationship Between Operation Duration and Weight', fontsize=14)
    plt.xlabel('Operation Duration (minutes)', fontsize=12)
    plt.ylabel('Weight (lbs)', fontsize=12)

    # Add grid
    plt.grid(linestyle='--', alpha=0.7)

    # Calculate correlation coefficient and p-value
    corr, p_value = stats.pearsonr(durations_minutes, weights_lbs)

    # Calculate and plot best fit line
    slope, intercept = np.polyfit(durations_minutes, weights_lbs, 1)
    x_line = np.array([durations_minutes.min(), durations_minutes.max()])
    y_line = slope * x_line + intercept
    plt.plot(x_line, y_line, color='red', linewidth=2,
             label=f'y = {slope:.2f}x + {intercept:.2f}')

    # Add text with statistics
    stats_text = (f'Pearson Correlation: {corr:.4f}\n'
                  f'P-value: {p_value:.4e}\n'
                  f'Slope: {slope:.4f}\n'
                  f'Intercept: {intercept:.2f}')

    plt.annotate(stats_text, xy=(0.05, 0.95), xycoords='axes fraction',
                 fontsize=10, ha='left', va='top',
                 bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.7))

    # Add legend
    plt.legend()

    # Show the plot
    plt.tight_layout()
    plt.savefig('duration_vs_weight.png')
    plt.show()

    print(f"Scatter plot saved as 'duration_vs_weight.png'")

    # Return correlation statistics
    return {
        'correlation': corr,
        'p_value': p_value,
        'slope': slope,
        'intercept': intercept
    }


def load_and_process_data(trip_data_path, vehicle_data_path):
    """
    Load and process the Austin Commercial Vehicle Survey data
    """
    print("Loading data files...")

    # First inspect the files to determine sheet names
    try:
        trip_xl = pd.ExcelFile(trip_data_path)
        trip_sheets = trip_xl.sheet_names
        print(f"Trip data sheets: {trip_sheets}")

        vehicle_xl = pd.ExcelFile(vehicle_data_path)
        vehicle_sheets = vehicle_xl.sheet_names
        print(f"Vehicle data sheets: {vehicle_sheets}")

        # Use the first sheet if available for both files
        if trip_sheets:
            trip_sheet = trip_sheets[0]
            trip_data = pd.read_excel(trip_data_path, sheet_name=trip_sheet)
            print(f"Loaded {len(trip_data)} trip records from sheet '{trip_sheet}'")
            print(f"Sample columns: {list(trip_data.columns)[:5]}...")
        else:
            raise ValueError("No sheets found in trip data file")

        if vehicle_sheets:
            vehicle_sheet = vehicle_sheets[0]
            vehicle_data = pd.read_excel(vehicle_data_path, sheet_name=vehicle_sheet)
            print(f"Loaded {len(vehicle_data)} vehicle records from sheet '{vehicle_sheet}'")
            print(f"Sample columns: {list(vehicle_data.columns)[:5]}...")
        else:
            print("No sheets found in vehicle data file, proceeding with trip data only")
            return trip_data

        # Try to identify a common column for merging
        vehicle_id_cols = [col for col in trip_data.columns if 'vehicle' in col.lower() and 'id' in col.lower()]
        veh_id_cols = [col for col in vehicle_data.columns if 'vehicle' in col.lower() and 'id' in col.lower()]

        if vehicle_id_cols and veh_id_cols:
            # Merge on vehicle ID
            merged_data = pd.merge(
                trip_data,
                vehicle_data,
                left_on=vehicle_id_cols[0],
                right_on=veh_id_cols[0],
                how="left",
                suffixes=("_trip", "_vehicle")
            )
            print(f"Merged data shape: {merged_data.shape}")
            return merged_data
        else:
            print("No common vehicle ID columns found for merging, returning trip data only")
            return trip_data

    except Exception as e:
        print(f"Error loading data: {e}")
        # Try to return whatever data we can
        try:
            return pd.read_excel(trip_data_path)
        except:
            raise ValueError("Could not load any data from the provided files")


def analyze_durations_by_key(df, key_column, duration_column, output_dir='.', prefix=''):
    """
    Analyze and visualize stop durations by a key column (e.g., activity type, land use, vehicle type)

    Parameters:
    -----------
    df : pandas.DataFrame
        DataFrame containing the data
    key_column : str
        Column name to group by (e.g., 'ActivityType', 'PlaceType')
    duration_column : str
        Column name for duration data
    output_dir : str
        Directory to save output files
    prefix : str
        Prefix for output filenames

    Returns:
    --------
    pandas.DataFrame
        DataFrame with aggregated statistics
    """
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    # Group by key column and calculate statistics
    stats = df.groupby(key_column)[duration_column].agg([
        'count', 'mean', 'median', 'std', 'min', 'max'
    ]).reset_index()

    # Create visualization
    plt.figure(figsize=(12, 6))
    plt.bar(stats[key_column].astype(str), stats['mean'], color='steelblue')
    plt.title(f'Average Duration by {key_column}', fontsize=14)
    plt.xlabel(key_column, fontsize=12)
    plt.ylabel(f'Average Duration (minutes)', fontsize=12)
    plt.xticks(rotation=45, ha='right')
    plt.grid(axis='y', linestyle='--', alpha=0.7)
    plt.tight_layout()

    # Save the plot
    output_path = os.path.join(output_dir, f'{prefix}_{key_column.lower()}_duration_analysis.png')
    plt.savefig(output_path)
    plt.close()

    # Save the data
    csv_path = os.path.join(output_dir, f'{prefix}_{key_column.lower()}_duration_analysis.csv')
    stats.to_csv(csv_path, index=False)

    print(f"Analysis for {key_column} saved to {output_dir}")

    return stats


def analyze_operation_durations(austin_data, frism_data, output_dir='./analysis_results'):
    """
    Analyze operation durations from both Austin CV Survey data and FRISM plans

    Parameters:
    -----------
    austin_data : pandas.DataFrame
        Processed Austin Commercial Vehicle Survey data with stop durations
    frism_data : pandas.DataFrame
        FRISM plan data with operation durations
    output_dir : str
        Directory to save output files

    Returns:
    --------
    dict
        Dictionary with analysis results
    """
    print("Analyzing operation durations from both datasets...")

    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    results = {}

    # Convert Austin durations to seconds for consistency
    austin_data['DurationInSec'] = austin_data['StopDuration'] * 60

    # Generate histograms for both datasets
    austin_stats = generate_duration_histogram(
        austin_data['DurationInSec'],
    )

    frism_stats = generate_duration_histogram(
        frism_data['operationDurationInSec'],
    )

    # Compare distributions
    plt.figure(figsize=(12, 6))

    # Plot kernel density estimates
    sns.kdeplot(austin_data['DurationInSec'] / 60, label='Austin CV Survey', color='blue')
    sns.kdeplot(frism_data['operationDurationInSec'] / 60, label='FRISM Plans', color='red')

    plt.title('Comparison of Operation Duration Distributions', fontsize=14)
    plt.xlabel('Duration (minutes)', fontsize=12)
    plt.ylabel('Density', fontsize=12)
    plt.grid(linestyle='--', alpha=0.7)
    plt.legend()
    plt.tight_layout()

    # Save the comparison plot
    comparison_path = os.path.join(output_dir, 'duration_distribution_comparison.png')
    plt.savefig(comparison_path)
    plt.close()

    # Store statistics
    results['austin_stats'] = austin_stats
    results['frism_stats'] = frism_stats

    # If weight data is available in both datasets, analyze correlations
    if 'weightInlb' in frism_data.columns and any('weight' in col.lower() for col in austin_data.columns):
        # Identify weight column in Austin data
        weight_col = next(col for col in austin_data.columns if 'weight' in col.lower())

        # Analyze correlation for each dataset
        if weight_col:
            austin_corr = plot_duration_vs_weight(
                austin_data,
                'DurationInSec',
                weight_col,
            )
            results['austin_correlation'] = austin_corr

    # Create summary report
    with open(os.path.join(output_dir, 'duration_analysis_summary.txt'), 'w') as f:
        f.write("Operation Duration Analysis Summary\n")
        f.write("==================================\n\n")

        f.write("Austin CV Survey Statistics:\n")
        for key, value in austin_stats.items():
            f.write(f"  {key.capitalize()}: {value:.2f} minutes\n")

        f.write("\nFRISM Plans Statistics:\n")
        for key, value in frism_stats.items():
            f.write(f"  {key.capitalize()}: {value:.2f} minutes\n")

        f.write("\nComparison:\n")
        f.write(f"  Mean difference: {frism_stats['mean'] - austin_stats['mean']:.2f} minutes\n")
        f.write(f"  Median difference: {frism_stats['median'] - austin_stats['median']:.2f} minutes\n")

    print(f"Operation duration analysis complete. Results saved to {output_dir}")

    return results


def calculate_stop_durations(df, key_cols):
    """
    Calculate stop durations from arrival and departure times
    """
    print("Calculating stop durations...")

    if 'arrival_time' in key_cols and 'departure_time' in key_cols:
        arrival_col = key_cols['arrival_time']
        departure_col = key_cols['departure_time']

        # Calculate duration in minutes
        df['stop_duration'] = (df[departure_col] - df[arrival_col]).dt.total_seconds() / 60

        # Filter out invalid durations
        valid_durations = df[df['stop_duration'] >= 0].shape[0]
        print(f"  Valid stop durations: {valid_durations} out of {df.shape[0]} records")

        # Remove negative durations
        df = df[df['stop_duration'] >= 0].copy()
    else:
        print("  Cannot calculate stop durations - missing arrival or departure time columns")

    return df


def estimate_idling_durations(df, key_cols):
    """
    Estimate idling durations based on activity type and other factors
    """
    print("Estimating idling durations...")

    if 'stop_duration' not in df.columns:
        print("  Cannot estimate idling durations - missing stop duration column")
        return df

    # Get column names from key_cols dictionary
    activity_type_col = key_cols.get('activity_type')
    place_type_col = key_cols.get('place_type')
    vehicle_class_col = key_cols.get('vehicle_class')
    commercial_type_col = key_cols.get('commercial_type')

    # Default idling percentages
    df['idling_percent'] = 0.3  # Default 30% idling time

    # If we have activity type information, adjust idling percentages
    if activity_type_col and activity_type_col in df.columns:
        print(f"  Adjusting idling times based on activity types")
        # Get unique activity types to understand the coding
        unique_activities = df[activity_type_col].dropna().unique()
        print(f"  Unique activity types: {unique_activities[:10]}")

        # Example activity type mapping - customize based on your data
        # This is just a placeholder for demonstration
        activity_idling = {}

        # If numerical data, use common activity codes
        if pd.api.types.is_numeric_dtype(df[activity_type_col]):
            # Example mapping based on common activity codes
            activity_idling = {
                1: 0.1,  # Base Location / Return to Base Location
                2: 0.8,  # Vehicle Maintenance (fuel, oil, etc)
                3: 0.9,  # Driver Needs (lunch, restroom, etc)
                4: 0.4,  # Deliver Cargo
                5: 0.4,  # Pick up Cargo
                6: 0.4,  # Deliver and Pick up Cargo
                7: 0.3,  # Government Related Service
                8: 0.2,  # Installation / Maintenance / Repair Service
                9: 0.3,  # Sales / Professional Service
                96: 0.3  # Other Activity
            }

        # Apply the activity mapping for known values
        mask = df[activity_type_col].isin(activity_idling.keys())
        if mask.any():
            df.loc[mask, 'idling_percent'] = df.loc[mask, activity_type_col].map(activity_idling)

    # Adjust based on place type if available
    if place_type_col and place_type_col in df.columns:
        print(f"  Adjusting idling times based on place types")
        # Get unique place types
        unique_places = df[place_type_col].dropna().unique()
        print(f"  Unique place types: {unique_places[:10]}")

        # Example place type adjustments
        place_adjustment = {}

        # Create mappings based on place type
        if pd.api.types.is_numeric_dtype(df[place_type_col]):
            # Example mapping based on common place codes from the survey
            place_adjustment = {
                1: 0.9,  # Office Building (Non-Government)
                2: 0.9,  # Retail / Shopping
                3: 0.8,  # Industrial / Manufacturing
                4: 0.9,  # Medical / Hospital
                5: 0.8,  # Education (K-12)
                6: 0.8,  # Education (college)
                7: 0.9,  # Government
                8: 0.7,  # Residential
                11: 0.7,  # Warehouse
                12: 0.7,  # Distribution center
                13: 0.6  # Construction site
            }

        # Apply place type adjustments
        mask = df[place_type_col].isin(place_adjustment.keys())
        if mask.any():
            df.loc[mask, 'place_adjustment'] = df.loc[mask, place_type_col].map(place_adjustment)
            df.loc[mask, 'idling_percent'] = df.loc[mask, 'idling_percent'] * df.loc[mask, 'place_adjustment']

    # Adjust based on vehicle class if available
    if vehicle_class_col and vehicle_class_col in df.columns:
        print(f"  Adjusting idling times based on vehicle class")

        # Create a simplified vehicle size category if possible
        if pd.api.types.is_numeric_dtype(df[vehicle_class_col]):
            df['vehicle_size'] = 'Medium'  # Default

            # Larger vehicles (adjust based on your coding)
            large_vehicle_codes = [6, 7, 8]  # Single unit 3+ axle and Semi
            medium_vehicle_codes = [5]  # Single unit 2-axle
            small_vehicle_codes = [1, 2, 3, 4]  # Cars, pickups, vans, SUVs

            df.loc[df[vehicle_class_col].isin(large_vehicle_codes), 'vehicle_size'] = 'Large'
            df.loc[df[vehicle_class_col].isin(medium_vehicle_codes), 'vehicle_size'] = 'Medium'
            df.loc[df[vehicle_class_col].isin(small_vehicle_codes), 'vehicle_size'] = 'Small'

            # Apply size adjustment
            size_adjustment = {
                'Large': 1.2,
                'Medium': 1.0,
                'Small': 0.8
            }

            df['size_adjustment'] = df['vehicle_size'].map(size_adjustment)
            df['idling_percent'] = df['idling_percent'] * df['size_adjustment'].fillna(1.0)

    # Calculate idling duration
    df['idling_duration'] = df['stop_duration'] * df['idling_percent']

    # Cap idling percent at 1.0 (100%)
    df['idling_percent'] = df['idling_percent'].clip(upper=1.0)

    return df


def aggregate_durations(df):
    """
    Aggregate durations by different dimensions
    """
    print("Aggregating results...")

    results = {}

    # Activity type analysis
    activity_stats = df.groupby('ActivityType').agg({
        'StopDuration': ['count', 'mean', 'median', 'std', 'min', 'max'],
        'IdlingDuration': ['mean', 'median', 'std', 'min', 'max']
    }).reset_index()

    results['activity_type'] = activity_stats

    # Land use type analysis
    landuse_stats = df.groupby('PlaceType').agg({
        'StopDuration': ['count', 'mean', 'median', 'std', 'min', 'max'],
        'IdlingDuration': ['mean', 'median', 'std', 'min', 'max']
    }).reset_index()

    results['land_use'] = landuse_stats

    # Vehicle type analysis
    vehicle_stats = df.groupby(['CommercialType', 'VehicleClass']).agg({
        'StopDuration': ['count', 'mean', 'median', 'std', 'min', 'max'],
        'IdlingDuration': ['mean', 'median', 'std', 'min', 'max']
    }).reset_index()

    results['vehicle_type'] = vehicle_stats

    # Combined analysis (activity x land use)
    combined_stats = df.groupby(['ActivityType', 'PlaceType']).agg({
        'StopDuration': ['count', 'mean', 'median'],
        'IdlingDuration': ['mean', 'median']
    }).reset_index()

    results['activity_landuse'] = combined_stats

    # Time of day analysis
    df['HourOfDay'] = df['ArrivalTime'].dt.hour
    time_stats = df.groupby('HourOfDay').agg({
        'StopDuration': ['count', 'mean', 'median'],
        'IdlingDuration': ['mean', 'median']
    }).reset_index()

    results['time_of_day'] = time_stats

    return results


def create_visualizations(results, output_dir):
    """
    Create visualizations of the aggregated results
    """
    print("Creating visualizations...")

    os.makedirs(output_dir, exist_ok=True)

    # Set plot style
    plt.style.use('ggplot')
    sns.set(style="whitegrid")

    # Activity type visualization
    plt.figure(figsize=(12, 6))
    activity_data = results['activity_type']
    plt.bar(activity_data['ActivityType'], activity_data[('StopDuration', 'mean')])
    plt.title('Average Stop Duration by Activity Type')
    plt.xlabel('Activity Type')
    plt.ylabel('Average Duration (minutes)')
    plt.tight_layout()
    plt.savefig(os.path.join(output_dir, 'activity_stop_duration.png'))
    plt.close()

    # Land use visualization
    plt.figure(figsize=(12, 6))
    landuse_data = results['land_use']
    plt.bar(landuse_data['PlaceType'], landuse_data[('StopDuration', 'mean')])
    plt.title('Average Stop Duration by Land Use Type')
    plt.xlabel('Land Use Type')
    plt.ylabel('Average Duration (minutes)')
    plt.tight_layout()
    plt.savefig(os.path.join(output_dir, 'landuse_stop_duration.png'))
    plt.close()

    # Idling by activity
    plt.figure(figsize=(12, 6))
    plt.bar(activity_data['ActivityType'], activity_data[('IdlingDuration', 'mean')])
    plt.title('Estimated Average Idling Duration by Activity Type')
    plt.xlabel('Activity Type')
    plt.ylabel('Average Idling Duration (minutes)')
    plt.tight_layout()
    plt.savefig(os.path.join(output_dir, 'activity_idling_duration.png'))
    plt.close()

    # Time of day analysis
    plt.figure(figsize=(12, 6))
    time_data = results['time_of_day']
    plt.plot(time_data['HourOfDay'], time_data[('StopDuration', 'mean')], 'o-', label='Stop Duration')
    plt.plot(time_data['HourOfDay'], time_data[('IdlingDuration', 'mean')], 's-', label='Idling Duration')
    plt.title('Average Stop and Idling Duration by Hour of Day')
    plt.xlabel('Hour of Day')
    plt.ylabel('Duration (minutes)')
    plt.legend()
    plt.xticks(range(0, 24))
    plt.grid(True)
    plt.tight_layout()
    plt.savefig(os.path.join(output_dir, 'time_of_day_analysis.png'))
    plt.close()

    return


def export_results(results, output_dir):
    """
    Export results to CSV files
    """
    print("Exporting results...")

    os.makedirs(output_dir, exist_ok=True)

    for key, df in results.items():
        # Flatten MultiIndex columns
        if isinstance(df.columns, pd.MultiIndex):
            df.columns = ['_'.join(col).strip() for col in df.columns.values]

        # Export to CSV
        df.to_csv(os.path.join(output_dir, f"{key}_analysis.csv"), index=False)

    print(f"Results exported to {output_dir}")


def parse_time_columns(df):
    """
    Identify and parse time columns in the dataset
    """
    print("Parsing time columns...")

    # Identify arrival and departure time columns
    arrival_cols = [col for col in df.columns if ('arriv' in col.lower() or 'arrival' in col.lower())]
    departure_cols = [col for col in df.columns if ('depart' in col.lower() or 'departure' in col.lower())]

    # If we don't have clear arrival/departure, look for time columns
    if not arrival_cols or not departure_cols:
        time_cols = [col for col in df.columns if 'time' in col.lower()]
        print(f"Found time columns: {time_cols}")
    else:
        print(f"Found arrival columns: {arrival_cols}")
        print(f"Found departure columns: {departure_cols}")

    # Try to parse the first arrival column
    if arrival_cols:
        arrival_col = arrival_cols[0]
        try:
            # Check a sample value
            sample_val = df[arrival_col].dropna().iloc[0] if not df[arrival_col].dropna().empty else None

            if sample_val:
                print(f"Sample arrival time value: {sample_val} (type: {type(sample_val)})")

                # Different parsing strategies based on value type
                if isinstance(sample_val, (int, float)):
                    # This might be Excel time (fraction of day)
                    if 0 <= sample_val <= 1:
                        df['ArrivalTime_parsed'] = df[arrival_col].apply(
                            lambda x: datetime(1900, 1, 1) + timedelta(days=x) if pd.notnull(x) else pd.NaT
                        )
                    else:
                        # Could be minutes since midnight or another format
                        print(f"  Unable to automatically interpret numeric time format for {arrival_col}")
                else:
                    # Try standard datetime parsing
                    df['ArrivalTime_parsed'] = pd.to_datetime(df[arrival_col], errors='coerce')
        except Exception as e:
            print(f"  Error parsing arrival column {arrival_col}: {e}")

    # Try to parse the first departure column
    if departure_cols:
        departure_col = departure_cols[0]
        try:
            # Check a sample value
            sample_val = df[departure_col].dropna().iloc[0] if not df[departure_col].dropna().empty else None

            if sample_val:
                print(f"Sample departure time value: {sample_val} (type: {type(sample_val)})")

                # Different parsing strategies based on value type
                if isinstance(sample_val, (int, float)):
                    # This might be Excel time (fraction of day)
                    if 0 <= sample_val <= 1:
                        df['DepartureTime_parsed'] = df[departure_col].apply(
                            lambda x: datetime(1900, 1, 1) + timedelta(days=x) if pd.notnull(x) else pd.NaT
                        )
                    else:
                        # Could be minutes since midnight or another format
                        print(f"  Unable to automatically interpret numeric time format for {departure_col}")
                else:
                    # Try standard datetime parsing
                    df['DepartureTime_parsed'] = pd.to_datetime(df[departure_col], errors='coerce')
        except Exception as e:
            print(f"  Error parsing departure column {departure_col}: {e}")

    # List all the successfully parsed time columns
    parsed_cols = [col for col in df.columns if col.endswith('_parsed')]
    print(f"Successfully parsed time columns: {parsed_cols}")

    return df


def identify_key_columns(df):
    """
    Identify key columns for analysis
    """
    print("Identifying key columns for analysis...")

    # Create a dictionary to store the identified column names
    key_cols = {}

    # Arrival time
    arrival_candidates = [col for col in df.columns if 'arriv' in col.lower() and col.endswith('_parsed')]
    if arrival_candidates:
        key_cols['arrival_time'] = arrival_candidates[0]
    else:
        print("  Could not identify arrival time column")

    # Departure time
    departure_candidates = [col for col in df.columns if 'depart' in col.lower() and col.endswith('_parsed')]
    if departure_candidates:
        key_cols['departure_time'] = departure_candidates[0]
    else:
        print("  Could not identify departure time column")

    # Activity type
    activity_candidates = [col for col in df.columns if 'activ' in col.lower()]
    if activity_candidates:
        key_cols['activity_type'] = activity_candidates[0]
    else:
        print("  Could not identify activity type column")

    # Place/Land use type
    place_candidates = [col for col in df.columns if 'place' in col.lower() or 'land' in col.lower()]
    if place_candidates:
        key_cols['place_type'] = place_candidates[0]
    else:
        print("  Could not identify place type column")

    # Vehicle class/type
    vehicle_class_candidates = [col for col in df.columns if
                                ('class' in col.lower() or 'type' in col.lower()) and 'vehicle' in col.lower()]
    if vehicle_class_candidates:
        key_cols['vehicle_class'] = vehicle_class_candidates[0]
    else:
        print("  Could not identify vehicle class column")

    # Commercial type (cargo/service)
    commercial_candidates = [col for col in df.columns if
                             'commerc' in col.lower() or 'cargo' in col.lower() or 'service' in col.lower()]
    if commercial_candidates:
        key_cols['commercial_type'] = commercial_candidates[0]
    else:
        print("  Could not identify commercial type column")

    # Weight column
    weight_candidates = [col for col in df.columns if 'weight' in col.lower() or 'cargo' in col.lower()]
    if weight_candidates:
        key_cols['weight'] = weight_candidates[0]
    else:
        print("  Could not identify weight column")

    print("Identified key columns:")
    for k, v in key_cols.items():
        print(f"  {k}: {v}")

    return key_cols


def main():
    """
    Main function to run the analysis
    """
    print("Starting Commercial Vehicle Operation Duration Analysis")

    # File paths
    austin_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/data/Austin_2017")
    frism_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/frism/2024-01-23/Baseline")
    output_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/analysis/operation_durations")

    # Process Austin CV Survey data
    try:
        print("\nProcessing Austin Commercial Vehicle Survey data...")
        trip_data_path = f"{austin_dir}/Raw 2017-2018 Austin Commercial Vehicle Travel Survey Data for UT and ANL.xlsx"
        vehicle_data_path = f"{austin_dir}/2017-2018 Austin Commercial Vehicle Survey Data File Formats.xlsx"

        # Load and process data
        austin_data = load_and_process_data(trip_data_path, vehicle_data_path)

        # Parse time columns
        austin_data = parse_time_columns(austin_data)

        # Identify key columns
        key_cols = identify_key_columns(austin_data)

        # Calculate stop durations
        austin_data = calculate_stop_durations(austin_data, key_cols)

        # Estimate idling durations
        austin_data = estimate_idling_durations(austin_data, key_cols)

        # Analyze by different dimensions
        if 'activity_type' in key_cols:
            analyze_durations_by_key(
                austin_data,
                key_cols['activity_type'],
                'stop_duration',
                output_dir=output_dir,
                prefix='austin'
            )

        if 'place_type' in key_cols:
            analyze_durations_by_key(
                austin_data,
                key_cols['place_type'],
                'stop_duration',
                output_dir=output_dir,
                prefix='austin'
            )

        print("Austin data processing complete.")
    except Exception as e:
        print(f"Error processing Austin data: {e}")
        austin_data = None

    # Process FRISM plan data
    try:
        print("\nProcessing FRISM plan data...")
        frism_b2b = pd.read_csv(f"{frism_dir}/B2B_all_payload_sBase_y2018.csv")
        frism_b2c = pd.read_csv(f"{frism_dir}/B2C_all_payload_sBase_y2018.csv")
        frism_data = pd.concat([frism_b2b, frism_b2c], ignore_index=True)

        # Ensure weight column is processed
        if 'weightInlb' in frism_data.columns:
            frism_data["weightInlbAbs"] = frism_data["weightInlb"].abs()

        # Generate histogram
        generate_duration_histogram(
            frism_data["operationDurationInSec"],
        )

        # Analyze by categorical variables if available
        if 'operationType' in frism_data.columns:
            analyze_durations_by_key(
                frism_data,
                'operationType',
                'operationDurationInSec',
                output_dir=output_dir,
                prefix='frism'
            )

        print("FRISM data processing complete.")
    except Exception as e:
        print(f"Error processing FRISM data: {e}")
        frism_data = None

    # Combined analysis if both datasets are available
    if austin_data is not None and frism_data is not None:
        try:
            analyze_operation_durations(austin_data, frism_data, output_dir=output_dir)
        except Exception as e:
            print(f"Error performing combined analysis: {e}")

    print("\nAnalysis complete!")
    print(f"Results saved to {output_dir} directory")


if __name__ == "__main__":
    main()