#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Script to map ISRM grid polygons to OSM edge geometries.
The result splits each OSM edge by ISRM polygon and calculates the proportion
of the edge length in each polygon, starting from the ISRM grid.
"""

import logging
import os
import re
import sys

import geopandas as gpd
import pandas as pd
from tqdm import tqdm

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_config_name

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


def extract_edge_length(tags_dict):
    """Extract the edge length from the tags dictionary."""
    length_str = tags_dict.get('length', None)
    if length_str is None:
        return None
    try:
        return float(length_str)
    except (ValueError, TypeError):
        return None


def process_isrm_network_intersection(isrm_grid_path, osm_pbf_path, osm_gpkg_path, output_path):
    """
    Process the intersection of ISRM grid polygons with OSM edge geometries.

    Args:
        isrm_grid_path (str): Path to ISRM grid shapefile with isrm column
        osm_pbf_path (str): Path to OSM PBF file with osm_id and other_tags
        osm_gpkg_path (str): Path to OSM GPKG network with egde_id and geometry
        output_path (str): Path to output file

    Returns:
        gpd.GeoDataFrame: The resulting GeoDataFrame with intersection results
    """
    # 1. Load ISRM grid first
    logger.info(f"Loading ISRM grid from {isrm_grid_path}")
    try:
        isrm_gdf = gpd.read_file(isrm_grid_path)
        if 'isrm' not in isrm_gdf.columns:
            logger.error("ISRM grid file is missing 'isrm' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load ISRM grid: {e}")
        sys.exit(1)

    # 2. Load OSM GPKG network for geometries
    logger.info(f"Loading OSM GPKG network from {osm_gpkg_path}")
    try:
        gpkg_gdf = gpd.read_file(osm_gpkg_path)
        if 'egde_id' not in gpkg_gdf.columns:
            logger.error("OSM GPKG file is missing 'egde_id' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load OSM GPKG: {e}")
        sys.exit(1)

    # Ensure ISRM grid has the same CRS as GPKG network
    if isrm_gdf.crs != gpkg_gdf.crs:
        logger.info(f"Reprojecting ISRM grid to match OSM GPKG CRS: {gpkg_gdf.crs}")
        isrm_gdf = isrm_gdf.to_crs(gpkg_gdf.crs)

    # 3. Load OSM PBF file for mapping
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

    # Parse other_tags to extract edge_id and length
    logger.info("Parsing other_tags column to extract edge_id and length")
    osm_gdf['parsed_tags'] = osm_gdf['other_tags'].apply(parse_other_tags)
    osm_gdf['edge_id'] = osm_gdf['parsed_tags'].apply(lambda x: x.get('edge_id', None))

    # Extract length from parsed_tags
    osm_gdf['edge_length'] = osm_gdf['parsed_tags'].apply(extract_edge_length)

    # Filter out edges without length information
    valid_osm_gdf = osm_gdf.dropna(subset=['edge_length'])
    logger.info(f"Found {len(valid_osm_gdf)} edges with valid length information out of {len(osm_gdf)} total")

    # Connect OSM data to geometries
    edge_geom_map = pd.merge(
        valid_osm_gdf[['osm_id', 'edge_id', 'edge_length']],
        gpkg_gdf[['egde_id', 'geometry']],
        left_on='edge_id',
        right_on='egde_id',
        how='inner'
    )

    # Convert to GeoDataFrame
    edge_geom_gdf = gpd.GeoDataFrame(edge_geom_map, geometry='geometry', crs=gpkg_gdf.crs)
    logger.info(f"Successfully mapped {len(edge_geom_gdf)} edges to OSM geometries")

    # Create a spatial index for OSM geometries to speed up intersection queries
    edge_geom_sindex = edge_geom_gdf.sindex

    # Process ISRM polygons and find intersections
    intersection_results = []

    logger.info("Finding intersections between ISRM polygons and OSM edges")
    for idx, isrm_row in tqdm(isrm_gdf.iterrows(), total=len(isrm_gdf), desc="Processing ISRM polygons"):
        isrm_id = isrm_row['isrm']
        isrm_geom = isrm_row.geometry

        # Find potential edge geometries that intersect this ISRM polygon
        # Use spatial index for faster query
        possible_matches_idx = list(edge_geom_sindex.intersection(isrm_geom.bounds))
        if not possible_matches_idx:
            # logger.warning(f"No edges found for ISRM ID: {isrm_id}")
            continue

        possible_matches = edge_geom_gdf.iloc[possible_matches_idx]

        # Further filter to only those that actually intersect
        intersecting_edges = possible_matches[possible_matches.geometry.intersects(isrm_geom)]

        if len(intersecting_edges) == 0:
            logger.warning(f"No intersecting edges found for ISRM ID: {isrm_id}")
            continue

        # For each intersecting edge, calculate intersection
        for edge_idx, edge_row in intersecting_edges.iterrows():
            osm_id = edge_row['osm_id']
            edge_geom = edge_row.geometry
            original_length = edge_row['edge_length']

            # Get the actual edge length from geometry for proportion calculation
            edge_geom_length = edge_geom.length

            # Get the actual intersection geometry
            intersection_geom = edge_geom.intersection(isrm_geom)

            # Skip empty geometries
            if intersection_geom.is_empty:
                continue

            # Calculate the proportion of the edge length in this ISRM polygon
            intersection_length = intersection_geom.length
            proportion = intersection_length / edge_geom_length if edge_geom_length > 0 else 0
            proportional_length = original_length * proportion

            # Create a record for this intersection
            result = {
                'isrm_id': isrm_id,
                'osm_id': osm_id,
                'edge_id': edge_row['edge_id'],
                'original_edge_length': original_length,
                'proportion': proportion,
                'proportional_length': proportional_length,
                'isrm_osm_id': f"{isrm_id}-{osm_id}",
                'geometry': intersection_geom
            }

            # Copy all attributes from edge
            for key, value in edge_row.items():
                if key not in ['geometry', 'osm_id', 'edge_length', 'edge_id'] and key not in result:
                    result[f'edge_{key}'] = value

            # Copy all attributes from ISRM polygon
            for key, value in isrm_row.items():
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

    area = "seattle"  # sfbay - seattle
    study_area_config = get_area_config(area)
    study_area_config["graph_layers"]["residential"]["min_density_per_km2"] = 412  # 2855 - 412

    #
    work_dir = study_area_config["work_dir"]

    # Hardcoded paths
    isrm_grid_path = os.path.expanduser(f"{work_dir}/inmap/ISRM/isrm_polygon.shp")
    osm_pbf_path = os.path.expanduser(f"{work_dir}/network/seattle-area-cbg412-ferry-network/seattle-area-cbg412-ferry-network.osm.pbf")
    osm_gpkg_path = os.path.expanduser(f"{work_dir}/network/seattle-area-cbg412-ferry-network/seattle-area-cbg412-ferry-network.gpkg")
    output_path = os.path.expanduser(f"{work_dir}/inmap/isrm_network_intersection.geojson")

    # Process the intersection
    process_isrm_network_intersection(
        isrm_grid_path=isrm_grid_path,
        osm_pbf_path=osm_pbf_path,
        osm_gpkg_path=osm_gpkg_path,
        output_path=output_path
    )


if __name__ == "__main__":
    main()