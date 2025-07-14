#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Script to map ISRM grid polygons to OSM edge geometries.
The result splits each OSM edge by ISRM polygon and calculates the proportion
of the edge length in each polygon, starting from the ISRM grid.
All operations are performed in UTM projection and results are converted back to WGS84.
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
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name

# Set up logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Define WGS84 EPSG code
WGS84_EPSG = 4326


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


def process_isrm_osm_intersection(isrm_grid_path, osm_geojson_path, osm_gpkg_path, epsg_utm, output_path):
    """
    Process the intersection of ISRM grid polygons with OSM edge geometries.
    All operations are performed in UTM projection and results are converted back to WGS84.

    Args:
        isrm_grid_path (str): Path to ISRM grid shapefile with isrm column
        osm_geojson_path (str): Path to OSM GEOJSON file with osm_id and other_tags
        osm_gpkg_path (str): Path to OSM GPKG network with edge_id and geometry
        epsg_utm (int): EPSG code for UTM projection to use for geometric operations
        output_path (str): Path to output file

    Returns:
        gpd.GeoDataFrame: The resulting GeoDataFrame with intersection results
    """
    # 1. Load ISRM grid
    logger.info(f"Loading ISRM grid from {isrm_grid_path}")
    try:
        isrm_gdf = gpd.read_file(isrm_grid_path)
        if 'isrm' not in isrm_gdf.columns:
            logger.error("ISRM grid file is missing 'isrm' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load ISRM grid: {e}")
        sys.exit(1)

    # 2. Load OSM GPKG network
    logger.info(f"Loading OSM GPKG network from {osm_gpkg_path}")
    try:
        gpkg_gdf = gpd.read_file(osm_gpkg_path, layer='edges')
        if 'edge_id' not in gpkg_gdf.columns:
            logger.error("OSM GPKG file is missing 'edge_id' column")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load OSM GPKG: {e}")
        sys.exit(1)

    # 3. Load OSM GeoJSON
    logger.info(f"Loading OSM GEOJSON from {osm_geojson_path}")
    try:
        osm_gdf = gpd.read_file(osm_geojson_path)
        if 'osm_id' not in osm_gdf.columns or 'other_tags' not in osm_gdf.columns:
            logger.error("OSM file is missing 'osm_id' or 'other_tags' columns")
            sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to load OSM GeoJSON: {e}")
        sys.exit(1)

    # Convert osm_id to int and parse other_tags
    osm_gdf['osm_id'] = osm_gdf['osm_id'].astype(int)

    # Parse other_tags to extract edge_id and length
    osm_gdf['parsed_tags'] = osm_gdf['other_tags'].apply(parse_other_tags)
    osm_gdf['edge_id'] = osm_gdf['parsed_tags'].apply(lambda x: x.get('edge_id', None))
    osm_gdf['edge_length'] = osm_gdf['parsed_tags'].apply(extract_edge_length)

    # Filter out edges without length information
    valid_osm_gdf = osm_gdf.dropna(subset=['edge_length'])
    logger.info(f"Found {len(valid_osm_gdf)} edges with valid length information")

    # Connect OSM data to geometries
    edge_geom_map = pd.merge(
        valid_osm_gdf[['osm_id', 'edge_id', 'edge_length']],
        gpkg_gdf[['edge_id', 'geometry']],
        on='edge_id',
        how='inner'
    )

    # Convert to GeoDataFrame
    edge_geom_gdf = gpd.GeoDataFrame(edge_geom_map, geometry='geometry', crs=gpkg_gdf.crs)
    logger.info(f"Successfully mapped {len(edge_geom_gdf)} edges to OSM geometries")

    # Project all geometries to UTM for accurate calculations
    logger.info(f"Projecting geometries to UTM EPSG:{epsg_utm}")
    edge_geom_gdf = edge_geom_gdf.to_crs(epsg=epsg_utm)
    isrm_gdf = isrm_gdf.to_crs(epsg=epsg_utm)

    # Create a spatial index for OSM geometries to speed up intersection queries
    edge_geom_sindex = edge_geom_gdf.sindex

    # Process ISRM polygons and find intersections
    intersection_results = []

    logger.info("Finding intersections between ISRM polygons and OSM edges")
    for idx, isrm_row in tqdm(isrm_gdf.iterrows(), total=len(isrm_gdf), desc="Processing ISRM polygons"):
        isrm_id = isrm_row['isrm']
        isrm_geom = isrm_row.geometry

        # Find potential edge geometries that intersect this ISRM polygon
        possible_matches_idx = list(edge_geom_sindex.intersection(isrm_geom.bounds))
        if not possible_matches_idx:
            continue

        possible_matches = edge_geom_gdf.iloc[possible_matches_idx]

        # Further filter to only those that actually intersect
        intersecting_edges = possible_matches[possible_matches.geometry.intersects(isrm_geom)]

        if len(intersecting_edges) == 0:
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
            proportion = round(intersection_length / edge_geom_length, 2) if edge_geom_length > 0 else 0
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

    result_gdf = gpd.GeoDataFrame(intersection_results, geometry='geometry', crs=epsg_utm)

    # Convert results back to WGS84 for output
    logger.info(f"Converting results back to WGS84 (EPSG:{WGS84_EPSG})")
    result_gdf = result_gdf.to_crs(epsg=WGS84_EPSG)

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


