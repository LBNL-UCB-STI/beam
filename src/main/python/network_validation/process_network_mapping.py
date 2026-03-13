#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Script to map BEAM network links to OSM edge geometries without ISRM intersection.
Uses attributeOrigId -> osm_id to join network links to OSM geometries.
"""

import logging
import os
import re

import geopandas as gpd
import pandas as pd

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


def map_beam_network_to_osm_geometry(network_path, osm_input_path, output_path):
    """
    Map BEAM network links to OSM geometries using attributeOrigId -> osm_id.
    This function performs network mapping only and does not intersect with ISRM.

    Args:
        network_path (str): Path to the network.csv.gz file
        osm_input_path (str): Path to OSM GEOJSON or PBF file with osm_id and other_tags
        output_path (str): Path to save the output file

    Returns:
        pd.DataFrame: The resulting DataFrame with mapping results
    """
    # 1. Load network data
    logger.info(f"Loading network data from {network_path}")
    try:
        network_df = pd.read_csv(network_path)
        total_network_rows = len(network_df)
        logger.info(f"Loaded network data with {total_network_rows} rows")

        if 'attributeOrigId' not in network_df.columns:
            logger.error("Network file is missing 'attributeOrigId' column")
            return None
    except Exception as e:
        logger.error(f"Failed to load network data: {e}")
        return None

    network_df = network_df.dropna(subset=['attributeOrigId'])
    network_with_attr = len(network_df)

    # 2. Load OSM input (GeoJSON or PBF)
    logger.info(f"Loading OSM data from {osm_input_path}")
    try:
        if osm_input_path.lower().endswith(".pbf"):
            osm_gdf = gpd.read_file(osm_input_path, layer="lines")
        else:
            osm_gdf = gpd.read_file(osm_input_path)
        total_osm_rows = len(osm_gdf)
        if 'osm_id' not in osm_gdf.columns or 'other_tags' not in osm_gdf.columns:
            logger.error("OSM file is missing 'osm_id' or 'other_tags' columns")
            return None
    except Exception as e:
        logger.error(f"Failed to load OSM data: {e}")
        return None

    # 3. Parse tags
    osm_gdf['osm_id'] = osm_gdf['osm_id'].astype(int)
    osm_gdf['parsed_tags'] = osm_gdf['other_tags'].apply(parse_other_tags)
    osm_gdf['edge_id'] = osm_gdf['parsed_tags'].apply(lambda x: x.get('edge_id', None))
    osm_gdf['edge_length'] = osm_gdf['parsed_tags'].apply(extract_edge_length)

    # 4. Merge network with OSM geometries
    logger.info("Merging network data with OSM geometries")
    network_df['attributeOrigId'] = network_df['attributeOrigId'].astype(int)
    merged_df = pd.merge(
        network_df,
        osm_gdf,
        left_on='attributeOrigId',
        right_on='osm_id',
        how='inner'
    )

    logger.info(f"Merged result has {len(merged_df)} rows")

    # 5. Verification report
    network_ids = network_df[['attributeOrigId']].drop_duplicates()
    osm_ids = osm_gdf[['osm_id']].drop_duplicates()

    unmatched_network = network_ids.merge(
        osm_ids,
        left_on='attributeOrigId',
        right_on='osm_id',
        how='left',
        indicator=True
    )
    unmatched_network = unmatched_network[unmatched_network['_merge'] == 'left_only']

    unmatched_osm = osm_ids.merge(
        network_ids,
        left_on='osm_id',
        right_on='attributeOrigId',
        how='left',
        indicator=True
    )
    unmatched_osm = unmatched_osm[unmatched_osm['_merge'] == 'left_only']

    missing_osm_id = osm_gdf['osm_id'].isna().sum()
    missing_other_tags = osm_gdf['other_tags'].isna().sum()

    mapped_network_ids = network_ids[~network_ids['attributeOrigId'].isin(unmatched_network['attributeOrigId'])]
    match_rate = (len(mapped_network_ids) / len(network_ids)) if len(network_ids) > 0 else 0

    logger.info("Verification summary")
    logger.info(f"Network rows total: {total_network_rows}")
    logger.info(f"Network rows with attributeOrigId: {network_with_attr}")
    logger.info(f"Unique network attributeOrigId: {len(network_ids)}")
    logger.info(f"OSM rows total: {total_osm_rows}")
    logger.info(f"OSM rows missing osm_id: {missing_osm_id}")
    logger.info(f"OSM rows missing other_tags: {missing_other_tags}")
    logger.info(f"Unmatched network attributeOrigId: {len(unmatched_network)}")
    logger.info(f"Unmatched OSM osm_id: {len(unmatched_osm)}")
    logger.info(f"Match rate (unique ids): {match_rate:.4f}")

    # 6. Save the result
    logger.info(f"Saving mapped results to {output_path}")
    output_dir = os.path.dirname(output_path)
    if output_dir and not os.path.exists(output_dir):
        os.makedirs(output_dir)

    extension = os.path.splitext(output_path)[1].lower()
    if extension == '.gpkg':
        if isinstance(merged_df, gpd.GeoDataFrame):
            merged_df.to_file(output_path, driver='GPKG')
        else:
            geo_merged_df = gpd.GeoDataFrame(merged_df, geometry='geometry')
            geo_merged_df.to_file(output_path, driver='GPKG')
    elif extension == '.geojson':
        if isinstance(merged_df, gpd.GeoDataFrame):
            merged_df.to_file(output_path, driver='GeoJSON')
        else:
            geo_merged_df = gpd.GeoDataFrame(merged_df, geometry='geometry')
            geo_merged_df.to_file(output_path, driver='GeoJSON')
    elif extension == '.csv':
        if 'geometry' in merged_df.columns:
            merged_df['geometry_wkt'] = merged_df['geometry'].apply(lambda geom: geom.wkt if geom else None)
            merged_df = merged_df.drop(columns='geometry')
        merged_df.to_csv(output_path, index=False)
    else:
        logger.warning(f"Unrecognized output format: {extension}, using CSV format")
        merged_df.to_csv(output_path, index=False)

    logger.info("Network mapping complete")
    return merged_df


def main():
    """Main execution function with hardcoded paths."""
    #network_path = "/path/to/network.csv.gz"
    #osm_input_path = "/path/to/network.osm.geojson"  # or .pbf
    #output_path = "/path/to/beam-network-osm-mapping.geojson"
    work_dir = "/Users/haitamlaarabi/Workspace/Simulation/sfbay/network/sfbay-area-cbg5500-weakConn-network"
    network_path = work_dir + "/network.csv.gz"
    osm_input_path = work_dir + "/sfbay-area-cbg5500-weakConn-network.osm.pbf"
    output_path = work_dir + "/sfbay-mapping.geojson"
    map_beam_network_to_osm_geometry(
        network_path=os.path.expanduser(network_path),
        osm_input_path=os.path.expanduser(osm_input_path),
        output_path=os.path.expanduser(output_path)
    )


if __name__ == "__main__":
    main()
