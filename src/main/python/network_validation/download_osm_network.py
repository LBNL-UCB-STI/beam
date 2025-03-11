#!/usr/bin/env python3
"""
@author: haitamlaarabi, cristian.poliziani, zaneedell
"""
from validation_utils import download_and_prepare_osm_network
from validation_utils import standardize_oneway
from validation_utils import standardize_maxspeed
from validation_utils import check_invalid_coordinates
from validation_utils import create_osm_highway_filter
from validation_utils import save_graph_to_osm
from validation_utils import load_graph_from_osm
from validation_utils import scan_network_directories_for_ways
import osmnx as ox
from osmnx import settings
import os
import pickle
import subprocess


#############################
########## Settings #########
#############################

osm_default_highways = ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified"]

study_area_config = {
    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/sfbay"),

    # if download isn't enabled, we read network from disk
    "download_enabled": True,

    # Geographic settings
    "study_area": "sfbay",
    "state_fips": "06",
    "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113'],
    "census_year": 2018,
    "study_area_crs": 26910,  # NAD83 / UTM zone 10N
    "connect_islands": False,  # Links disconnected islands relying on motor vehicle ferry using a virtual car link
    "tolerance": 2,

    # Vehicle weight classifications (FHWA)
    "weight_limits": {
        "unit": "lbs",
        "mdv_max": 26000,  # Upper limit for Medium Duty Vehicles (Class 3-6) in pounds
        "hdv_max": 80000,  # Upper limit for Heavy Duty Vehicles (Class 7-8) in pounds
    },

    # Density thresholds and corresponding network filters
    "graph_layers": {
        "main": {
            "geo_level": "county",
            "custom_filter": create_osm_highway_filter(osm_default_highways),
            "buffer_zone_in_meters": 200
        },
        "residential": {
            # // California has a higher urbanization rate (94.8% urban vs 80.7% national average)
            # // https://dof.ca.gov/wp-content/uploads/sites/352/Forecasting/Demographics/Documents/Urban-Rural_Classification_and_2020_Urban_Area_Criteria_CA_SDC.pdf
            # const avgPersonsPerHousehold = 2.9; // CA average household size (higher than national 2.5)
            #
            # // Core density calculation (using similar proportions as national but adjusted for CA household size)
            # const coreHUDensity = 1275; // National high-density nucleus requirement
            # const caDensityAdjustment = 2.9 / 2.5; // CA vs national household size ratio
            # // Calculate CA-adjusted thresholds
            # const caHighDensityPPSM = coreHUDensity * 2.9;
            # const caInitialCorePPSM = 425 * 2.9;
            # const caUrbanExtensionPPSM = 200 * 2.9;
            # // Result
            # // California-adjusted density thresholds (persons per square mile):
            # //  densest urban cores, typical of downtown areas in major California cities:  7,395 ppsm = 2,855 ppsk
            # // High-density nucleus requirement: 3698 ppsm = 1429 ppsk
            # // Initial core requirement: 1233 ppsm = 475 ppsk
            # // Urban extension requirement: 580 ppsm = 224 ppsk
            # // Rural Areas less than 580 people per square mile
            "min_density_per_km2": 4500,
            "geo_level": "cbg",
            "custom_filter": create_osm_highway_filter(osm_default_highways + ["residential"]),
            "buffer_zone_in_meters": 20
        }
    },

    # OSMNX settings
    "osmnx_settings": {
        "log_console": True,
        "use_cache": True,
        "cache_only_mode": False,
        "all_oneway": True,
        "requests_timeout": 180,
        "overpass_memory": None,
        "max_query_area_size": 50 * 1000 * 50 * 1000,  # 50km × 50km
        "overpass_rate_limit": False,
        "overpass_max_attempts": 3,
        "useful_tags_way": list(ox.settings.useful_tags_way) + ["maxweight", "hgv", "maxweight:hgv", "maxlength"],
        "overpass_url": "https://overpass-api.de/api",
        # https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances
    }
}

def generate_config_name(config: dict) -> str:
    """
    Generate a configuration name based on study area, graph layers, and tolerance.
    Format: [study_area]-[main_geo_level]-[residential_geo_level][density]-t[tolerance][-ferry]-network

    Example output: sfbay-area-cbg7000-network or sfbay-area-cbg7000-ferry-network
    """
    # Get study area
    study_area = config["study_area"]

    # Get residential geographic level and density
    residential_geo_level = config["graph_layers"]["residential"]["geo_level"]
    density_value = str(config["graph_layers"]["residential"]["min_density_per_km2"])

    # Ferry suffix
    ferry_suffix = "-ferry" if config["connect_islands"] else ""

    # Combine all parts
    return f"{study_area}-area-{residential_geo_level}{density_value}{ferry_suffix}-network"
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
    edges = edges.drop(['geometry', 'u_original', 'v_original', 'merged_edges', 'osmid'], axis=1, errors='ignore')
    nodes = nodes.drop(['osmid_original'], axis=1, errors='ignore')
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


scan_network_directories_for_ways(os.path.expanduser("~/Workspace/Simulation/sfbay/network"))
