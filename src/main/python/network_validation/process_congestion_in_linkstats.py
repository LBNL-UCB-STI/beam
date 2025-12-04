import pandas as pd
import os
import matplotlib.pyplot as plt
import numpy as np


def calculate_congestion_smart(df: pd.DataFrame, short_link_threshold_m: float = 50.0) -> pd.DataFrame:
    """
    Processes linkstats data with intelligent handling of short links.

    For normal links (>threshold): Uses VHT/mile (velocity_hpm)
    For short links (<=threshold): Uses absolute VHT and flags them separately

    Args:
        df (pd.DataFrame): The linkstats data
        short_link_threshold_m (float): Threshold in meters to classify short links

    Returns:
        pd.DataFrame: DataFrame with congestion metrics and short_link flag
    """
    # 1. Data Cleaning
    required_cols = ['link', 'length', 'volume', 'traveltime']
    for col in required_cols:
        if col not in df.columns:
            print(f"Error: Required column '{col}' is missing.")
            return pd.DataFrame()

    df['length'] = pd.to_numeric(df['length'], errors='coerce')
    df['volume'] = pd.to_numeric(df['volume'], errors='coerce')
    df['traveltime'] = pd.to_numeric(df['traveltime'], errors='coerce')
    df = df.dropna(subset=['length', 'volume', 'traveltime']).copy()

    # 2. Calculate basic metrics
    df['vht'] = df['volume'] * (df['traveltime'] / 3600.0)
    df['length_m'] = df['length']  # Length is already in meters
    df['length_miles'] = df['length'] / 1609.34  # Convert meters to miles

    # 3. Flag short links
    df['is_short_link'] = df['length_m'] <= short_link_threshold_m

    # 4. Calculate velocity_hpm ONLY for normal links
    # For short links, use absolute VHT as the congestion metric
    df['velocity_hpm'] = np.where(
        df['is_short_link'],
        np.nan,  # Don't calculate for short links
        df['vht'] / df['length_miles']
    )

    # 5. Unified congestion metric for ranking
    # For normal links: velocity_hpm
    # For short links: VHT normalized by a fixed reference (e.g., per 100m)
    df['congestion_metric'] = np.where(
        df['is_short_link'],
        df['vht'] * (100.0 / df['length_m']),  # Normalize to VHT per 100m
        df['velocity_hpm']
    )

    return df


