import os

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy import stats
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


def plot_duration_vs_weight(df, duration_col, weight_col, group_col=None, output_file='duration_vs_weight.png'):
    """
    Create a scatter plot showing the relationship between stop durations and weight,
    with optional grouping by a categorical variable.

    Parameters:
    -----------
    df : pandas.DataFrame
        The DataFrame containing the data
    duration_col : str
        Column name for operation duration in minutes
    weight_col : str
        Column name for weight in pounds
    group_col : str, optional
        Column name for grouping variable (categorical)
    output_file : str
        Path to save the output plot

    Returns:
    --------
    dict
        Dictionary containing correlation statistics
    """
    # Ensure clean data by removing NaN values
    plot_df = df.dropna(subset=[duration_col, weight_col]).copy()

    if plot_df.empty:
        print(f"No valid data points found with non-null values in both {duration_col} and {weight_col}")
        return None

    # Use durations in minutes directly if already in minutes
    durations_minutes = plot_df[duration_col]
    weights_lbs = plot_df[weight_col]

    # Create a figure
    plt.figure(figsize=(12, 8))

    if group_col is not None and group_col in plot_df.columns:
        # Create a grouped scatter plot with different colors
        groups = plot_df[group_col].unique()

        # Create a colormap with distinct colors
        colors = plt.cm.tab10(np.linspace(0, 1, len(groups)))

        # Plot each group separately
        for i, group in enumerate(groups):
            group_data = plot_df[plot_df[group_col] == group]
            plt.scatter(group_data[duration_col], group_data[weight_col],
                        alpha=0.6, color=colors[i], edgecolor='none',
                        label=f'{group}')

            # Calculate correlation for this group if enough data points
            if len(group_data) > 2:
                group_corr, _ = stats.pearsonr(group_data[duration_col], group_data[weight_col])

                # Calculate and plot best fit line for this group
                group_slope, group_intercept = np.polyfit(group_data[duration_col], group_data[weight_col], 1)
                x_line = np.array([group_data[duration_col].min(), group_data[duration_col].max()])
                y_line = group_slope * x_line + group_intercept
                plt.plot(x_line, y_line, color=colors[i], linewidth=2,
                         linestyle='--')

                # Add correlation text near the group in the plot
                plt.annotate(f'r = {group_corr:.2f}',
                             xy=(group_data[duration_col].median(), group_data[weight_col].median()),
                             xytext=(10, 0), textcoords='offset points',
                             fontsize=9, color=colors[i])

        plt.title(f'Relationship Between Operation Duration and Weight by {group_col}', fontsize=14)
    else:
        # Generate simple scatter plot if no grouping
        plt.scatter(durations_minutes, weights_lbs, alpha=0.5, color='steelblue', edgecolor='none')
        plt.title('Relationship Between Operation Duration and Weight', fontsize=14)

    # Add labels and grid
    plt.xlabel('Operation Duration (minutes)', fontsize=12)
    plt.ylabel('Weight (lbs)', fontsize=12)
    plt.grid(linestyle='--', alpha=0.7)

    # Calculate overall correlation coefficient and p-value
    corr, p_value = stats.pearsonr(durations_minutes, weights_lbs)

    # Calculate and plot overall best fit line
    slope, intercept = np.polyfit(durations_minutes, weights_lbs, 1)
    x_line = np.array([durations_minutes.min(), durations_minutes.max()])
    y_line = slope * x_line + intercept

    if group_col is None:
        # Only show the overall trend line when not grouping
        plt.plot(x_line, y_line, color='red', linewidth=2,
                 label=f'y = {slope:.2f}x + {intercept:.2f}')

    # Add text with statistics
    stats_text = (f'Overall Statistics:\n'
                  f'Pearson Correlation: {corr:.4f}\n'
                  f'P-value: {p_value:.4e}\n'
                  f'Slope: {slope:.4f}\n'
                  f'Intercept: {intercept:.2f}\n'
                  f'N = {len(durations_minutes)}')

    plt.annotate(stats_text, xy=(0.05, 0.95), xycoords='axes fraction',
                 fontsize=10, ha='left', va='top',
                 bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.7))

    # Add legend if groups are present
    if group_col is not None and group_col in plot_df.columns:
        plt.legend(title=group_col)
    else:
        plt.legend()

    # Save the plot
    plt.tight_layout()
    plt.savefig(output_file)
    plt.close()

    print(f"Scatter plot saved as '{output_file}'")

    # Return correlation statistics
    return {
        'correlation': corr,
        'p_value': p_value,
        'slope': slope,
        'intercept': intercept,
        'n': len(durations_minutes)
    }


