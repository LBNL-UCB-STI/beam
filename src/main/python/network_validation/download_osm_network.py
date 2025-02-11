#!/usr/bin/env python3
"""
@author: haitamlaarabi
"""
import pickle
import subprocess

from osmnx import settings
from osmnx import truncate

from validation_utils import *


#########################
######## METHODS ########
#########################

def download_and_prepare_osm_network(_study_area_config: dict) -> nx.MultiDiGraph:
    # Apply OSMNX settings
    for setting, value in _study_area_config["osmnx_settings"].items():
        setattr(ox.settings, setting, value)

    # List to store the graphs
    graphs = []

    # For each density level
    for level, params in _study_area_config["density_levels"].items():
        # Create density-specific paths
        densely_populated_tracts_geo = (
            f"{_study_area_config['work_dir']}/{_study_area_config['study_area']}"
            f"_tracts_geq_to_{params['min_density_per_km2']}_pop_per_km2_wgs84.geojson"
        )

        # Get boundaries for this density level
        if os.path.exists(densely_populated_tracts_geo):
            densely_populated_tracts = gpd.read_file(densely_populated_tracts_geo)
        else:
            densely_populated_tracts = collect_dense_tract_boundaries(
                state_fips_code=_study_area_config["state_fips"],
                county_fips_codes=_study_area_config["county_fips"],
                year=_study_area_config["census_year"],
                densely_populated_tracts_geo_path=densely_populated_tracts_geo,
                projected_coordinate_system=_study_area_config["study_area_crs"],
                min_density_per_km2=params["min_density_per_km2"]
            )

        # Create polygon for network extraction
        densely_populated_polygon = densely_populated_tracts.geometry.union_all()

        # Download OSM Network for this density level
        G = ox.graph_from_polygon(
            densely_populated_polygon,
            network_type="drive",
            simplify=False,
            retain_all=True,
            truncate_by_edge=True,
            custom_filter=params["custom_filter"]
        )

        # Add the graph to the list
        graphs.append(G)

    g_combined = nx.compose_all(graphs)
    g_projected = ox.project_graph(g_combined, to_crs=_study_area_config["study_area_crs"]).copy()
    g_with_speeds = ox.add_edge_speeds(g_projected)
    g_with_ft_restrictions = process_freight_restrictions(g_with_speeds, _study_area_config)

    if _study_area_config["connect_islands"]:
        region_counties_geo = (
            f"{_study_area_config['work_dir']}/{_study_area_config['study_area']}_counties_wgs84.geojson"
        )
        if os.path.exists(region_counties_geo):
            region_boundary_wgs84 = gpd.read_file(region_counties_geo)
        else:
            region_boundary_wgs84 = collect_geographic_boundaries(
                state_fips_code=_study_area_config["state_fips"],
                county_fips_codes=_study_area_config["county_fips"],
                year=_study_area_config["census_year"],
                study_area_geo_path=region_counties_geo,
                projected_coordinate_system=_study_area_config["study_area_crs"],
                geo_level="county")

        g_completed_network = process_ferry_into_car_edges(g_with_ft_restrictions,
                                                           region_boundary_wgs84.geometry.union_all())
    else:
        g_completed_network = g_with_ft_restrictions

    g_consolidated = ox.consolidate_intersections(
        g_completed_network,
        tolerance=2,
        rebuild_graph=True,
        dead_ends=True,
        reconnect_edges=True
    )
    # Update length
    nodes, edges = ox.graph_to_gdfs(g_consolidated)
    edges['length'] = edges['geometry'].length
    g_length_updated = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_consolidated.graph)

    # Simplify
    nodes_1, edges_1 = ox.graph_to_gdfs(g_length_updated)
    g_simplified = ox.simplification.simplify_graph(
        g_length_updated,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True
    )
    nodes_2, edges_2 = ox.graph_to_gdfs(g_simplified)
    print(f'Nodes: #{len(nodes_2)} — deleted #{len(nodes_1) - len(nodes_2)} nodes')
    print(f'Edges: #{len(edges_2)} — deleted #{len(edges_1) - len(edges_2)} edges')

    g_wgs84 = ox.project_graph(g_simplified, to_crs="epsg:4326")
    g_connected = ox.truncate.largest_component(g_wgs84.copy())

    return g_connected


