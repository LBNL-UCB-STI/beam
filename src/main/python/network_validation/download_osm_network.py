#!/usr/bin/env python3
"""
@author: haitamlaarabi, cristian.poliziani, zaneedell
"""
from validation_utils import download_and_prepare_osm_network
from validation_utils import standardize_oneway
from validation_utils import standardize_maxspeed
from validation_utils import check_invalid_coordinates
from validation_utils import save_graph_to_osm
from validation_utils import load_graph_from_osm
from validation_utils import scan_network_directories_for_ways
from ..utils.study_area_config import generate_config_name
from ..utils.study_area_config import sfbay_area_config
from ..utils.study_area_config import seattle_area_config
import osmnx as ox
import os
import pickle
import subprocess

# study_area_config = sfbay_area_config
study_area_config = seattle_area_config

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

if not os.path.exists(graphml_network) and study_area_config["download_enabled"]:
    print(f'Downloading and preparing OSM-based {config_name} network...')
    g_network = download_and_prepare_osm_network(study_area_config)

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
elif os.path.exists(graphml_network):
    # Load the graph with custom data types
    g_network = ox.load_graphml(
        graphml_network,
        edge_dtypes={
            'oneway': standardize_oneway,
            'bridge': str,
            'tunnel': str,
            'length': float,
            'lanes': int,
            'maxspeed': standardize_maxspeed,
            'osmid': str
        },
        node_dtypes={
            'osmid': str, 'x': float, 'y': float
        }
    )
else:
    print(f"GraphML Network not found & download isn't enabled. Please download and prepare the network first.")
    g_network = None

if g_network and not os.path.exists(osm_network):
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

    print(f"Converting GraphML Network to GPKG Network...")
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
        'geometry', 'u_original', 'v_original', 'merged_edges', 'osmid', 'junction', 'service', 'tunnel',
        'bridge', 'motorcar', 'motor_vehicle', 'width', 'area', 'ref'
    ], axis=1, errors='ignore')
    nodes = nodes.drop([
        'osmid_original', 'cluster', 'railway'
    ], axis=1, errors='ignore')
    g_osm = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_network.graph)
    save_graph_to_osm(g_osm, filename=osm_network)
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
elif g_network:
    # If the OSM network file doesn't exist, attempt to load it
    if os.path.exists(osm_network):
        print(f"Loading OSM Network from '{osm_network}'...")
        g_osm = load_graph_from_osm(osm_network)  # Implement this function to load the graph
        print("OSM Network loaded successfully.")

        # Count the number of links
        num_links = g_osm.number_of_edges()
        print(f"Number of links in the OSM Network: {num_links}")
    else:
        print(f"OSM Network file '{osm_network}' not found. Please ensure the network is downloaded and prepared.")


scan_network_directories_for_ways(os.path.expanduser(f'{study_area_config["work_dir"]}/network'))
