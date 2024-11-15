#!/usr/bin/env python3

import pandas as pd
import geopandas as gpd
from shapely.geometry import LineString, Point
import os
import logging


def setup_logging(log_file):
    """
    Set up logging configuration.
    """
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(levelname)s - %(message)s',
        handlers=[
            logging.FileHandler(log_file, mode='w'),
            logging.StreamHandler()
        ]
    )


def log_and_print(message, level=logging.INFO):
    """
    Log message and print to console.
    """
    if level == logging.INFO:
        logging.info(message)
    elif level == logging.WARNING:
        logging.warning(message)
    elif level == logging.ERROR:
        logging.error(message)


def validate_network_file(network_df):
    """
    Validate the network file has all required columns and valid data.
    """
    required_columns = [
        'linkId', 'linkLength', 'linkFreeSpeed', 'linkCapacity',
        'numberOfLanes', 'linkModes', 'attributeOrigId', 'attributeOrigType',
        'fromNodeId', 'toNodeId', 'fromLocationX', 'fromLocationY',
        'toLocationX', 'toLocationY'
    ]

    missing_columns = [col for col in required_columns if col not in network_df.columns]
    if missing_columns:
        raise ValueError(f"Missing required columns in network file: {missing_columns}")


def convert_network_to_geojson(network_file, projected_crs_epsg):
    """
    Convert network CSV file to GeoJSON format.

    Parameters:
    network_file (str): Path to the network.csv.gz file
    projected_crs_epsg (int): EPSG code for the projected CRS (default: 26910 for NAD83/UTM zone 10N)

    Returns:
    str: Path to the created GeoJSON file
    """
    try:
        log_and_print(f"Reading network file: {network_file}")
        network_name = os.path.splitext(os.path.splitext(os.path.basename(network_file))[0])[0]
        network_df = pd.read_csv(network_file)

        # Validate the input file
        validate_network_file(network_df)

        # Filter for car modes
        car_modes = ['car', 'car;bike', 'car;walk;bike']
        network_filtered = network_df[network_df['linkModes'].isin(car_modes)]
        log_and_print(f"Filtered network for car modes. Features remaining: {len(network_filtered):,}")

        # Create GeoDataFrame with projected CRS
        gdf = gpd.GeoDataFrame(
            network_filtered,
            geometry=[
                LineString([Point(row.fromLocationX, row.fromLocationY),
                            Point(row.toLocationX, row.toLocationY)])
                for idx, row in network_filtered.iterrows()
            ],
            crs=f"EPSG:{projected_crs_epsg}"
        )

        # Remove coordinate columns as they're now in the geometry
        gdf = gdf.drop(columns=['fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY'])

        # Convert to WGS84 for GeoJSON output
        gdf_wgs84 = gdf.to_crs(epsg=4326)

        # Create output path and save file
        output_dir = os.path.dirname(network_file)
        output_file = os.path.join(output_dir, f"{network_name}.geojson")

        # Save to GeoJSON
        gdf_wgs84.to_file(output_file, driver='GeoJSON')

        # Log statistics
        log_and_print(f"\n[NETWORK] Network statistics:")
        log_and_print(f"Total features: {len(gdf_wgs84):,}")
        log_and_print(f"Total network length: {gdf_wgs84['linkLength'].sum() / 1000:.2f} km")
        log_and_print(f"Unique road types: {gdf_wgs84['attributeOrigType'].nunique()}")

        # Road type distribution
        log_and_print("\nTop 5 road types distribution:")
        road_type_dist = gdf_wgs84['attributeOrigType'].value_counts().head()
        for road_type, count in road_type_dist.items():
            log_and_print(f"  {road_type}: {count:,} links")

        log_and_print(f"\n[OUTPUT] GeoJSON file saved to: {output_file}")
        return output_file

    except Exception as e:
        log_and_print(f"Error converting network to GeoJSON: {str(e)}", logging.ERROR)
        raise


def main():
    network_file = os.path.expanduser(
        f"~/Workspace/Simulation/seattle/beam-runs/2024-04-20/2018_Baseline_RPS/network.RPS.csv.gz")
    network_dir = os.path.dirname(network_file)
    network_name = os.path.splitext(os.path.splitext(os.path.basename(network_file))[0])[0]

    # Setup logging
    log_file = os.path.join(network_dir, f'network_to_geojson_{network_name}.log')
    setup_logging(log_file)

    # Check if network file exists
    if not os.path.exists(network_file):
        log_and_print(f"Network file not found: {network_file}", logging.ERROR)
        return

    try:
        convert_network_to_geojson(network_file, projected_crs_epsg=32048)
        log_and_print("Conversion completed successfully")
    except Exception as e:
        log_and_print(f"Conversion failed: {str(e)}", logging.ERROR)


if __name__ == "__main__":
    main()