def analyze_congestion_by_category(df: pd.DataFrame, short_link_threshold_m: float = 50.0):
    """
    Analyze congestion separately for normal and short links.
    """
    print("\n" + "=" * 80)
    print("CONGESTION ANALYSIS - SEGMENTED BY LINK LENGTH")
    print("=" * 80)

    # Separate datasets
    normal_links = df[~df['is_short_link']].copy()
    short_links = df[df['is_short_link']].copy()

    total_records = len(df)
    normal_count = len(normal_links)
    short_count = len(short_links)

    print(f"\nDataset Summary:")
    print(f"   Total link-hour records: {total_records:,}")
    print(f"   Normal links (>{short_link_threshold_m}m): {normal_count:,} ({100 * normal_count / total_records:.1f}%)")
    print(f"   Short links (≤{short_link_threshold_m}m): {short_count:,} ({100 * short_count / total_records:.1f}%)")

    # Analyze normal links
    if not normal_links.empty:
        print("\n" + "-" * 80)
        print(f"NORMAL LINKS (>{short_link_threshold_m}m) - Using VHT/Mile Metric")
        print("-" * 80)

        # Filter out zero values
        congested_normal = normal_links[normal_links['velocity_hpm'] > 0]

        if not congested_normal.empty:
            threshold_99 = congested_normal['velocity_hpm'].quantile(0.99)
            highly_congested = congested_normal[congested_normal['velocity_hpm'] >= threshold_99]

            print(f"\n   99th Percentile VHT/Mile: {threshold_99:,.2f}")
            print(f"   Records exceeding threshold: {len(highly_congested):,}")

            # Top 10 most congested normal links
            top_normal = congested_normal.groupby('link').agg(
                max_vht_per_mile=('velocity_hpm', 'max'),
                avg_length_m=('length_m', 'mean'),
                total_vht=('vht', 'sum'),
                congested_hours=('velocity_hpm', 'count')
            ).sort_values(by='max_vht_per_mile', ascending=False).head(10)

            print("\n   Top 10 Most Congested Normal Links:")
            print("   " + "-" * 76)
            print(f"   {'Link':<10} {'Max VHT/mi':<15} {'Avg Length':<12} {'Total VHT':<12} {'Hours':<8}")
            print("   " + "-" * 76)
            for link_id, row in top_normal.iterrows():
                print(f"   {str(link_id):<10} {row['max_vht_per_mile']:>13,.1f}  "
                      f"{row['avg_length_m']:>10,.0f}m  {row['total_vht']:>10,.1f}h  "
                      f"{row['congested_hours']:>6.0f}")

    # Analyze short links
    if not short_links.empty:
        print("\n" + "-" * 80)
        print(f"SHORT LINKS (<={short_link_threshold_m}m) - Using Absolute VHT")
        print("-" * 80)

        congested_short = short_links[short_links['vht'] > 0]

        if not congested_short.empty:
            threshold_99_short = congested_short['vht'].quantile(0.99)
            highly_congested_short = congested_short[congested_short['vht'] >= threshold_99_short]

            print(f"\n   99th Percentile Absolute VHT: {threshold_99_short:,.2f} vehicle-hours")
            print(f"   Records exceeding threshold: {len(highly_congested_short):,}")

            # Top 10 most congested short links
            top_short = congested_short.groupby('link').agg(
                max_vht=('vht', 'max'),
                avg_length_m=('length_m', 'mean'),
                total_vht=('vht', 'sum'),
                congested_hours=('vht', 'count')
            ).sort_values(by='max_vht', ascending=False).head(10)

            print("\n   Top 10 Most Congested Short Links:")
            print("   " + "-" * 76)
            print(f"   {'Link':<10} {'Max VHT':<15} {'Avg Length':<12} {'Total VHT':<12} {'Hours':<8}")
            print("   " + "-" * 76)
            for link_id, row in top_short.iterrows():
                print(f"   {str(link_id):<10} {row['max_vht']:>13,.2f}  "
                      f"{row['avg_length_m']:>10,.1f}m  {row['total_vht']:>10,.1f}h  "
                      f"{row['congested_hours']:>6.0f}")

            # Additional insights for short links
            print("\n   SHORT LINK INSIGHTS:")
            short_link_ids = short_links['link'].unique()
            print(f"   • {len(short_link_ids):,} unique short links in network")

            # Find extremely short links with high traffic
            very_short = short_links[short_links['length_m'] < 15]
            if not very_short.empty:
                total_vht_very_short = very_short['vht'].sum()
                print(f"   • {len(very_short['link'].unique()):,} links under 15m")
                print(f"   • Total VHT on sub-15m links: {total_vht_very_short:,.1f} vehicle-hours")


def plot_histogram_segmented(df: pd.DataFrame, short_link_threshold_m: float = 50.0):
    """
    Create side-by-side histograms for normal and short links.
    """
    normal_links = df[~df['is_short_link']]
    short_links = df[df['is_short_link']]

    fig, axes = plt.subplots(1, 2, figsize=(16, 6))

    # Plot 1: Normal links (VHT/mile)
    if not normal_links.empty:
        ax1 = axes[0]
        normal_positive = normal_links[normal_links['velocity_hpm'] > 0]['velocity_hpm']

        if not normal_positive.empty:
            ax1.hist(normal_positive, bins=50, edgecolor='black', log=True, color='steelblue')
            ax1.set_title(f'Normal Links (>{short_link_threshold_m}m)\nVHT per Mile Distribution')
            ax1.set_xlabel('VHT per Mile')
            ax1.set_ylabel('Frequency (Log Scale)')
            ax1.grid(axis='y', alpha=0.3)

            # Add 99th percentile line
            p99 = normal_positive.quantile(0.99)
            ax1.axvline(p99, color='red', linestyle='--', linewidth=2,
                        label=f'99th percentile: {p99:,.0f}')
            ax1.legend()

    # Plot 2: Short links (absolute VHT)
    if not short_links.empty:
        ax2 = axes[1]
        short_positive = short_links[short_links['vht'] > 0]['vht']

        if not short_positive.empty:
            ax2.hist(short_positive, bins=50, edgecolor='black', log=True, color='coral')
            ax2.set_title(f'Short Links (≤{short_link_threshold_m}m)\nAbsolute VHT Distribution')
            ax2.set_xlabel('Vehicle-Hours Traveled')
            ax2.set_ylabel('Frequency (Log Scale)')
            ax2.grid(axis='y', alpha=0.3)

            # Add 99th percentile line
            p99 = short_positive.quantile(0.99)
            ax2.axvline(p99, color='red', linestyle='--', linewidth=2,
                        label=f'99th percentile: {p99:.2f}')
            ax2.legend()

    plt.tight_layout()
    plt.show()