def generate_config_name(config: dict) -> str:
    """
    Generate a configuration name based on study area and density levels.
    Format: study_area_lastRoadType-densityPOPxKM2_lastRoadType-densityPOPxKM2

    Example output: sfbay_unclassified-0POPxKM2_residential-2500POPxKM2
    """
    # Get study area
    study_area = config["study_area"]

    # Process density levels
    density_parts = []

    for level, params in config["density_levels"].items():
        # Get density value
        density = params["min_density_per_km2"]

        # Extract last road type from custom filter
        filter_str = params["custom_filter"]
        road_types = filter_str.split('~')[1].strip('"[]').split('|')
        last_road_type = road_types[-1]

        # Combine level info
        level_str = f"{last_road_type}-{density}POPxKM2"
        density_parts.append(level_str)

    # Combine all parts
    ferry_suffix = "_ferry" if (config["connect_islands"]) else ""
    return f"{study_area}_{'_'.join(density_parts)}{ferry_suffix}"


#############################
########## Settings #########
#############################

study_area_config = {
    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/sfbay/geo"),

    # Geographic settings
    "study_area": "sfbay",
    "state_fips": "06",
    "county_fips": ["041"],
    # ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113'],  # ["041", "075"]
    "census_year": 2018,
    "study_area_crs": 26910,  # NAD83 / UTM zone 10N
    "connect_islands": False,  # Links disconnected islands relying on motor vehicle ferry using a virtual car link

    # Vehicle weight classifications (FHWA)
    "weight_limits": {
        "unit": "lbs",
        "mdv_max": 26000,  # Upper limit for Medium Duty Vehicles (Class 3-6) in pounds
        "hdv_max": 80000,  # Upper limit for Heavy Duty Vehicles (Class 7-8) in pounds
    },

    # Density thresholds and corresponding network filters
    "density_levels": {
        # "sparse": {
        #     "min_density_per_km2": 0,
        #     "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link"]'
        # },
        "moderate": {
            "min_density_per_km2": 0,
            # 193.05 people/sq km = 500 people/sq mi is threshod for rural areas https://www.ers.usda.gov/topics/rural-economy-population/rural-classifications/what-is-rural
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified"]'
        },
        "dense": {
            "min_density_per_km2": 193,
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified|residential"]'
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
        "overpass_rate_limit": True,
        "overpass_max_attempts": 3,
        "useful_tags_way": list(ox.settings.useful_tags_way) + ["maxweight", "hgv", "maxweight:hgv", "maxlength"],
        "overpass_url": "https://overpass-api.de/api",
        # https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances
    }
}

#############################
############ Main ###########
#############################

config_name = generate_config_name(study_area_config)
file_prefix = f'{study_area_config["work_dir"]}/{config_name}'
graphml_network = f'{file_prefix}_network.graphml'

if not os.path.exists(graphml_network):
    print(f'Downloading and preparing OSM-based {config_name} network...')
    g_network = download_and_prepare_osm_network(study_area_config)

    ox.save_graphml(g_network, filepath=graphml_network)
    print(f"GRAPHML Network saved to '{graphml_network}'.")

    # Save PKL Network
    pkl_network = f'{file_prefix}_network.pkl'
    with open(pkl_network, 'wb') as f:
        pickle.dump(g_network, f)
    print(f"PKL Network saved to '{pkl_network}'.")

    # Save PNG Network
    png_network = f'{file_prefix}_network.png'
    plot(g_network, png_network)
    print(f"PNG Network saved to '{png_network}'.")

    # Save GPKG Network
    gpkg_network = f'{file_prefix}_network.gpkg'
    ox.save_graph_geopackage(g_network, filepath=gpkg_network)
    print(f"GPKG Network saved to '{gpkg_network}'.")
else:
    def convert_yes_no(value):
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            if value.lower() == 'yes':
                return True
            if value.lower() == 'no':
                return False
        return value


    # Specify data types for all relevant attributes
    edge_dtypes = {
        'oneway': convert_yes_no,
        'bridge': convert_yes_no,
        'tunnel': convert_yes_no,
        'length': float,
        'lanes': int,
        'maxspeed': str,
        'osmid': str
    }

    node_dtypes = {
        'osmid': str,
        'x': float,
        'y': float
    }

    # Load the graph with custom data types
    g_network = ox.load_graphml(
        graphml_network,
        edge_dtypes=edge_dtypes,
        node_dtypes=node_dtypes
    )

# Save OSM Network
osm_network = f'{file_prefix}_network.osm'
nodes, edges = ox.graph_to_gdfs(g_network)
edges = edges.drop(['name', 'ref', 'reversed', 'geometry', 'u_original', 'v_original', 'bridge', 'merged_edges'],
                   axis=1, errors='ignore')
G_final = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_network.graph)
save_graph_to_osm(G_final, filename=osm_network)
print(f"OSM Network saved to '{osm_network}'.")

# Convert to PBF using osmium
pbf_path = f"{osm_network}.pbf"
cmd = f"osmium cat {osm_network} -o {pbf_path} --overwrite --output-format pbf,compression=zlib"
subprocess.run(cmd, shell=True)
