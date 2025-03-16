#!/usr/bin/env python3
"""
@author: haitamlaarabi, cristian.poliziani, zaneedell
"""
from osm_utils import download_and_prepare_osm_network
from osm_utils import check_invalid_coordinates
from osm_utils import scan_network_directories_for_ways
from osm_utils import check_duplicate_edge_ids
from osm_xml import save_graph_xml
import osmnx as ox
import os
import sys
import pickle
import subprocess


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

area = "seattle" # sfbay - seattle
study_area_config = get_area_config(area)
study_area_config["graph_layers"]["residential"]["min_density_per_km2"] = 412  # 2855 - 412

#############################
############ Main ###########
#############################

config_name = generate_config_name(study_area_config)
network_dir = f'{study_area_config["work_dir"]}/network/{config_name}'

# Create the directory if it doesn't exist
os.makedirs(network_dir, exist_ok=True)

graphml_network = f'{network_dir}/{config_name}.graphml'
pkl_network = f'{network_dir}/{config_name}.pkl'
gpkg_network = f'{network_dir}/{config_name}.gpkg'
osm_network = f'{network_dir}/{config_name}.osm'
pbf_network = f'{network_dir}/{config_name}.osm.pbf'

print(f'Downloading and preparing OSM-based {config_name} network...')
g_network = download_and_prepare_osm_network(study_area_config)
nodes, edges = ox.graph_to_gdfs(g_network)
has_duplicates, duplicate_info = check_duplicate_edge_ids(edges, 'edge_id')

if has_duplicates:
    # Display information about the duplicates
    dup_counts, dup_examples = duplicate_info
    print("\nDuplicate edge IDs:")
    print(dup_counts)

    print("\nExample edges with duplicate IDs:")
    # Display relevant columns for the first few duplicate edges
    display_cols = ['edge_id', 'u', 'v', 'osmid', 'highway']
    print(dup_examples[display_cols].head(10))

# Save GraphML
ox.save_graphml(g_network, filepath=graphml_network)
print(f"GRAPHML Network saved to '{graphml_network}'.")

# Save PKL Network
with open(pkl_network, 'wb') as f:
    pickle.dump(g_network, f)
print(f"PKL Network saved to '{pkl_network}'.")

# Save PNG Network
# png_network = f'{network_dir}/{config_name}.png'
# plot(g_network, png_network)
# print(f"PNG Network saved to '{png_network}'.")

print(f"Checking for invalid coordinates...")
has_invalid, invalid_nodes = check_invalid_coordinates(g_network)

if has_invalid:
    print(
        f"WARNING: Found {len(invalid_nodes)} nodes with invalid coordinates. These should be fixed before proceeding.")
    # Optionally: Fix or remove invalid nodes
    # g_network.remove_nodes_from(invalid_nodes)
    # print(f"Removed {len(invalid_nodes)} invalid nodes from the network.")
else:
    print("✓ All node coordinates are valid.")

# Extract nodes and edges as GeoDataFrames
nodes, edges = ox.graph_to_gdfs(g_network)
# Print CRS information
print("Nodes CRS:", nodes.crs)
print("Edges CRS:", edges.crs)

# For more detailed information about the CRS
print("\nDetailed Nodes CRS information:")
print(nodes.crs.to_string())
print("\nDetailed Edges CRS information:")
print(edges.crs.to_string())

# Check if they're the same
if nodes.crs == edges.crs:
    print("\nBoth nodes and edges have the same CRS")
else:
    print("\nWARNING: Nodes and edges have different CRS!")
    print(f"Nodes CRS: {nodes.crs}")
    print(f"Edges CRS: {edges.crs}")

# Print a sample of node coordinates
print("\nSample node coordinates (should be longitude/latitude if WGS84):")
print(nodes[['x', 'y']].head())

# Print a sample of edge geometries
print("\nSample edge coordinates (first point of each LineString):")
for idx, geom in edges.geometry.head().items():
    print(f"Edge {idx}: First point {geom.coords[0]}")

print(f"Converting GraphML Network to GPKG Network...")
# Save GPKG Network with OSM IDs hashed
ox.save_graph_geopackage(g_network, filepath=gpkg_network)
print(f"GPKG Network saved to '{gpkg_network}'.")
# Save OSM Network
print(f"Creating OSM Network...")
# Extract nodes and edges from the graph to create a new graph in OSM format
# Note: This will lose some information (e.g., edge attributes) and may not be 100% accurate
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
        'tunnel', 'bridge', 'junction', 'osm_id', 'access'
    ],
    edge_tag_aggs=[('length', 'sum')]
)
# save_graph_to_osm(g_osm, filename=osm_network)
print(f"OSM Network saved to '{osm_network}'.")

# Convert to PBF using osmium
cmd = f"osmium cat {osm_network} -o {pbf_network} --overwrite --output-format pbf,compression=zlib"
subprocess.run(cmd, shell=True)
# osmium fileinfo -e {pbf_path}
print(f"PBF File saved to '{pbf_network}'")

# Check file info using osmium
print("Checking PBF file info...")
fileinfo_cmd = f"osmium fileinfo -e {pbf_network}"
result = subprocess.run(fileinfo_cmd, shell=True, check=True, capture_output=True, text=True)
print("File information:")
print(result.stdout)


scan_network_directories_for_ways(os.path.expanduser(f'{study_area_config["work_dir"]}/network'))