def map_beam_network_to_isrm_osm_intersection(network_path, isrm_osm_path, output_path):
    """
    Map network data to ISRM-OSM intersection data using attributeOrigId to match osm_id.

    Args:
        network_path (str): Path to the network.csv.gz file
        isrm_osm_path (str): Path to the ISRM-OSM intersection GeoJSON file
        output_path (str): Path to save the output file

    Returns:
        pd.DataFrame: The resulting DataFrame with mapping results
    """
    # 1. Load network data
    logger.info(f"Loading network data from {network_path}")
    try:
        network_df = pd.read_csv(network_path)
        logger.info(f"Loaded network data with {len(network_df)} rows")

        # Check if attributeOrigId column exists
        if 'attributeOrigId' not in network_df.columns:
            logger.error("Network file is missing 'attributeOrigId' column")
            return None
    except Exception as e:
        logger.error(f"Failed to load network data: {e}")
        return None

    # Filter out rows with empty or NaN attributeOrigId
    network_df = network_df.dropna(subset=['attributeOrigId'])
    # 2. Load ISRM-OSM intersection data
    logger.info(f"Loading ISRM-OSM intersection data from {isrm_osm_path}")
    try:
        isrm_osm_gdf = gpd.read_file(isrm_osm_path)
        logger.info(f"Loaded ISRM-OSM data with {len(isrm_osm_gdf)} rows")

        # Check if osm_id column exists
        if 'osm_id' not in isrm_osm_gdf.columns:
            logger.error("ISRM-OSM file is missing 'osm_id' column")
            return None
    except Exception as e:
        logger.error(f"Failed to load ISRM-OSM data: {e}")
        return None

    # 3. Convert osm_id to the same type as attributeOrigId for proper joining
    logger.info("Preparing data for mapping")

    # Make sure both ID columns are of the same type
    network_df['attributeOrigId'] = network_df['attributeOrigId'].astype(int)
    isrm_osm_gdf['osm_id'] = isrm_osm_gdf['osm_id'].astype(int)

    # 4. Merge the network and ISRM-OSM data
    logger.info("Merging network data with ISRM-OSM data")
    merged_df = pd.merge(
        network_df,
        isrm_osm_gdf,
        left_on='attributeOrigId',
        right_on='osm_id',
        how='inner'
    )

    logger.info(f"Merged result has {len(merged_df)} rows")

    # 5. Calculate the proportional network values based on the ISRM-OSM proportion
    logger.info("Calculating proportional values")

    # Apply proportion to network length
    merged_df['proportional_network_length'] = merged_df['linkLength'] * merged_df['proportion']

    # 6. Create a unique identifier combining network linkId and ISRM id
    merged_df['network_isrm_id'] = merged_df['linkId'].astype(str) + '-' + merged_df['isrm_id'].astype(str)

    # 7. Save the result
    logger.info(f"Saving mapped results to {output_path}")
    output_dir = os.path.dirname(output_path)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # Determine output format based on file extension
    extension = os.path.splitext(output_path)[1].lower()
    if extension == '.gpkg':
        if isinstance(merged_df, gpd.GeoDataFrame):
            merged_df.to_file(output_path, driver='GPKG')
        else:
            # Convert to GeoDataFrame if it's just a DataFrame
            logger.warning("Converting DataFrame to GeoDataFrame for GPKG output")
            geo_merged_df = gpd.GeoDataFrame(merged_df, geometry='geometry')
            geo_merged_df.to_file(output_path, driver='GPKG')
    elif extension == '.geojson':
        if isinstance(merged_df, gpd.GeoDataFrame):
            merged_df.to_file(output_path, driver='GeoJSON')
        else:
            # Convert to GeoDataFrame if it's just a DataFrame
            logger.warning("Converting DataFrame to GeoDataFrame for GeoJSON output")
            geo_merged_df = gpd.GeoDataFrame(merged_df, geometry='geometry')
            geo_merged_df.to_file(output_path, driver='GeoJSON')
    elif extension == '.csv':
        # For CSV, we drop the geometry column if it exists
        if 'geometry' in merged_df.columns:
            # Save the WKT representation of geometry
            merged_df['geometry_wkt'] = merged_df['geometry'].apply(lambda geom: geom.wkt if geom else None)
            merged_df = merged_df.drop(columns='geometry')
        merged_df.to_csv(output_path, index=False)
    else:
        logger.warning(f"Unrecognized output format: {extension}, using CSV format")
        merged_df.to_csv(output_path, index=False)

    logger.info("Mapping complete")
    return merged_df


