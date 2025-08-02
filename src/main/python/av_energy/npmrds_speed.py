import geopandas as gpd
import pandas as pd
import os


def calculate_california_speed_by_fsystem(shapefile_path, speed_data_path, speed_col='speed'):
    """
    Read NPMRDS shapefile and speed data, calculate average speeds by road category
    for California, SCAG, MTC, SACOG, Urban, and Rural regions
    """

    # Read the shapefile
    print("Reading NPMRDS shapefile...")
    gdf = gpd.read_file(shapefile_path)

    # Read speed data
    print("Reading speed data...")
    speed_data = pd.read_csv(speed_data_path)

    # F_System to road category mapping
    fsystem_to_roadclass_lookup = {
        1.0: 'Interstate',
        2.0: 'Freeways and Expressways',
        3.0: 'Principal Arterial',
        4.0: 'Minor Arterial',
        5.0: 'Major Collector',
        6.0: 'Minor Collector',
        7.0: 'Local'
    }

    # Define county mappings for each region
    scag_counties = {
        'Los Angeles', 'Orange', 'Riverside', 'San Bernardino',
        'Ventura', 'Imperial'
    }

    mtc_counties = {
        'Alameda', 'Contra Costa', 'Marin', 'Napa',
        'San Francisco', 'San Mateo', 'Santa Clara',
        'Solano', 'Sonoma'
    }

    sacog_counties = {
        'El Dorado', 'Placer', 'Sacramento', 'Yolo', 'Sutter', 'Yuba'
    }

    # All urban counties (SCAG + MTC + SACOG)
    urban_counties = scag_counties | mtc_counties | sacog_counties

    # Filter out invalid F_system values
    gdf = gdf[gdf['F_System'].notna()]

    # Map F_System to road categories
    gdf['Road_Category'] = gdf['F_System'].map(fsystem_to_roadclass_lookup)

    # Filter out any unmapped F_System values
    gdf = gdf[gdf['Road_Category'].notna()]

    # Create region classifications
    gdf['region'] = 'Rural'
    gdf.loc[gdf['County'].isin(scag_counties), 'region'] = 'SCAG'
    gdf.loc[gdf['County'].isin(mtc_counties), 'region'] = 'MTC'
    gdf.loc[gdf['County'].isin(sacog_counties), 'region'] = 'SACOG'
    gdf.loc[gdf['County'].isin(urban_counties), 'urban'] = 'Urban'
    gdf.loc[~gdf['County'].isin(urban_counties), 'urban'] = 'Rural'

    # Calculate average speed per TMC from the speed data
    print("Calculating average speeds per TMC...")
    avg_speeds = speed_data.groupby('tmc_code')[speed_col].mean().reset_index()

    # Join speed data with network data
    print("Joining speed data with network...")
    gdf = gdf.merge(avg_speeds, left_on='Tmc', right_on='tmc_code', how='inner')

    # Filter out unreasonable speeds
    gdf = gdf[(gdf[speed_col] > 0) & (gdf[speed_col] < 200)]

    print(f"Total segments with speed data: {len(gdf)}")

    # Create summary comparison table
    print("\n=== SPEED BY ROAD CATEGORY ===")
    summary_data = []

    # Order road categories by hierarchy
    road_category_order = [
        'Interstate',
        'Freeways and Expressways',
        'Principal Arterial',
        'Minor Arterial',
        'Major Collector',
        'Minor Collector',
        'Local'
    ]

    for road_category in road_category_order:
        if road_category in gdf['Road_Category'].values:
            row = {'Road_Category': road_category}

            # California average
            california_data = gdf[gdf['Road_Category'] == road_category]
            row['California'] = round(california_data[speed_col].mean(), 2) if len(california_data) > 0 else 'N/A'

            # SCAG average
            scag_data = gdf[(gdf['Road_Category'] == road_category) & (gdf['region'] == 'SCAG')]
            row['SCAG'] = round(scag_data[speed_col].mean(), 2) if len(scag_data) > 0 else 'N/A'

            # MTC average
            mtc_data = gdf[(gdf['Road_Category'] == road_category) & (gdf['region'] == 'MTC')]
            row['MTC'] = round(mtc_data[speed_col].mean(), 2) if len(mtc_data) > 0 else 'N/A'

            # SACOG average
            sacog_data = gdf[(gdf['Road_Category'] == road_category) & (gdf['region'] == 'SACOG')]
            row['SACOG'] = round(sacog_data[speed_col].mean(), 2) if len(sacog_data) > 0 else 'N/A'

            # Urban average (SCAG + MTC + SACOG)
            urban_data = gdf[(gdf['Road_Category'] == road_category) & (gdf['urban'] == 'Urban')]
            row['Urban'] = round(urban_data[speed_col].mean(), 2) if len(urban_data) > 0 else 'N/A'

            # Rural average
            rural_data = gdf[(gdf['Road_Category'] == road_category) & (gdf['urban'] == 'Rural')]
            row['Rural'] = round(rural_data[speed_col].mean(), 2) if len(rural_data) > 0 else 'N/A'

            summary_data.append(row)

    summary_df = pd.DataFrame(summary_data)
    print(summary_df.to_string(index=False))

    # Save results to CSV
    output_file = 'npmrds_speed_analysis.csv'
    summary_df.to_csv(output_file, index=False)

    print(f"\nResults saved to {output_file}")

    return summary_df, gdf


# Example usage
if __name__ == "__main__":
    shapefile_path = os.path.expanduser("~/Workspace/Simulation/sfbay/validation/npmrds/California.shp")
    speed_data_path = os.path.expanduser(
        "~/Workspace/Simulation/sfbay/validation/npmrds/al_ca_oct2018_1hr_trucks_pax.csv")

    try:
        summary, geodata = calculate_california_speed_by_fsystem(
            shapefile_path,
            speed_data_path,
            speed_col='average_speed'
        )
    except FileNotFoundError as e:
        print(f"File not found: {e}")
    except Exception as e:
        print(f"Error processing data: {e}")