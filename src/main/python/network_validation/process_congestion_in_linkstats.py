import pandas as pd
import os
import matplotlib.pyplot as plt


def calculate_congestion(df: pd.DataFrame) -> pd.DataFrame:
    """
    Processes linkstats data (passed as a DataFrame) to calculate VHT and a
    normalized velocity metric (velocity_hpm).

    Args:
        df (pd.DataFrame): The linkstats data, already loaded into a pandas DataFrame.

    Returns:
        pd.DataFrame: The DataFrame with 'vht' and 'velocity_hpm' columns added.
    """
    # 1. Data Cleaning and Preparation
    required_cols = ['link', 'length', 'volume', 'traveltime']
    for col in required_cols:
        if col not in df.columns:
            print(f"Error: Required column '{col}' is missing from the data.")
            return pd.DataFrame()

    # Convert to numeric, coercing errors
    df['length'] = pd.to_numeric(df['length'], errors='coerce')
    df['volume'] = pd.to_numeric(df['volume'], errors='coerce')
    df['traveltime'] = pd.to_numeric(df['traveltime'], errors='coerce')

    # Remove rows where crucial data is NaN after conversion
    df = df.dropna(subset=['length', 'volume', 'traveltime']).copy()

    # Calculate VHT (Vehicle Hours Traveled)
    # VHT = volume (vehicles) * traveltime (seconds) / 3600
    df['vht'] = df['volume'] * (df['traveltime'] / 3600.0)

    # Calculate Velocity HPM (Hours Per Mile) - (VHT / Length in Miles)
    # Note: 1609 is the approximate conversion from meters to miles
    df['velocity_hpm'] = df['vht'] / (df['length'] / 1609.0)

    return df


def plot_histogram(df: pd.DataFrame, column: str, bins: int = 50):
    """
    Generates and displays a histogram for a specified column in the DataFrame,
    annotating each bar with the average value of the data in that bin.

    Args:
        df (pd.DataFrame): The DataFrame containing the data.
        column (str): The column name to plot (e.g., 'velocity_hpm').
        bins (int): The number of bins for the histogram.
    """
    if column not in df.columns:
        print(f"Error: Column '{column}' not found in the DataFrame.")
        return

    plt.figure(figsize=(12, 7))

    # 1. Plot the histogram, capturing the bin data (counts N, and bin edges)
    # log=True is used to capture the data for the log-scaled plot
    N, bins, patches = plt.hist(df[column], bins=bins, edgecolor='black', log=True)

    # 2. Iterate over the bins to calculate and display the average
    for i in range(len(N)):
        # Calculate the start and end of the current bin
        bin_start = bins[i]
        bin_end = bins[i + 1]

        # Filter the original data for values that fall into the current bin range
        # Use >= for bin_start and < for bin_end, matching plt.hist behavior
        data_in_bin = df[(df[column] >= bin_start) & (df[column] < bin_end)][column]

        # Check if the bin has data and a positive count
        if not data_in_bin.empty and N[i] > 0:
            # Calculate the average (mean) velocity_hpm for this bin
            average_hpm = data_in_bin.mean()

            # The height of the bar (count) is N[i]
            height = N[i]

            # Find the center of the bar for text placement
            x_center = (bin_start + bin_end) / 2

            # Format the text (using comma separators for clarity with large numbers)
            text_label = f"Avg: {average_hpm:,.2f}"

            # Annotate the bar. We place it slightly above the bar.
            # Adjust Y position for better visibility on a log scale (1.5x height)
            y_position = height * 1.5

            plt.text(x_center, y_position, text_label,
                     ha='center', va='bottom', fontsize=8, color='darkred',
                     rotation=45)  # Rotate for better fit with large numbers

    plt.title(f'Distribution of {column} with Bin Averages')
    plt.xlabel(column)
    plt.ylabel('Frequency (Number of Link-Hour Records) [Log Scale]')
    plt.grid(axis='y', alpha=0.5)
    plt.tight_layout()
    plt.show()


if __name__ == '__main__':
    print("--- Traffic Congestion Analysis ---")
    linkstats_file = os.path.expanduser(
        '~/Workspace/Simulation/seattle/beam-runs/calibration--jdeq--20251120/seattle-pilates-calibration--jdeq--cbg120fwc--FC10-0-20251120-153918/3.linkstats_unmodified.csv.gz')

    # --- Load data directly from the CSV file ---
    df_linkstats = pd.DataFrame()
    try:
        df_linkstats = pd.read_csv(linkstats_file)
        print("Successfully loaded data from the linkstats file.")
    except FileNotFoundError:
        print(f"Error: {linkstats_file} not found. Please ensure the file path is correct.")
        exit()
    except Exception as e:
        print(f"Failed to read {linkstats_file}: {e}")
        exit()

    try:
        # Pass the DataFrame to the calculation function
        processed_df = calculate_congestion(df_linkstats)

        if not processed_df.empty:
            # 1. Define the Congestion Threshold (99th Percentile)
            # Filter out zero values first, as they represent uncongested periods (the huge bar on the left)
            congested_values = processed_df[processed_df['velocity_hpm'] > 0]['velocity_hpm']

            # Check if there are enough non-zero values to calculate a percentile
            if not congested_values.empty:
                # Calculate the 99th percentile as the threshold
                threshold_99th_percentile = congested_values.quantile(0.99)

                # Filter the original processed data to find link-hours above the threshold
                congested_records = processed_df[
                    processed_df['velocity_hpm'] >= threshold_99th_percentile
                    ].sort_values(by='velocity_hpm', ascending=False)

                # 2. Print Summary of Congestion
                print(f"\n--- Congestion Threshold (99th Percentile) ---")
                print(f"Threshold value: {threshold_99th_percentile:,.4f} Vehicle-Hours/Mile")
                print(f"Number of link-hour records exceeding threshold: {len(congested_records):,}")

                print("\n--- Top 10 Most Congested Link-Hours ---")

                # Aggregate the link records to get a summary view for the top links
                top_links_summary = congested_records.groupby('link').agg(
                    max_velocity_hpm=('velocity_hpm', 'max'),
                    total_vht=('vht', 'sum'),
                    record_count=('velocity_hpm', 'count')
                ).sort_values(by='max_velocity_hpm', ascending=False).head(10).reset_index()

                top_links_summary.rename(columns={
                    'max_velocity_hpm': 'Max Congestion (VHT/mi)',
                    'total_vht': 'Total VHT (Hours)',
                    'record_count': 'Congested Hours'
                }, inplace=True)

                print(top_links_summary.to_string(index=False, float_format="{:,.4f}".format))

            else:
                print("No positive congestion values found to calculate a threshold.")

            # 3. Plot the histogram
            print("\nDisplaying Histogram of 'velocity_hpm' (Logarithmic Y-axis) with Averages...")
            plot_histogram(processed_df, 'velocity_hpm')

        else:
            print("Analysis failed or no valid data was processed.")
    except Exception as e:
        print(f"An error occurred during processing: {e}")