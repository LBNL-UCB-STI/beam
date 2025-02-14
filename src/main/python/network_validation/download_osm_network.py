#!/usr/bin/env python3
"""
@author: haitamlaarabi, cristian.poliziani, zaneedell
"""
import pickle
import subprocess
import hashlib

from osmnx import settings
from osmnx import truncate

from validation_utils import *


#########################
######## METHODS ########
#########################

def shorten_osmid(osmid):
    # Convert osmid to string if it isn't already
    osmid_str = str(osmid)
    # Create a hash of the osmid
    hash_object = hashlib.md5(osmid_str.encode())
    # Get first 8 characters of the hash
    short_id = hash_object.hexdigest()[:8]
    return short_id


def find_long_tags_in_gdf(gdf, element_type="elements"):
    """
    Find columns and combinations of attributes that exceed 250 characters in a GeoDataFrame.

    Parameters:
    -----------
    gdf : GeoDataFrame
        The input GeoDataFrame (can be either nodes or edges)
    element_type : str, optional
        The type of elements being analyzed ("nodes" or "edges") for output messages

    Returns:
    --------
    tuple
        (long_tags, long_comb_tags) where:
        - long_tags: dict of individual columns with values >= 250 characters
        - long_comb_tags: dict of rows with combined attribute length >= 250 characters
    """
    print(f"\nAnalyzing {element_type}...")

    # Find individual columns with values longer than 250 characters
    long_tags = {}
    for column in gdf.columns:
        # Convert all values to strings and check their lengths
        max_length = gdf[column].astype(str).str.len().max()
        if max_length >= 250:
            long_tags[column] = max_length

    # Print results for individual columns
    if long_tags:
        print(f"\nIndividual {element_type} columns with values >= 250 characters:")
        for column, length in long_tags.items():
            print(f"Column '{column}': max length = {length} characters")
            # Print an example of a long value
            long_value_idx = gdf[column].astype(str).str.len().idxmax()
            print(f"Example long value: {gdf[column].iloc[long_value_idx]}\n")
    else:
        print(f"No individual {element_type} columns found with values >= 250 characters")

    # Find combinations of attributes that exceed 250 characters
    print(f"\nChecking {element_type} attribute combinations...")
    # Get all rows where any combination of attributes might be long
    long_comb_tags = {}
    for idx, row in gdf.iterrows():
        comb_length = 0
        contributing_cols = []

        for col in gdf.columns:
            value = str(row[col])
            if len(value) > 0 and value.lower() != 'nan':  # Skip empty or NaN values
                value_length = len(value)
                comb_length += value_length
                if value_length > 0:  # Only add if the value has length
                    contributing_cols.append({
                        'column': col,
                        'length': value_length,
                        'value': value
                    })

        if comb_length >= 250:
            long_comb_tags[idx] = {
                'total_length': comb_length,
                'contributing_columns': contributing_cols
            }

    # Print results for combinations
    if long_comb_tags:
        print(f"\n{element_type.capitalize()} rows with combined attribute length >= 250 characters:")
        for idx, info in long_comb_tags.items():
            print(f"\nRow {idx}:")
            print(f"Total combined length: {info['total_length']} characters")
            print("Contributing columns:")
            for col_info in info['contributing_columns']:
                print(f"- {col_info['column']}: length={col_info['length']} chars")
                if col_info['length'] > 50:  # Show value only if it's significantly long
                    print(f"  Value: {col_info['value'][:50]}...")  # Show first 50 chars
    else:
        print(f"No combinations of {element_type} attributes found exceeding 250 characters")

    return long_tags, long_comb_tags


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
    g_simplified = ox.simplification.simplify_graph(
        g_length_updated,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True
    )

    # Shorten OSM IDs
    nodes, edges = ox.graph_to_gdfs(g_simplified)
    # Create a mapping of original to shortened IDs (if you need to reference back)
    edges['osmid_hash'] = edges['osmid'].apply(lambda x: shorten_osmid(x))
    nodes['osmid_hash'] = nodes['osmid_original'].apply(lambda x: shorten_osmid(x))
    g_hashed = ox.graph_from_gdfs(nodes, edges)

    # Project to WGS84
    g_wgs84 = ox.project_graph(g_hashed, to_crs="epsg:4326")
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

    # if download isn't enabled, we read network from disk
    "download_enabled": True,

    # Geographic settings
    "study_area": "sfbay",
    "state_fips": "06",
    "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113'],
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
        # // High-density nucleus requirement: 3698 ppsm = 1429 ppsk
        # // Initial core requirement: 1233 ppsm = 475 ppsk
        # // Urban extension requirement: 580 ppsm = 224 ppsk
        # // Rural Areas less than 580 people per square mile

        "sparse": {
            "min_density_per_km2": 0,
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link"]'
        },
        "moderate": {
            "min_density_per_km2": 224,
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified"]'
        },
        "dense": {
            "min_density_per_km2": 475,
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
osm_network = f'{file_prefix}_network.osm'

if not os.path.exists(graphml_network) and study_area_config["download_enabled"]:
    print(f'Downloading and preparing OSM-based {config_name} network...')
    g_network = download_and_prepare_osm_network(study_area_config)

    # Save GraphML
    ox.save_graphml(g_network, filepath=graphml_network)
    print(f"GRAPHML Network saved to '{graphml_network}'.")

    # Save PKL Network
    pkl_network = f'{file_prefix}_network.pkl'
    with open(pkl_network, 'wb') as f:
        pickle.dump(g_network, f)
    print(f"PKL Network saved to '{pkl_network}'.")

    # Save GPKG Network with OSM IDs hashed
    gpkg_network = f'{file_prefix}_network.gpkg'
    ox.save_graph_geopackage(g_network, filepath=gpkg_network)
    print(f"GPKG Network saved to '{gpkg_network}'.")

    # Save PNG Network
    png_network = f'{file_prefix}_network.png'
    plot(g_network, png_network)
    print(f"PNG Network saved to '{png_network}'.")
elif os.path.exists(graphml_network):
    # Load the graph with custom data types
    g_network = ox.load_graphml(
        graphml_network,
        edge_dtypes={
            'oneway': str, 'bridge': str, 'tunnel': str, 'length': float, 'lanes': int, 'maxspeed': str, 'osmid': str
        },
        node_dtypes={
            'osmid': str, 'x': float, 'y': float
        }
    )
else:
    print(f"GraphML Network not found & download isn't enabled. Please download and prepare the network first.")
    g_network = None

if g_network and not os.path.exists(osm_network):
    # Save OSM Network
    nodes, edges = ox.graph_to_gdfs(g_network)
    edges = edges.drop(['geometry', 'u_original', 'v_original', 'merged_edges', 'osmid'], axis=1, errors='ignore')
    nodes = nodes.drop(['osmid_original'], axis=1, errors='ignore')
    g_osm = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_network.graph)
    save_graph_to_osm(g_osm, filename=osm_network)
    print(f"OSM Network saved to '{osm_network}'.")

    # Convert to PBF using osmium
    pbf_path = f"{osm_network}.pbf"
    cmd = f"osmium cat {osm_network} -o {pbf_path} --overwrite --output-format pbf,compression=zlib"
    subprocess.run(cmd, shell=True)
    # osmium fileinfo -e {pbf_path}
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
