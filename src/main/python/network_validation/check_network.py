import pandas as pd
import sys
from pathlib import Path


def analyze_top_k_capacity_mismatches(filepath, k=10, capacity_per_lane=2200, rank_by='deviation'):
    """
    Identify and rank top K capacity vs. lanes mismatches in OSM link data.

    Parameters:
    -----------
    filepath : str
        Path to CSV or Excel file with link data
    k : int
        Number of top mismatches to return
    capacity_per_lane : float
        Expected capacity per lane (default: 2200)
    rank_by : str
        Ranking method: 'deviation' (absolute %), 'absolute' (raw difference),
        or 'composite' (combined score)

    Returns:
    --------
    DataFrame with top K ranked mismatches
    """

    # Read file
    if filepath.endswith('.csv'):
        df = pd.read_csv(filepath)
    elif filepath.endswith(('.xlsx', '.xls')):
        df = pd.read_excel(filepath)
    else:
        raise ValueError("File must be CSV or Excel format")

    print(f"\n{'=' * 100}")
    print(f"TOP K CAPACITY VS. LANES MISMATCH DETECTOR")
    print(f"{'=' * 100}\n")
    print(f"Total links analyzed: {len(df)}")
    print(f"Capacity per lane assumption: {capacity_per_lane}")
    print(f"Ranking method: {rank_by}")
    print(f"Top K to display: {k}\n")

    # Normalize column names
    df.columns = [col.lower().strip() for col in df.columns]

    # Identify the relevant columns
    lane_col = next((col for col in df.columns if 'numberof' in col or 'lanes' in col), None)
    capacity_col = next((col for col in df.columns if 'capacity' in col), None)
    id_col = next((col for col in df.columns if 'linkid' in col or 'id' in col), None)
    length_col = next((col for col in df.columns if 'length' in col), None)
    mode_col = next((col for col in df.columns if 'mode' in col), None)

    if not all([lane_col, capacity_col, id_col]):
        print(f"ERROR: Could not find required columns")
        print(f"  Lanes column: {lane_col}")
        print(f"  Capacity column: {capacity_col}")
        print(f"  ID column: {id_col}")
        return None

    print(f"Using columns: {id_col} | {lane_col} | {capacity_col}")
    if length_col:
        print(f"             | {length_col}")
    if mode_col:
        print(f"             | {mode_col}")
    print()

    # Create working copy
    df_copy = df.copy()

    # Calculate metrics
    df_copy['expected_capacity'] = df_copy[lane_col] * capacity_per_lane
    df_copy['actual_capacity'] = df_copy[capacity_col]
    df_copy['capacity_difference'] = df_copy['actual_capacity'] - df_copy['expected_capacity']
    df_copy['percent_diff'] = (df_copy['capacity_difference'] / df_copy['expected_capacity']) * 100
    df_copy['deviation'] = abs(df_copy['percent_diff'])
    df_copy['is_outlier_lanes'] = df_copy[lane_col] % 1 != 0  # Non-integer lanes

    # Additional anomaly flags
    df_copy['zero_or_negative_capacity'] = df_copy[capacity_col] <= 0
    df_copy['zero_lanes'] = df_copy[lane_col] == 0

    # Composite anomaly score (1-10 scale)
    df_copy['anomaly_score'] = 0
    df_copy.loc[df_copy['deviation'] > 50, 'anomaly_score'] += 3
    df_copy.loc[(df_copy['deviation'] > 25) & (df_copy['deviation'] <= 50), 'anomaly_score'] += 2
    df_copy.loc[(df_copy['deviation'] > 10) & (df_copy['deviation'] <= 25), 'anomaly_score'] += 1
    df_copy.loc[df_copy['is_outlier_lanes'], 'anomaly_score'] += 2
    df_copy.loc[df_copy['zero_or_negative_capacity'], 'anomaly_score'] += 5
    df_copy.loc[df_copy['zero_lanes'], 'anomaly_score'] += 5

    # Rank based on method
    if rank_by == 'deviation':
        df_copy = df_copy.sort_values('deviation', ascending=False)
        rank_col = 'deviation'
    elif rank_by == 'absolute':
        df_copy['abs_difference'] = abs(df_copy['capacity_difference'])
        df_copy = df_copy.sort_values('abs_difference', ascending=False)
        rank_col = 'abs_difference'
    elif rank_by == 'composite':
        df_copy = df_copy.sort_values('anomaly_score', ascending=False)
        rank_col = 'anomaly_score'
    else:
        raise ValueError("rank_by must be 'deviation', 'absolute', or 'composite'")

    # Get top K
    top_k = df_copy.head(k).copy()

    print(f"{'=' * 100}")
    print(f"TOP {k} POTENTIAL MISMATCHES")
    print(f"{'=' * 100}\n")

    # Display detailed results
    for idx, (i, row) in enumerate(top_k.iterrows(), 1):
        print(f"{'─' * 100}")
        print(f"RANK #{idx}")
        print(f"{'─' * 100}")
        print(f"  Link ID:           {row[id_col]}")
        print(f"  Lanes:             {row[lane_col]}")
        print(f"  Actual Capacity:   {row['actual_capacity']:.0f}")
        print(f"  Expected Capacity: {row['expected_capacity']:.0f}")
        print(f"  Difference:        {row['capacity_difference']:+.0f} ({row['percent_diff']:+.2f}%)")

        if length_col:
            print(f"  Length:            {row[length_col]:.2f}")
        if mode_col:
            print(f"  Modes:             {row[mode_col]}")

        # Anomaly details
        issues = []
        if row['deviation'] > 50:
            issues.append(f"EXTREME deviation: {row['deviation']:.2f}%")
        elif row['deviation'] > 25:
            issues.append(f"High deviation: {row['deviation']:.2f}%")

        if row['is_outlier_lanes']:
            issues.append(f"Non-integer lanes: {row[lane_col]}")

        if row['zero_or_negative_capacity']:
            issues.append("Invalid capacity (≤0)")

        if row['zero_lanes']:
            issues.append("Zero lanes")

        if issues:
            print(f"  ⚠ Issues:          {'; '.join(issues)}")

        if rank_by == 'composite':
            print(f"  Anomaly Score:     {row['anomaly_score']:.0f}/10")

        print()

    # Summary statistics
    print(f"{'=' * 100}")
    print(f"SUMMARY STATISTICS")
    print(f"{'=' * 100}\n")

    extreme_dev = len(df_copy[df_copy['deviation'] > 50])
    high_dev = len(df_copy[(df_copy['deviation'] > 25) & (df_copy['deviation'] <= 50)])
    moderate_dev = len(df_copy[(df_copy['deviation'] > 10) & (df_copy['deviation'] <= 25)])
    non_int_lanes = len(df_copy[df_copy['is_outlier_lanes']])
    invalid_cap = len(df_copy[df_copy['zero_or_negative_capacity']])
    zero_lanes = len(df_copy[df_copy['zero_lanes']])

    print(f"  Extreme deviation (>50%):     {extreme_dev}")
    print(f"  High deviation (25-50%):      {high_dev}")
    print(f"  Moderate deviation (10-25%):  {moderate_dev}")
    print(f"  Non-integer lanes:            {non_int_lanes}")
    print(f"  Invalid capacity (≤0):        {invalid_cap}")
    print(f"  Zero lanes:                   {zero_lanes}")

    print(f"\n{'=' * 100}\n")

    return top_k


