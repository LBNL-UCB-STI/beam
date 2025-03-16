#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Script to map network links to OSM geometries and intersect with ISRM grid.
The result splits each link by ISRM polygon and calculates the proportion
of the link length in each polygon.
"""

import logging
import os
import re
import sys

import geopandas as gpd
import pandas as pd
from tqdm import tqdm

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def parse_other_tags(other_tags):
    """Parse the 'other_tags' column from OSM PBF file to extract key-value pairs."""
    if not other_tags or pd.isna(other_tags):
        return {}

    # Extract key-value pairs using regex
    pattern = r'"([^"]+)"=>"([^"]+)"'
    matches = re.findall(pattern, other_tags)
    return {key: value for key, value in matches}


def process_network_isrm_intersection(network_csv_path, osm_pbf_path, osm_gpkg_path, isrm_grid_path, output_path):
    """
    Process the intersection of network links with ISRM grid polygons.

    Args:
        network_csv_path (str): Path to network CSV file with attributeOrigId, linkId, linkLength
        osm_pbf_path (str): Path to OSM PBF file with osm_id and other_tags
        osm_gpkg_path (str): Path to OSM GPKG network with egde_id and geometry
        isrm_grid_path (str): Path to ISRM grid shapefile with isrm column
        output_path (str): Path to output file

    Returns:
        gpd.GeoDataFrame: The resulting GeoDataFrame with intersection results
    """
    # 1. Load network CSV
    logger.info(f"Loading network CSV from {network_csv_path}")
    try:
        network_df = pd.read_csv(network_csv_path)
        required_cols = ['attributeOrigId', 'linkId', 'linkLength']
        if not all(col in network_df.columns for col in required_cols):
            missing = [col for col in required_cols if col not in network_df.columns]
            logger.error(f"Missing required columns in network CSV: {missing}")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load network CSV: {e}")
        sys.exit(1)

    # Convert attributeOrigId to int for matching
    network_df['attributeOrigId'] = network_df['attributeOrigId'].astype(int)

    # 2. Load OSM PBF file
    logger.info(f"Loading OSM PBF from {osm_pbf_path}")
    try:
        osm_gdf = gpd.read_file(osm_pbf_path)
        if 'osm_id' not in osm_gdf.columns or 'other_tags' not in osm_gdf.columns:
            logger.error("OSM PBF file is missing 'osm_id' or 'other_tags' columns")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load OSM PBF: {e}")
        sys.exit(1)

    # Convert osm_id to int and parse other_tags
    osm_gdf['osm_id'] = osm_gdf['osm_id'].astype(int)

    # Parse other_tags to extract edge_id
    logger.info("Parsing other_tags column to extract edge_id")
    osm_gdf['parsed_tags'] = osm_gdf['other_tags'].apply(parse_other_tags)
    osm_gdf['edge_id'] = osm_gdf['parsed_tags'].apply(lambda x: x.get('egde_id', None))

    # 3. Load OSM GPKG network
    logger.info(f"Loading OSM GPKG network from {osm_gpkg_path}")
    try:
        gpkg_gdf = gpd.read_file(osm_gpkg_path)
        if 'egde_id' not in gpkg_gdf.columns:
            logger.error("OSM GPKG file is missing 'egde_id' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load OSM GPKG: {e}")
        sys.exit(1)

    # 4. Load ISRM grid
    logger.info(f"Loading ISRM grid from {isrm_grid_path}")
    try:
        isrm_gdf = gpd.read_file(isrm_grid_path)
        if 'isrm' not in isrm_gdf.columns:
            logger.error("ISRM grid file is missing 'isrm' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load ISRM grid: {e}")
        sys.exit(1)

    # Ensure ISRM grid has the same CRS as GPKG network
    if isrm_gdf.crs != gpkg_gdf.crs:
        logger.info(f"Reprojecting ISRM grid to match OSM GPKG CRS: {gpkg_gdf.crs}")
        isrm_gdf = isrm_gdf.to_crs(gpkg_gdf.crs)

    # 5. Map linkId to OSM geometry through the two conditions
    logger.info("Mapping network links to OSM geometries")

    # First mapping: int(attributeOrigId) == int(osm_id)
    mapping_df = pd.merge(
        network_df[['attributeOrigId', 'linkId', 'linkLength']],
        osm_gdf[['osm_id', 'edge_id']],
        left_on='attributeOrigId',
        right_on='osm_id',
        how='inner'
    )

    # Second mapping: edge_id == egde_id
    mapping_df = pd.merge(
        mapping_df,
        gpkg_gdf[['egde_id', 'geometry']],
        left_on='edge_id',
        right_on='egde_id',
        how='inner'
    )

    # Create GeoDataFrame from mapping results
    mapping_gdf = gpd.GeoDataFrame(mapping_df, geometry='geometry', crs=gpkg_gdf.crs)

    logger.info(f"Successfully mapped {len(mapping_gdf)} links to OSM geometries")

    # 6. Intersect with ISRM grid
    logger.info("Intersecting mapped links with ISRM grid")

    # Create empty list to store results
    intersection_results = []

    # Iterate through each link
    for idx, link_row in tqdm(mapping_gdf.iterrows(), total=len(mapping_gdf), desc="Processing links"):
        link_id = link_row['linkId']
        link_geom = link_row.geometry
        original_length = link_row['linkLength']

        # Get the actual link length from geometry for proportion calculation
        link_geom_length = link_geom.length

        # Find all ISRM polygons that intersect this link
        possible_matches = isrm_gdf[isrm_gdf.geometry.intersects(link_geom)]

        if len(possible_matches) == 0:
            logger.warning(f"No intersection found for link ID: {link_id}")
            continue

        # For each intersecting ISRM polygon, get the actual intersection
        for match_idx, match_row in possible_matches.iterrows():
            isrm_id = match_row['isrm']
            isrm_geom = match_row.geometry

            # Get the actual intersection geometry
            intersection_geom = link_geom.intersection(isrm_geom)

            # Skip empty geometries
            if intersection_geom.is_empty:
                continue

            # Calculate the proportion of the link length in this ISRM polygon
            intersection_length = intersection_geom.length
            proportion = intersection_length / link_geom_length if link_geom_length > 0 else 0
            proportional_length = original_length * proportion

            # Create a record for this intersection
            result = {
                'isrm_id': isrm_id,
                'link_id': link_id,
                'original_link_length': original_length,
                'proportion': proportion,
                'proportional_length': proportional_length,
                'isrm_link_id': f"{isrm_id}-{link_id}",
                'geometry': intersection_geom
            }

            # Copy all attributes from link
            for key, value in link_row.items():
                if key not in ['geometry', 'linkId', 'linkLength'] and key not in result:
                    result[f'link_{key}'] = value

            # Copy all attributes from ISRM polygon
            for key, value in match_row.items():
                if key not in ['geometry', 'isrm'] and key not in result:
                    result[f'isrm_{key}'] = value

            intersection_results.append(result)

    logger.info(f"Intersection produced {len(intersection_results)} results")

    # Create a GeoDataFrame from results
    if not intersection_results:
        logger.error("No intersections found")
        sys.exit(1)

    result_gdf = gpd.GeoDataFrame(intersection_results, geometry='geometry', crs=gpkg_gdf.crs)

    # Save results
    logger.info(f"Saving results to {output_path}")
    output_dir = os.path.dirname(output_path)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # Determine output format based on file extension
    extension = os.path.splitext(output_path)[1].lower()
    if extension == '.gpkg':
        result_gdf.to_file(output_path, driver='GPKG')
    elif extension == '.shp':
        result_gdf.to_file(output_path)
    elif extension == '.geojson':
        result_gdf.to_file(output_path, driver='GeoJSON')
    elif extension == '.csv':
        # For CSV, we need to export geometry as WKT
        result_gdf['geometry_wkt'] = result_gdf.geometry.apply(lambda geom: geom.wkt)
        result_df = pd.DataFrame(result_gdf.drop(columns='geometry'))
        result_df.to_csv(output_path, index=False)
    else:
        logger.info(f"Unrecognized output format: {extension}, using GPKG format")
        result_gdf.to_file(output_path, driver='GPKG')

    logger.info("Processing complete")
    return result_gdf


def main():
    """Main execution function with hardcoded paths."""
    # Hardcoded paths
    network_csv_path = "data/network.csv"
    osm_pbf_path = "data/osm_roads.pbf"
    osm_gpkg_path = "data/osm_network.gpkg"
    isrm_grid_path = "data/isrm_grid.shp"
    output_path = "results/network_isrm_intersection.gpkg"

    # Process the intersection
    process_network_isrm_intersection(
        network_csv_path=network_csv_path,
        osm_pbf_path=osm_pbf_path,
        osm_gpkg_path=osm_gpkg_path,
        isrm_grid_path=isrm_grid_path,
        output_path=output_path
    )


if __name__ == "__main__":
    main()