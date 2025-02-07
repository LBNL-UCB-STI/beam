from validation_utils import *
from osmnx import settings
from osmnx import truncate
import pickle


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

    nodes_1, edges_1 = ox.graph_to_gdfs(g_combined)
    g_simplified = ox.simplification.simplify_graph(
        g_combined,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True,
        edge_attr_aggs={
            "length": sum,
            "travel_time": sum,
            "lanes": str_median,
            "hgv": min,
            "mdv": min
        }
    )
    nodes_2, edges_2 = ox.graph_to_gdfs(g_simplified)
    print(f'Nodes: #{len(nodes_2)} — deleted #{len(nodes_1) - len(nodes_2)} nodes')
    print(f'Edges: #{len(edges_2)} — deleted #{len(edges_1) - len(edges_2)} edges')

    g_with_speeds = ox.add_edge_speeds(g_simplified)
    g_with_restrictions = process_vehicle_classifications(g_with_speeds, _study_area_config["country_code"])
    g_wgs84 = ox.project_graph(g_with_restrictions, to_crs="epsg:4326")
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
    config_name = f"{study_area}_{'_'.join(density_parts)}"

    return config_name


#############################
########## Settings #########
#############################

study_area_config = {
    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/sfbay/geo"),

    # Geographic settings
    "study_area": "sfbay",
    "state_fips": "06",
    "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113'],  # ["041", "075"]
    "census_year": 2018,
    "study_area_crs": 26910,  # NAD83 / UTM zone 10N
    "country_code": "US",

    # Density thresholds and corresponding network filters
    # Typical thresholds:
    #    - Rural: < 50 people/km²
    #    - Mixed Rural/Suburban: 50-200 people/km²
    #    - Suburban/Urban Mix: 200-500 people/km²
    #    - Urban: > 500 people/km²
    "density_levels": {
        # "sparse": {
        #     "min_density_per_km2": 0,
        #     "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link"]'
        # },
        "moderate": {
            "min_density_per_km2": 0,
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified"]'
        },
        "dense": {
            "min_density_per_km2": 200,
            "custom_filter": '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified|residential"]'
        }
    },

    # OSMNX settings
    "osmnx_settings": {
        "log_console": True,
        "use_cache": True,
        "all_oneway": True
    }
}

#############################
############ Main ###########
#############################

config_name = generate_config_name(study_area_config)
print(f'Downloading and preparing OSM-based {config_name} network...')

file_prefix = f'{study_area_config["work_dir"]}/{config_name}'
G_network = download_and_prepare_osm_network(study_area_config)

# Save PNG Network
png_network = f'{file_prefix}_network.png'
plot(G_network, png_network)
print(f"PNG Network saved to '{png_network}'.")

# Save PKL Network
pkl_network = f'{file_prefix}_network.pkl'
with open(pkl_network, 'wb') as f:
    pickle.dump(G_network, f)
print(f"PKL Network saved to '{pkl_network}'.")

# Save GPKG Network
gpkg_network = f'{file_prefix}_network.gpkg'
ox.save_graph_geopackage(G_network, filepath=gpkg_network)
print(f"GPKG Network saved to '{gpkg_network}'.")

# Save OSM Network
osm_network = f'{file_prefix}_network.osm'
save_graph_to_osm(G_network, filename=osm_network)
print(f"OSM Network saved to '{osm_network}'.")

# Save PBF Network
pbf_network = f'{file_prefix}_network.osm.pbf'
save_graph_to_pbf(G_network, filename=pbf_network)
print(f"PBF Network saved to '{osm_network}'.")
