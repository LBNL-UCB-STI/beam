#!/usr/bin/env python3
"""
Download and prepare OSM network data.

@author: haitamlaarabi, cristian.poliziani, zaneedell
"""
import os
import sys
import pickle
import subprocess

import osmnx as ox

from osm_utils import download_and_prepare_osm_network
from osm_utils import check_invalid_coordinates
from osm_utils import scan_network_directories_for_ways
from osm_utils import check_duplicate_edge_ids
from osm_xml import save_graph_xml

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name


def main():
    """Main execution function."""
    area = "sfbay"  # Options: sfbay, seattle
    study_area_config = get_area_config(area)
    study_area_config["graph_layers"]["residential"]["min_density_per_km2"] = 2855  # 2855 for sfbay, 412 for seattle

    # Generate configuration name and prepare directory
    config_name = generate_network_name(study_area_config)
    network_dir = f'{study_area_config["work_dir"]}/network/{config_name}'
    os.makedirs(network_dir, exist_ok=True)

    # Define output file paths
    graphml_network = f'{network_dir}/{config_name}.graphml'
    pkl_network = f'{network_dir}/{config_name}.pkl'
    gpkg_network = f'{network_dir}/{config_name}.gpkg'
    osm_network = f'{network_dir}/{config_name}.osm'
    pbf_network = f'{network_dir}/{config_name}.osm.pbf'
    geojson_network = f'{network_dir}/{config_name}.osm.geojson'

    print(f'Downloading and preparing OSM-based {config_name} network...')
    g_network = download_and_prepare_osm_network(study_area_config)

    # Check for duplicate edge IDs
    nodes, edges = ox.graph_to_gdfs(g_network)
    has_duplicates, duplicate_info = check_duplicate_edge_ids(edges, 'edge_id')

    if has_duplicates:
        dup_counts, dup_examples = duplicate_info
        print(f"\nFound {sum(dup_counts.values())} duplicate edge IDs")

    # Save GraphML and PKL formats
    ox.save_graphml(g_network, filepath=graphml_network)
    print(f"GRAPHML Network saved to '{graphml_network}'.")

    with open(pkl_network, 'wb') as f:
        pickle.dump(g_network, f)
    print(f"PKL Network saved to '{pkl_network}'.")

    # Check for invalid coordinates
    has_invalid, invalid_nodes = check_invalid_coordinates(g_network)
    if has_invalid:
        print(f"WARNING: Found {len(invalid_nodes)} nodes with invalid coordinates.")
    else:
        print("✓ All node coordinates are valid.")

    # Extract nodes and edges as GeoDataFrames and verify CRS
    nodes, edges = ox.graph_to_gdfs(g_network)
    if nodes.crs != edges.crs:
        print("\nWARNING: Nodes and edges have different CRS!")
        print(f"Nodes CRS: {nodes.crs}")
        print(f"Edges CRS: {edges.crs}")

    # Save GPKG Network
    print(f"Converting GraphML Network to GPKG Network...")
    ox.save_graph_geopackage(g_network, filepath=gpkg_network)
    print(f"GPKG Network saved to '{gpkg_network}'.")

    # Create OSM Network
    print(f"Creating OSM Network...")
    nodes, edges = ox.graph_to_gdfs(g_network)
    edges = edges.drop([
        'u_original', 'v_original', 'merged_edges', 'osmid'
    ], axis=1, errors='ignore')
    nodes = nodes.drop([
        'osmid_original'
    ], axis=1, errors='ignore')

    g_osm = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_network.graph)
    save_graph_xml(
        g_osm,
        filepath=osm_network,
        edge_tags=[
            'highway', 'lanes', 'maxspeed', 'name', 'oneway', 'length',
            'tunnel', 'bridge', 'junction', 'edge_id', 'access', 'osm_id'
        ],
        edge_tag_aggs=[('length', 'sum')]
    )
    print(f"OSM Network saved to '{osm_network}'.")

    # Convert to PBF and GeoJSON formats
    cmd = f"osmium cat {osm_network} -o {pbf_network} --overwrite --output-format pbf,compression=zlib"
    subprocess.run(cmd, shell=True, check=True)
    print(f"OSM PBF File saved to '{pbf_network}'")

    cmd2 = f"ogr2ogr -f GeoJSON {geojson_network} {pbf_network} lines"
    subprocess.run(cmd2, shell=True, check=True)
    print(f"OSM GEOJSON File saved to '{geojson_network}'")

    # Scan network directories for ways
    work_dir = study_area_config["work_dir"]
    scan_network_directories_for_ways(os.path.expanduser(f'{work_dir}/network'))


if __name__ == "__main__":
    main()