def main():
    if len(sys.argv) < 2:
        print("Usage: python detect_top_k_mismatches.py <filepath> [k] [capacity_per_lane] [rank_by]")
        print("\nParameters:")
        print("  filepath:          CSV or Excel file path (required)")
        print("  k:                 Number of top mismatches to show (default: 10)")
        print("  capacity_per_lane: Expected capacity per lane (default: 2200)")
        print("  rank_by:           Ranking method - 'deviation', 'absolute', or 'composite' (default: 'deviation')")
        print("\nExamples:")
        print("  python detect_top_k_mismatches.py data.csv")
        print("  python detect_top_k_mismatches.py data.csv 20")
        print("  python detect_top_k_mismatches.py data.xlsx 15 2200 composite")
        print("  python detect_top_k_mismatches.py data.csv 10 2500 absolute")
        sys.exit(1)

    filepath = sys.argv[1]
    k = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    capacity_per_lane = float(sys.argv[3]) if len(sys.argv) > 3 else 2200
    rank_by = sys.argv[4] if len(sys.argv) > 4 else 'deviation'

    if not Path(filepath).exists():
        print(f"ERROR: File not found: {filepath}")
        sys.exit(1)

    if rank_by not in ['deviation', 'absolute', 'composite']:
        print(f"ERROR: rank_by must be 'deviation', 'absolute', or 'composite'")
        sys.exit(1)

    if k < 1:
        print(f"ERROR: k must be at least 1")
        sys.exit(1)

    top_k = analyze_top_k_capacity_mismatches(filepath, k, capacity_per_lane, rank_by)

    # Save results to CSV
    if top_k is not None and len(top_k) > 0:
        output_file = Path(filepath).stem + f"_top_{k}_mismatches.csv"

        # Select key columns for export
        export_cols = [col for col in top_k.columns if col.lower() not in
                       ['freereg_sp', 'attribute', 'nodeid']]

        top_k[export_cols].to_csv(output_file, index=False)
        print(f"✓ Results saved to: {output_file}\n")


if __name__ == "__main__":
    main()