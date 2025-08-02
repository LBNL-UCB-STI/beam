#!/usr/bin/env python3
"""
HPMS VMT Calculator
Reads CA_HPMS_TMC2017.gdb and calculates VMT (Vehicle Miles Traveled) by F_SYSTEM
for layer: CA_HPMSPR2016_TMC2017
VMT = AADT * Shape_Length * 365
"""

import geopandas as gpd
import pandas as pd
import fiona
import os


def calculate_vmt_by_fsystem(gdb_path, target_layer="CA_HPMSPR2016_TMC2017"):
    """
    Calculate VMT by F_SYSTEM from HPMS geodatabase for specific layer

    Args:
        gdb_path (str): Path to the .gdb file
        target_layer (str): Specific layer to process
    """

    # F_SYSTEM to road class lookup
    fsystem_to_roadclass_lookup = {
        1.0: 'Interstate',
        2.0: 'Freeways and Expressways',
        3.0: 'Principal Arterial',
        4.0: 'Minor Arterial',
        5.0: 'Major Collector',
        6.0: 'Minor Collector',
        7.0: 'Local'
    }

    print("HPMS VMT Calculator")
    print("=" * 50)
    print(f"Target layer: {target_layer}")

    # Check if geodatabase exists and list layers
    try:
        layers = fiona.listlayers(gdb_path)
        print(f"\nFound {len(layers)} layers in {gdb_path}:")
        for i, layer in enumerate(layers, 1):
            marker = " ← TARGET" if layer == target_layer else ""
            print(f"  {i}. {layer}{marker}")

        # Check if target layer exists
        if target_layer not in layers:
            print(f"\n❌ Target layer '{target_layer}' not found!")
            print("Available layers are listed above.")
            return

    except Exception as e:
        print(f"Error reading geodatabase: {e}")
        return

    # Process the target layer
    try:
        print(f"\nProcessing layer: {target_layer}")
        print("-" * 50)

        # Read the layer
        gdf = gpd.read_file(gdb_path, layer=target_layer)
        print(f"Records in layer: {len(gdf)}")

        # Check for required columns
        required_cols = ['AADT', 'Shape_Length', 'F_SYSTEM']
        available_cols = gdf.columns.tolist()

        print(f"Available columns ({len(available_cols)}): {', '.join(available_cols)}")

        missing_cols = [col for col in required_cols if col not in available_cols]
        if missing_cols:
            print(f"⚠️  Missing required columns: {missing_cols}")

            # Try alternative column names
            alt_names = {
                'AADT': ['aadt', 'Aadt', 'ANNUAL_AVERAGE_DAILY_TRAFFIC'],
                'Shape_Length': ['shape_length', 'SHAPE_LENGTH', 'Shape_Leng', 'SHAPE_LENG', 'LENGTH', 'Miles',
                                 'MILES'],
                'F_SYSTEM': ['f_system', 'F_System', 'FUNCTIONAL_CLASS', 'FC', 'FSYSTEM']
            }

            for req_col in missing_cols:
                found = False
                for alt_col in alt_names.get(req_col, []):
                    if alt_col in available_cols:
                        gdf = gdf.rename(columns={alt_col: req_col})
                        print(f"✓ Found alternative column: {alt_col} -> {req_col}")
                        found = True
                        break
                if not found:
                    print(f"✗ Could not find column for {req_col}")

            # Check again
            missing_cols = [col for col in required_cols if col not in gdf.columns]
            if missing_cols:
                print(f"❌ Cannot proceed. Missing columns: {missing_cols}")
                return

        # Clean and prepare data
        print(f"\nData preparation...")

        # Show data types and sample values
        for col in ['AADT', 'Shape_Length', 'F_SYSTEM']:
            print(f"  {col}: {gdf[col].dtype}, sample values: {gdf[col].dropna().head(3).tolist()}")

        # Convert to numeric and handle missing values
        for col in ['AADT', 'Shape_Length', 'F_SYSTEM']:
            gdf[col] = pd.to_numeric(gdf[col], errors='coerce')

        # Remove rows with missing critical data
        initial_count = len(gdf)
        gdf = gdf.dropna(subset=['AADT', 'Shape_Length', 'F_SYSTEM'])
        final_count = len(gdf)

        if initial_count != final_count:
            print(f"Removed {initial_count - final_count} rows with missing data")

        if len(gdf) == 0:
            print("❌ No valid data remaining after cleaning")
            return

        # Convert Shape_Length from feet to miles (assuming Shape_Length is in feet)
        # If Shape_Length is already in miles, comment out the next line
        gdf['Length_Miles'] = gdf['Shape_Length'] / 5280  # Convert feet to miles

        # Calculate VMT (AADT * Length_Miles * 365)
        gdf['VMT'] = gdf['AADT'] * gdf['Length_Miles'] * 365

        # Map F_SYSTEM to road class
        gdf['Road_Class'] = gdf['F_SYSTEM'].map(fsystem_to_roadclass_lookup)

        # Handle unmapped F_SYSTEM values
        unmapped = gdf[gdf['Road_Class'].isna()]
        if len(unmapped) > 0:
            unique_unmapped = unmapped['F_SYSTEM'].unique()
            print(f"⚠️  Unmapped F_SYSTEM values: {unique_unmapped}")
            gdf.loc[gdf['Road_Class'].isna(), 'Road_Class'] = 'Unknown'

        # Calculate VMT by F_SYSTEM
        vmt_summary = gdf.groupby(['F_SYSTEM', 'Road_Class']).agg({
            'VMT': 'sum',
            'AADT': 'mean',
            'Length_Miles': 'sum',
            'F_SYSTEM': 'count'
        }).round(0)

        vmt_summary.columns = ['Total_VMT', 'Avg_AADT', 'Total_Miles', 'Segment_Count']
        vmt_summary = vmt_summary.reset_index()

        # Calculate percentages
        total_vmt = vmt_summary['Total_VMT'].sum()
        vmt_summary['VMT_Percent'] = (vmt_summary['Total_VMT'] / total_vmt * 100).round(1)

        # Sort by F_SYSTEM
        vmt_summary = vmt_summary.sort_values('F_SYSTEM')

        print(f"\n" + "=" * 60)
        print(f"VMT SUMMARY BY F_SYSTEM - {target_layer}")
        print("=" * 60)
        print(vmt_summary.to_string(index=False))

        # Layer totals
        layer_total_vmt = gdf['VMT'].sum()
        layer_total_miles = gdf['Length_Miles'].sum()
        layer_avg_aadt = gdf['AADT'].mean()

        print(f"\nOverall Totals:")
        print(f"  Total VMT: {layer_total_vmt:,.0f}")
        print(f"  Total Miles: {layer_total_miles:,.1f}")
        print(f"  Average AADT: {layer_avg_aadt:,.0f}")
        print(f"  Total Segments: {len(gdf):,}")

        # Top road classes by VMT
        print(f"\nRoad Classes Ranked by VMT:")
        top_classes = vmt_summary.nlargest(len(vmt_summary), 'Total_VMT')[['Road_Class', 'Total_VMT', 'VMT_Percent']]
        for i, (_, row) in enumerate(top_classes.iterrows(), 1):
            print(f"  {i}. {row['Road_Class']}: {row['Total_VMT']:,.0f} ({row['VMT_Percent']}%)")

        # Show some sample data
        print(f"\nSample data (first 5 records):")
        sample_cols = ['F_SYSTEM', 'Road_Class', 'AADT', 'Length_Miles', 'VMT']
        available_sample_cols = [col for col in sample_cols if col in gdf.columns]
        print(gdf[available_sample_cols].head(5).to_string(index=False))

        # Data quality checks
        print(f"\nData Quality Summary:")
        print(f"  AADT range: {gdf['AADT'].min():,.0f} - {gdf['AADT'].max():,.0f}")
        print(f"  Length range: {gdf['Length_Miles'].min():.3f} - {gdf['Length_Miles'].max():.1f} miles")
        print(f"  VMT range: {gdf['VMT'].min():,.0f} - {gdf['VMT'].max():,.0f}")

    except Exception as e:
        print(f"❌ Error processing layer {target_layer}: {e}")
        import traceback
        traceback.print_exc()


def main():
    """Main function"""

    # Configuration
    gdb_path = os.path.expanduser("~/Workspace/Simulation/sfbay/validation/hpms/CA_HPMS_TMC2017.gdb")
    target_layer = "CA_HPMSPR2016_TMC2017"

    # Check if required packages are installed
    try:
        import geopandas
        import fiona
        print("✓ Required packages found\n")
    except ImportError as e:
        print(f"✗ Missing required package: {e}")
        print("Install with: pip install geopandas fiona")
        return

    # Calculate VMT for specific layer
    calculate_vmt_by_fsystem(gdb_path, target_layer)

    print(f"\nScript completed!")


if __name__ == "__main__":
    main()