def load_and_process_austin_data(trip_data_path, output_dir):
    """
    Load and process the Austin Commercial Vehicle Survey data
    with specific handling for the observed structure
    """
    print("Loading Austin data files...")

    # Load both sheets from the trip data file
    rec_20 = pd.read_excel(trip_data_path, sheet_name='Rec_20')
    rec_21 = pd.read_excel(trip_data_path, sheet_name='Rec_21')

    print(f"Loaded sheet Rec_20 with {len(rec_20)} records")
    print(f"Loaded sheet Rec_21 with {len(rec_21)} records")

    # Filter Rec_20 to keep only cargo vehicles (type=1)
    if '23. Veh Type 1=Cargo, 2=Service, 3=Service with some Cargo' in rec_20.columns:
        rec_20_filtered = rec_20[rec_20['23. Veh Type 1=Cargo, 2=Service, 3=Service with some Cargo'] == 1].copy()
        print(f"Filtered Rec_20 to {len(rec_20_filtered)} cargo vehicles")

        # Keep only required columns from Rec_20
        vehicle_columns = [
            '3. Vehicle ID Number',
            '22. Year of Vehicle',
            '25. Vehicle Fuel Type',
            '27. Vehicle Class.'
        ]

        # Check if all required columns exist
        missing_cols = [col for col in vehicle_columns if col not in rec_20_filtered.columns]
        if missing_cols:
            print(f"Warning: Missing columns in Rec_20: {missing_cols}")
            vehicle_columns = [col for col in vehicle_columns if col in rec_20_filtered.columns]

        rec_20_filtered = rec_20_filtered[vehicle_columns].copy()

        # Create fuel type mapping
        fuel_type_map = {
            1: 'Gasoline',
            2: 'Diesel',
            3: 'Propane',
            4: 'Natural Gas',
            5: 'Electricity',
            6: 'Gas/Electric Hybrid',
            96: 'Other',
            98: 'Don\'t Know',
            99: 'Refused'
        }

        # Create vehicle class mapping
        vehicle_class_map = {
            1: 'Passenger Car',
            2: 'Pick-up',
            3: 'Van (Cargo or Mini)',
            4: 'Sport Utility Vehicle (SUV)',
            5: 'Single Unit 2-axle (6 wheels)',
            6: 'Single Unit 3-axle (10 wheels)',
            7: 'Single Unit 4-axle (14 wheels)',
            8: 'Semi (all Tractor-Trailer Combinations)',
            96: 'Other'
        }

        vehicle_class_mapping = {
            "Semi (all Tractor-Trailer Combinations)": "Class78Tractor",
            "Single Unit 2-axle (6 wheels)": "Class456Vocational",
            "Single Unit 3-axle (10 wheels)": "Class456Vocational",
            "Single Unit 4-axle (14 wheels)": "Class78Vocational"
        }

        # Apply mappings if columns exist
        if '25. Vehicle Fuel Type' in rec_20_filtered.columns:
            rec_20_filtered['vehicleFuelType'] = rec_20_filtered['25. Vehicle Fuel Type'].map(fuel_type_map)

        if '27. Vehicle Class.' in rec_20_filtered.columns:
            rec_20_filtered['vehicleClass'] = rec_20_filtered['27. Vehicle Class.'].map(vehicle_class_map).map(vehicle_class_mapping)
            rec_20_filtered.dropna(subset=['vehicleClass'], inplace=True)

        # Rename columns for clarity
        rec_20_filtered.rename(columns={
            '3. Vehicle ID Number': 'vehicleId',
            '22. Year of Vehicle': 'vehicleModelYear',
        }, inplace=True)

        # Drop original columns that have been mapped
        if '25. Vehicle Fuel Type' in rec_20_filtered.columns:
            rec_20_filtered.drop('25. Vehicle Fuel Type', axis=1, inplace=True)

        if '27. Vehicle Class.' in rec_20_filtered.columns:
            rec_20_filtered.drop('27. Vehicle Class.', axis=1, inplace=True)
    else:
        print("Vehicle type column not found in Rec_20")
        return None

    # Filter Rec_21 to keep only records with activity types 4, 5, and 6
    if '8. Type of Activity' in rec_21.columns:
        cargo_activity_types = [4, 5, 6]  # 4-Delivery of Cargo, 5-Pick up Cargo, 6-Deliver and Pick up Cargo

        # Create activity type mapping
        activity_map = {
            4: 'Delivery of Cargo',
            5: 'Pick up Cargo',
            6: 'Deliver and Pick up Cargo'
        }

        # Filter records
        rec_21_filtered = rec_21[rec_21['8. Type of Activity'].isin(cargo_activity_types)].copy()
        print(f"Filtered to {len(rec_21_filtered)} records with cargo activities (types 4, 5, 6)")

        # Add human-readable activity type
        rec_21_filtered['activityType'] = rec_21_filtered['8. Type of Activity'].map(activity_map)
    else:
        print("Column '8. Type of Activity' not found in dataset")
        return None

    # Calculate operation duration in minutes
    if '29. Arrival Minute' in rec_21_filtered.columns and '31. Departure Minute' in rec_21_filtered.columns:
        print("Calculating operation duration from minute-based time columns")

        # Create a mask for first trips (where arrival fields are blank/NaN)
        first_trip_mask = rec_21_filtered['28. Arrival Hour'].isna()

        # Create a mask for last trips (where departure fields are blank/NaN)
        last_trip_mask = rec_21_filtered['30. Departure Hour'].isna()

        # Initialize the operation duration column with NaN values
        rec_21_filtered['operationDurationInMin'] = float('nan')

        # Make a copy of departure hour column to modify
        departure_hours = rec_21_filtered['30. Departure Hour'].copy()

        # Replace 0 hour with 24 hours (regardless of minutes)
        departure_hours.loc[departure_hours == 0] = 24

        # Calculate duration only for regular trips (not first or last)
        regular_trips = ~(first_trip_mask | last_trip_mask)
        rec_21_filtered.loc[regular_trips, 'operationDurationInMin'] = (
                (departure_hours.loc[regular_trips] -
                 rec_21_filtered.loc[regular_trips, '28. Arrival Hour']) * 60 +
                (rec_21_filtered.loc[regular_trips, '31. Departure Minute'] -
                 rec_21_filtered.loc[regular_trips, '29. Arrival Minute'])
        )
    else:
        print("Could not find minute-based time columns")
        return None

    # Extract cargo weights
    if '25. Cargo Weight PU' in rec_21_filtered.columns:
        rec_21_filtered['cargoWeightPU'] = rec_21_filtered['25. Cargo Weight PU']
    else:
        print("Column '25. Cargo Weight PU' not found, adding empty column")
        rec_21_filtered['cargoWeightPU'] = np.nan

    if '26. Cargo Weight DO' in rec_21_filtered.columns:
        rec_21_filtered['cargoWeightDO'] = rec_21_filtered['26. Cargo Weight DO']
    else:
        print("Column '26. Cargo Weight DO' not found, adding empty column")
        rec_21_filtered['cargoWeightDO'] = np.nan

    # Rename Vehicle ID in rec_21_filtered for merging
    if '3. Vehicle ID Number' in rec_21_filtered.columns:
        rec_21_filtered.rename(columns={'3. Vehicle ID Number': 'vehicleId'}, inplace=True)

    # Merge rec_21_filtered with rec_20_filtered
    if 'vehicleId' in rec_21_filtered.columns and 'vehicleId' in rec_20_filtered.columns:
        print("Merging trip data with vehicle information")
        merged_data = pd.merge(
            rec_21_filtered,
            rec_20_filtered,
            on='vehicleId',
            how='inner'  # Keep only records that exist in both datasets
        )
        print(f"Merged data has {len(merged_data)} records")
    else:
        print("Vehicle ID column not found for merging")
        return None

    # Create output CSV with only the required columns
    output_columns = [
        'vehicleId',
        'vehicleModelYear',
        'vehicleFuelType',
        'vehicleClass',
        'operationDurationInMin',
        'activityType',
        'cargoWeightPU',
        'cargoWeightDO'
    ]

    # Check that all output columns exist
    missing_cols = [col for col in output_columns if col not in merged_data.columns]
    if missing_cols:
        print(f"Warning: Missing columns in merged data: {missing_cols}")
        output_columns = [col for col in output_columns if col in merged_data.columns]

    output_df = merged_data[output_columns]

    # Save to CSV
    csv_path = os.path.join(output_dir, 'austin_cargo_operations.csv')
    output_df.to_csv(csv_path, index=False)
    print(f"CSV file saved to {csv_path}")

    return merged_data