def identify_problem_links(df: pd.DataFrame) -> pd.DataFrame:
    """
    Identify links that might be causing simulation artifacts.
    Returns a summary DataFrame of problematic links.
    """
    print("\n" + "=" * 80)
    print("NETWORK TOPOLOGY ANALYSIS")
    print("=" * 80)

    # Group by link to get per-link statistics
    link_stats = df.groupby('link').agg(
        avg_length_m=('length_m', 'mean'),
        total_vht=('vht', 'sum'),
        max_volume=('volume', 'max'),
        hours_with_traffic=('volume', lambda x: (x > 0).sum())
    ).reset_index()

    # Identify very short links with high traffic
    very_short_busy = link_stats[
        (link_stats['avg_length_m'] < 15) &
        (link_stats['total_vht'] > 100)  # More than 100 vehicle-hours over simulation
        ].sort_values(by='total_vht', ascending=False)

    if not very_short_busy.empty:
        print(f"\nWARNING: Found {len(very_short_busy)} links under 15m with >100 VHT:")
        print("   These may be artifacts from R5 transit stop splitting\n")
        print(f"   {'Link':<10} {'Length':<12} {'Total VHT':<15} {'Max Volume':<12} {'Active Hours':<15}")
        print("   " + "-" * 76)

        for _, row in very_short_busy.head(15).iterrows():
            print(f"   {row['link']:<10} {row['avg_length_m']:>10.1f}m  "
                  f"{row['total_vht']:>13,.1f}h  {row['max_volume']:>10,.0f}  "
                  f"{row['hours_with_traffic']:>13.0f}")

        print(f"\n   RECOMMENDATION:")
        print(f"   Consider modifying R5 to not split edges shorter than ~30m")
        print(f"   This would reduce artifacts while preserving network topology")

    return very_short_busy


if __name__ == '__main__':
    print("\n" + "=" * 80)
    print("TRAFFIC CONGESTION ANALYSIS")
    print("=" * 80)

    linkstats_file = os.path.expanduser(
        '~/Workspace/Simulation/seattle/beam-runs/calibration--jdeq--20251126/'
        'seattle-pilates-calibration--jdeq--cbg120fwc--FC10-0-20251126-165230/'
        '3.linkstats.csv')

    # Load data
    try:
        df_linkstats = pd.read_csv(linkstats_file)
        print(f"\nLoaded {len(df_linkstats):,} link-hour records")
    except FileNotFoundError:
        print(f"\nError: File not found: {linkstats_file}")
        exit()
    except Exception as e:
        print(f"\nFailed to read file: {e}")
        exit()

    # Set threshold for short links (in meters)
    SHORT_LINK_THRESHOLD = 50.0  # Adjust this value as needed

    # Process with smart handling
    processed_df = calculate_congestion_smart(df_linkstats, SHORT_LINK_THRESHOLD)

    if not processed_df.empty:
        # Main analysis
        analyze_congestion_by_category(processed_df, SHORT_LINK_THRESHOLD)

        # Identify problem links
        problem_links = identify_problem_links(processed_df)

        # Visualizations
        print("\n" + "=" * 80)
        print("Generating visualizations...")
        print("=" * 80)
        plot_histogram_segmented(processed_df, SHORT_LINK_THRESHOLD)

        # Optional: Save problem links to CSV
        if not problem_links.empty:
            output_file = '/tmp/problem_links.csv'
            problem_links.to_csv(output_file, index=False)
            print(f"\nSaved problem links to: {output_file}")

    else:
        print("\nAnalysis failed - no valid data processed")