def main():
    """Main execution function with hardcoded paths."""
    area = "seattle"
    study_area_config = get_area_config(area)
    study_area_config["graph_layers"]["residential"]["min_density_per_km2"] = 412

    network_name = generate_network_name(study_area_config)
    work_dir = study_area_config["work_dir"]
    network_dir = f'{work_dir}/network/{network_name}'

    # Input/output paths
    isrm_grid_path = os.path.expanduser(f"{work_dir}/inmap/ISRM/isrm_polygon.shp")
    osm_geojson_path = os.path.expanduser(f"{network_dir}/{network_name}.osm.geojson")
    osm_gpkg_path = os.path.expanduser(f"{network_dir}/{network_name}.gpkg")
    isrm_osm_dir = os.path.expanduser(f"{work_dir}/inmap/isrm-{network_name}")
    os.makedirs(isrm_osm_dir, exist_ok=True)
    isrm_osm_geojson_path = os.path.expanduser(f"{isrm_osm_dir}/isrm-{network_name}.geojson")
    beam_network = os.path.expanduser(f"{network_dir}/network.csv.gz")
    output2_path = os.path.expanduser(f"{isrm_osm_dir}/isrm-beam--network-intersection.geojson")

    if not os.path.exists(isrm_osm_geojson_path):
        # Process the intersection
        process_isrm_osm_intersection(
            isrm_grid_path=isrm_grid_path,
            osm_geojson_path=osm_geojson_path,
            osm_gpkg_path=osm_gpkg_path,
            epsg_utm=study_area_config["utm_epsg"],
            output_path=isrm_osm_geojson_path
        )

    # Map BEAM Network with isrm osm intersection
    map_beam_network_to_isrm_osm_intersection(
        beam_network,
        isrm_osm_geojson_path,
        output2_path
    )


if __name__ == "__main__":
    main()