def analyze_durations_by_key(df, key_column, duration_column, output_dir='.', prefix=''):
    """
    Analyze and visualize stop durations by a key column (e.g., activity type, land use, vehicle type)

    Parameters:
    -----------
    df : pandas.DataFrame
        DataFrame containing the data
    key_column : str
        Column name to group by (e.g., 'activity_type', 'place_type')
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

    # Check if columns exist
    if key_column not in df.columns:
        print(f"Column '{key_column}' not found in DataFrame")
        return None

    if duration_column not in df.columns:
        print(f"Column '{duration_column}' not found in DataFrame")
        return None

    # Group by key column and calculate statistics
    stats = df.groupby(key_column)[duration_column].agg([
        'count', 'mean', 'median', 'std', 'min', 'max'
    ]).reset_index()

    # Convert key column to string for plotting
    stats[key_column] = stats[key_column].astype(str)

    # Create visualization
    plt.figure(figsize=(12, 6))
    plt.bar(stats[key_column], stats['mean'], color='steelblue')
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

def analyze_austin_operation_durations(data, output_dir='.'):
    """
    Analyze operation durations from the Austin CV Survey data
    """
    print("Analyzing Austin operation durations...")

    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    # Check if we have the necessary columns
    if 'stop_duration' not in data.columns:
        print("Cannot analyze durations - stop_duration column not found")
        return None

    # Generate histogram of stop durations
    plt.figure(figsize=(12, 6))
    plt.hist(data['stop_duration'], bins=50, alpha=0.75, color='steelblue', edgecolor='black')
    plt.title('Distribution of Austin CV Survey Operation Durations', fontsize=14)
    plt.xlabel('Operation Duration (minutes)', fontsize=12)
    plt.ylabel('Frequency', fontsize=12)
    plt.grid(axis='y', linestyle='--', alpha=0.7)

    # Calculate and display statistics
    mean_duration = data['stop_duration'].mean()
    median_duration = data['stop_duration'].median()

    stats_text = f'Mean: {mean_duration:.2f} minutes\nMedian: {median_duration:.2f} minutes'
    plt.annotate(stats_text, xy=(0.95, 0.95), xycoords='axes fraction',
                 fontsize=10, ha='right', va='top',
                 bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.7))

    plt.tight_layout()
    plt.savefig(os.path.join(output_dir, 'austin_duration_histogram.png'))
    plt.close()

    # Analyze by activity type if available
    if 'activity_type' in data.columns:
        analyze_durations_by_key(
            data,
            'activity_type',
            'stop_duration',
            output_dir=output_dir,
            prefix='austin'
        )

    # Analyze by place type if available
    if 'place_type' in data.columns:
        analyze_durations_by_key(
            data,
            'place_type',
            'stop_duration',
            output_dir=output_dir,
            prefix='austin'
        )

    # Return statistics
    stats = {
        'mean': mean_duration,
        'median': median_duration,
        'min': data['stop_duration'].min(),
        'max': data['stop_duration'].max()
    }

    return stats


def analyze_cargo_operations(df, output_dir):
    """
    Comprehensive analysis of cargo operations data

    Parameters:
    -----------
    file_path : str
        Path to the CSV file containing cargo operations data
    """
    # Display basic information
    print("\n===== BASIC INFORMATION =====")
    print(f"Dataset shape: {df.shape}")
    print("\nFirst few rows:")
    print(df.head())
    print("\nColumn information:")
    print(df.info())
    print("\nSummary statistics:")
    print(df.describe())

    # Check for missing values
    print("\n===== MISSING VALUES =====")
    missing = df.isnull().sum()
    print(missing[missing > 0])

    # Clean the data
    print("\n===== DATA CLEANING =====")
    # Convert operation duration to numeric if not already
    if df['operationDurationInMin'].dtype == 'object':
        df['operationDurationInMin'] = pd.to_numeric(df['operationDurationInMin'], errors='coerce')
        print("Converted operationDurationInMin to numeric")

    # Handle missing values in cargo weights
    if 'cargoWeightPU' in df.columns and df['cargoWeightPU'].isnull().sum() > 0:
        print(f"Missing values in cargoWeightPU: {df['cargoWeightPU'].isnull().sum()}")
        # For analysis purposes, we'll separate pickup and delivery operations
        pickup_ops = df[df['activityType'] == 'Pick up Cargo'].copy()
        delivery_ops = df[df['activityType'] == 'Delivery of Cargo'].copy()
        print(f"Pickup operations: {pickup_ops.shape[0]}, Delivery operations: {delivery_ops.shape[0]}")

    # Remove extreme outliers (if needed)
    q1 = df['operationDurationInMin'].quantile(0.01)
    q3 = df['operationDurationInMin'].quantile(0.99)
    iqr = q3 - q1

    print(f"Duration statistics before outlier treatment:")
    print(f"Min: {df['operationDurationInMin'].min()}, Max: {df['operationDurationInMin'].max()}")
    print(f"Mean: {df['operationDurationInMin'].mean():.2f}, Median: {df['operationDurationInMin'].median():.2f}")
    print(f"1% percentile: {q1}, 99% percentile: {q3}")

    # Create a copy for analysis without extreme outliers
    df_no_outliers = df[(df['operationDurationInMin'] >= q1 - 1.5 * iqr) &
                        (df['operationDurationInMin'] <= q3 + 1.5 * iqr)].copy()

    print(f"\nRemoved {df.shape[0] - df_no_outliers.shape[0]} extreme outliers")
    print(f"Duration statistics after outlier treatment:")
    print(
        f"Min: {df_no_outliers['operationDurationInMin'].min()}, Max: {df_no_outliers['operationDurationInMin'].max()}")
    print(
        f"Mean: {df_no_outliers['operationDurationInMin'].mean():.2f}, Median: {df_no_outliers['operationDurationInMin'].median():.2f}")

    # Create visualizations
    print("\n===== CREATING VISUALIZATIONS =====")

    # Set up the plotting environment
    plt.style.use('ggplot')
    sns.set(font_scale=1.2)

    # 1. Distribution of operation durations
    plt.figure(figsize=(12, 6))

    plt.subplot(1, 2, 1)
    sns.histplot(df['operationDurationInMin'], kde=True, bins=30)
    plt.title('Distribution of Operation Durations')
    plt.xlabel('Duration (minutes)')
    plt.ylabel('Frequency')

    plt.subplot(1, 2, 2)
    sns.histplot(df['operationDurationInMin'], kde=True, log_scale=True, bins=30)
    plt.title('Distribution of Operation Durations (Log Scale)')
    plt.xlabel('Duration (minutes) - Log Scale')
    plt.ylabel('Frequency')

    plt.tight_layout()
    plt.savefig(f"{output_dir}/duration_distribution.png")
    plt.close()
    print("Created duration distribution plot")

    # 2. Operation Duration by Vehicle Model Year
    plt.figure(figsize=(14, 8))

    # Calculate average duration by model year
    year_duration = df.groupby('vehicleModelYear')['operationDurationInMin'].agg(['mean', 'median', 'count'])
    year_duration = year_duration.reset_index()

    # Plot with size representing count
    plt.subplot(1, 2, 1)
    sns.scatterplot(data=year_duration, x='vehicleModelYear', y='mean', size='count', sizes=(20, 500), alpha=0.7)
    plt.title('Average Operation Duration by Vehicle Model Year')
    plt.xlabel('Vehicle Model Year')
    plt.ylabel('Average Duration (minutes)')

    plt.subplot(1, 2, 2)
    sns.boxplot(data=df, x='vehicleModelYear', y='operationDurationInMin')
    plt.title('Operation Duration Distribution by Vehicle Model Year')
    plt.xlabel('Vehicle Model Year')
    plt.ylabel('Duration (minutes)')
    plt.xticks(rotation=45)

    plt.tight_layout()
    plt.savefig(f"{output_dir}/duration_by_year.png")
    plt.close()
    print("Created duration by vehicle year plot")

    # 3. Operation Duration by Activity Type
    if 'activityType' in df.columns:
        plt.figure(figsize=(14, 6))

        plt.subplot(1, 2, 1)
        sns.boxplot(data=df, x='activityType', y='operationDurationInMin')
        plt.title('Operation Duration by Activity Type')
        plt.xlabel('Activity Type')
        plt.ylabel('Duration (minutes)')

        plt.subplot(1, 2, 2)
        activity_counts = df['activityType'].value_counts()
        sns.barplot(x=activity_counts.index, y=activity_counts.values)
        plt.title('Count by Activity Type')
        plt.xlabel('Activity Type')
        plt.ylabel('Count')

        plt.tight_layout()
        plt.savefig(f"{output_dir}/duration_by_activity.png")
        plt.close()
        print("Created duration by activity type plot")

    # 4. Operation Duration by Cargo Weight (for pickup operations)
    pickup_ops = df[df['activityType'] == 'Pick up Cargo'].copy()
    if 'cargoWeightPU' in df.columns and not pickup_ops.empty:
        plt.figure(figsize=(14, 8))

        # Remove NaN values for this analysis
        pickup_with_weight = pickup_ops.dropna(subset=['cargoWeightPU'])

        if not pickup_with_weight.empty:
            plt.subplot(1, 2, 1)
            sns.scatterplot(data=pickup_with_weight, x='cargoWeightPU', y='operationDurationInMin', alpha=0.5)
            plt.title('Operation Duration vs Cargo Weight (Pick Up)')
            plt.xlabel('Cargo Weight (Pick Up)')
            plt.ylabel('Duration (minutes)')

            plt.subplot(1, 2, 2)
            # Create weight bins
            pickup_with_weight['weight_bin'] = pd.cut(pickup_with_weight['cargoWeightPU'], bins=10)
            sns.boxplot(data=pickup_with_weight, x='weight_bin', y='operationDurationInMin')
            plt.title('Operation Duration by Cargo Weight Range (Pick Up)')
            plt.xlabel('Cargo Weight Range')
            plt.ylabel('Duration (minutes)')
            plt.xticks(rotation=90)

            plt.tight_layout()
            plt.savefig(f"{output_dir}/duration_by_weight_pickup.png")
            plt.close()
            print("Created duration by cargo weight plot for pickup operations")

    # 5. Operation Duration by Cargo Weight (for delivery operations)
    delivery_ops = df[df['activityType'] == 'Delivery of Cargo'].copy()
    if 'cargoWeightDO' in df.columns and not delivery_ops.empty:
        plt.figure(figsize=(14, 8))

        # Remove NaN values for this analysis
        delivery_with_weight = delivery_ops.dropna(subset=['cargoWeightDO'])

        if not delivery_with_weight.empty:
            plt.subplot(1, 2, 1)
            sns.scatterplot(data=delivery_with_weight, x='cargoWeightDO', y='operationDurationInMin', alpha=0.5)
            plt.title('Operation Duration vs Cargo Weight (Delivery)')
            plt.xlabel('Cargo Weight (Delivery)')
            plt.ylabel('Duration (minutes)')

            plt.subplot(1, 2, 2)
            # Create weight bins
            delivery_with_weight['weight_bin'] = pd.cut(delivery_with_weight['cargoWeightDO'], bins=10)
            sns.boxplot(data=delivery_with_weight, x='weight_bin', y='operationDurationInMin')
            plt.title('Operation Duration by Cargo Weight Range (Delivery)')
            plt.xlabel('Cargo Weight Range')
            plt.ylabel('Duration (minutes)')
            plt.xticks(rotation=90)

            plt.tight_layout()
            plt.savefig(f"{output_dir}/duration_by_weight_delivery.png")
            plt.close()
            print("Created duration by cargo weight plot for delivery operations")

    # 6. Operation Duration by Vehicle Fuel Type
    plt.figure(figsize=(12, 6))

    fuel_counts = df['vehicleFuelType'].value_counts()

    plt.subplot(1, 2, 1)
    sns.boxplot(data=df, x='vehicleFuelType', y='operationDurationInMin')
    plt.title('Operation Duration by Fuel Type')
    plt.xlabel('Fuel Type')
    plt.ylabel('Duration (minutes)')

    plt.subplot(1, 2, 2)
    sns.barplot(x=fuel_counts.index, y=fuel_counts.values)
    plt.title('Count of Vehicles by Fuel Type')
    plt.xlabel('Fuel Type')
    plt.ylabel('Count')

    plt.tight_layout()
    plt.savefig(f"{output_dir}/duration_by_fuel_type.png")
    plt.close()
    print("Created duration by fuel type plot")

    # 7. Operation Duration by Vehicle Class
    plt.figure(figsize=(12, 6))

    class_counts = df['vehicleClass'].value_counts()

    plt.subplot(1, 2, 1)
    sns.boxplot(data=df, x='vehicleClass', y='operationDurationInMin')
    plt.title('Operation Duration by Vehicle Class')
    plt.xlabel('Vehicle Class')
    plt.ylabel('Duration (minutes)')
    plt.xticks(rotation=45)

    plt.subplot(1, 2, 2)
    sns.barplot(x=class_counts.index, y=class_counts.values)
    plt.title('Count of Vehicles by Class')
    plt.xlabel('Vehicle Class')
    plt.ylabel('Count')
    plt.xticks(rotation=45)

    plt.tight_layout()
    plt.savefig(f"{output_dir}/duration_by_vehicle_class.png")
    plt.close()
    print("Created duration by vehicle class plot")

    # 8. Correlation heatmap for numerical variables
    plt.figure(figsize=(10, 8))

    # Select only numeric columns
    numeric_df = df.select_dtypes(include=[np.number])

    # 9. Vehicle ID analysis - operations per vehicle
    vehicle_ops = df.groupby('vehicleId').size().reset_index(name='operation_count')
    vehicle_ops = vehicle_ops.sort_values('operation_count', ascending=False)

    plt.figure(figsize=(12, 6))
    plt.bar(range(len(vehicle_ops[:20])), vehicle_ops['operation_count'][:20])
    plt.xticks(range(len(vehicle_ops[:20])), vehicle_ops['vehicleId'][:20], rotation=45)
    plt.title('Number of Operations by Vehicle ID (Top 20)')
    plt.xlabel('Vehicle ID')
    plt.ylabel('Number of Operations')
    plt.tight_layout()
    plt.savefig(f"{output_dir}/operations_by_vehicle.png")
    plt.close()
    print("Created operations by vehicle plot")

    # Statistical analysis
    print("\n===== STATISTICAL ANALYSIS =====")

    # 1. Summary by vehicle class
    class_summary = df.groupby('vehicleClass')['operationDurationInMin'].agg(
        ['count', 'mean', 'median', 'std', 'min', 'max'])
    print("\nOperation Duration Summary by Vehicle Class:")
    print(class_summary)

    # 2. Summary by fuel type
    fuel_summary = df.groupby('vehicleFuelType')['operationDurationInMin'].agg(
        ['count', 'mean', 'median', 'std', 'min', 'max'])
    print("\nOperation Duration Summary by Fuel Type:")
    print(fuel_summary)

    # 3. Summary by activity type
    if 'activityType' in df.columns:
        activity_summary = df.groupby('activityType')['operationDurationInMin'].agg(
            ['count', 'mean', 'median', 'std', 'min', 'max'])
        print("\nOperation Duration Summary by Activity Type:")
        print(activity_summary)

    # 4. Summary by vehicle model year
    year_summary = df.groupby('vehicleModelYear')['operationDurationInMin'].agg(
        ['count', 'mean', 'median', 'std', 'min', 'max'])
    print("\nOperation Duration Summary by Vehicle Model Year:")
    print(year_summary)

    # 5. Correlation analysis
    print("\nCorrelation with Operation Duration:")
    for col in numeric_df.columns:
        if col != 'operationDurationInMin':
            correlation = df['operationDurationInMin'].corr(df[col])
            print(f"{col}: {correlation:.4f}")

    # 6. Top 10 longest operations
    print("\nTop 10 Longest Operations:")
    print(df.nlargest(10, 'operationDurationInMin')[
              ['vehicleId', 'vehicleModelYear', 'vehicleFuelType', 'vehicleClass', 'operationDurationInMin',
               'activityType']])

    # 7. Top 10 shortest operations (excluding zeros)
    print("\nTop 10 Shortest Operations (excluding zeros):")
    print(df[df['operationDurationInMin'] > 0].nsmallest(10, 'operationDurationInMin')[
              ['vehicleId', 'vehicleModelYear', 'vehicleFuelType', 'vehicleClass', 'operationDurationInMin',
               'activityType']])

    # 8. Zero duration operations
    zero_durations = df[df['operationDurationInMin'] == 0]
    print(
        f"\nNumber of zero-duration operations: {zero_durations.shape[0]}")

    # 9. Duration buckets analysis
    duration_buckets = [
        (0, 0),
        (0, 15),
        (15, 30),
        (30, 60),
        (60, 120),
        (120, 240),
        (240, 480),
        (480, 1000),
        (1000, float('inf'))
    ]

    bucket_labels = [
        'Zero',
        '0-15 min',
        '15-30 min',
        '30-60 min',
        '1-2 hours',
        '2-4 hours',
        '4-8 hours',
        '8-16 hours',
        '16+ hours'
    ]

    bucket_counts = []
    for i, (lower, upper) in enumerate(duration_buckets):
        if i == 0:  # Zero duration case
            count = (df['operationDurationInMin'] == 0).sum()
        else:
            count = ((df['operationDurationInMin'] > lower) & (df['operationDurationInMin'] <= upper)).sum()
        bucket_counts.append(count)

    plt.figure(figsize=(12, 6))
    plt.bar(bucket_labels, bucket_counts)
    plt.title('Operation Counts by Duration Buckets')
    plt.xlabel('Duration Bucket')
    plt.ylabel('Count')
    plt.xticks(rotation=45)
    plt.tight_layout()
    plt.savefig(f"{output_dir}/duration_buckets.png")
    plt.close()
    print("Created duration buckets analysis")

    # Save summary to CSV
    summary_df = pd.DataFrame({
        'Metric': ['Total Operations', 'Average Duration (min)', 'Median Duration (min)',
                   'Min Duration (min)', 'Max Duration (min)', 'Std Dev Duration (min)',
                   'Zero Duration Operations', 'Operations > 60 min', 'Operations > 120 min',
                   'Operations > 480 min', 'Operations > 1000 min'],
        'Value': [df.shape[0],
                  df['operationDurationInMin'].mean(),
                  df['operationDurationInMin'].median(),
                  df['operationDurationInMin'].min(),
                  df['operationDurationInMin'].max(),
                  df['operationDurationInMin'].std(),
                  zero_durations.shape[0],
                  df[df['operationDurationInMin'] > 60].shape[0],
                  df[df['operationDurationInMin'] > 120].shape[0],
                  df[df['operationDurationInMin'] > 480].shape[0],
                  df[df['operationDurationInMin'] > 1000].shape[0]]
    })

    summary_df.to_csv(f"{output_dir}/summary_statistics.csv", index=False)
    class_summary.to_csv(f"{output_dir}/class_summary.csv")
    fuel_summary.to_csv(f"{output_dir}/fuel_summary.csv")
    year_summary.to_csv(f"{output_dir}/year_summary.csv")

    if 'activityType' in df.columns:
        activity_summary.to_csv(f"{output_dir}/activity_summary.csv")

    # Create a comprehensive report
    with open(f"{output_dir}/analysis_report.txt", "w") as f:
        f.write("=== CARGO OPERATIONS ANALYSIS REPORT ===\n\n")
        f.write(f"Total Records: {df.shape[0]}\n\n")

        f.write("=== SUMMARY STATISTICS ===\n")
        for i, row in summary_df.iterrows():
            f.write(f"{row['Metric']}: {row['Value']}\n")

        f.write("\n=== VEHICLE INFORMATION ===\n")
        f.write(f"Total unique vehicles: {df['vehicleId'].nunique()}\n")
        f.write(f"Vehicle model years range: {df['vehicleModelYear'].min()} to {df['vehicleModelYear'].max()}\n")
        f.write(f"Vehicle fuel types: {', '.join(df['vehicleFuelType'].unique())}\n")
        f.write(f"Vehicle classes: {', '.join(df['vehicleClass'].unique())}\n")


        f.write("\n=== CORRELATION ANALYSIS ===\n")
        f.write("Correlation with Operation Duration:\n")
        for col in numeric_df.columns:
            if col != 'operationDurationInMin':
                correlation = df['operationDurationInMin'].corr(df[col])
                f.write(f"{col}: {correlation:.4f}\n")

        f.write("\n=== NOTABLE OBSERVATIONS ===\n")
        # Add any notable observations here based on the analysis
        if df['operationDurationInMin'].max() > 1000:
            f.write("- Some operations have extremely long durations (over 16 hours)\n")

        if zero_durations.shape[0] > 0:
            f.write(f"- {zero_durations.shape[0]} operations have zero duration\n")

        # Add vehicle class specific observations
        for vehicle_class in df['vehicleClass'].unique():
            class_data = df[df['vehicleClass'] == vehicle_class]
            avg_duration = class_data['operationDurationInMin'].mean()
            f.write(f"- {vehicle_class} vehicles have an average operation duration of {avg_duration:.2f} minutes\n")

        f.write("\n=== CONCLUSION ===\n")
        f.write(
            "This analysis provides insights into the cargo operations data, highlighting patterns in operation durations across different vehicle types, model years, and activity types.\n")

    print(f"\nAnalysis complete. Results saved to {output_dir}/")
    print(f"A comprehensive report has been generated at {output_dir}/analysis_report.txt")

    return df


def main():
    """
    Main function to run the analysis
    """
    print("Starting Commercial Vehicle Operation Duration Analysis")

    # File paths
    austin_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/data/Austin_2017")
    frism_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/frism/2024-01-23/Baseline")
    output_dir = os.path.expanduser("~/Workspace/Simulation/sfbay/data/Austin_2017/output")

    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    # Process Austin CV Survey data and generate CSV file
    try:
        print("\nProcessing Austin Commercial Vehicle Survey data...")
        trip_data_path = f"{austin_dir}/Raw 2017-2018 Austin Commercial Vehicle Travel Survey Data for UT and ANL.xlsx"

        # Load, process, and generate CSV file
        austin_data = load_and_process_austin_data(trip_data_path, output_dir)
        austin_data["cargoWeightPUdiffDO"] = austin_data["cargoWeightPU"] - austin_data["cargoWeightDO"]

        analyze_cargo_operations(austin_data, output_dir)

        plot_duration_vs_weight(
            austin_data[austin_data["activityType"]=="Pick up Cargo"],
            duration_col='operationDurationInMin',
            weight_col='cargoWeightPU',
            group_col=None,
            output_file=os.path.join(output_dir, 'austin_duration_vs_weight_pu.png')
        )

        plot_duration_vs_weight(
            austin_data[austin_data["activityType"]=="Delivery of Cargo"],
            duration_col='operationDurationInMin',
            weight_col='cargoWeightDO',
            group_col=None,
            output_file=os.path.join(output_dir, 'austin_duration_vs_weight_do.png')
        )

        plot_duration_vs_weight(
            austin_data[austin_data["activityType"]=="Deliver and Pick up Cargo"],
            duration_col='operationDurationInMin',
            weight_col='cargoWeightPUdiffDO',
            group_col=None,
            output_file=os.path.join(output_dir, 'austin_duration_vs_weight_pu_diff_do.png')
        )

    except Exception as e:
        print(f"Error processing Austin data: {e}")
        import traceback
        traceback.print_exc()

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

    print("\nAnalysis complete!")
    print(f"Results saved to {output_dir} directory")


if __name__ == "__main__":
    main()