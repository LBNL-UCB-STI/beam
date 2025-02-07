from validation_utils import *
import osmnx as ox
import networkx as nx
import contextily as ctx
from statistics import median
from osmnx import truncate
from typing import Tuple

country_code: str = "US"


#########################
######## METHODS ########
#########################

def plot(G, name):
    fig, ax = ox.plot.plot_graph(
        G,
        bgcolor="#FFFFFF",  # Light background
        #         node_color="#00FFAA",      # Bright teal nodes
        node_color="#333333",  # Bright teal nodes
        node_size=0.02,
        node_edgecolor='none',  # Node size  2.5
        #         node_alpha=0.8,            # Node transparency
        #         node_edgecolor="#333333",  # Dark edges around nodes
        node_zorder=3,  # Nodes above edges
        edge_color="#FF5A5F",  # Bright coral edges
        edge_linewidth=0.2,  # Edge thickness 0.5
        edge_alpha=0.8,  # Edge transparency
        show=False,  # Do not display immediately
        close=False  # Keep the plot open for saving
    )

    ctx.add_basemap(ax, source=ctx.providers.CartoDB.Positron, zoom=20)

    # 3. Calculate statistics
    num_nodes = len(G.nodes)
    num_edges = len(G.edges)
    # Total length in meters
    total_length = sum(data.get('length', 0) for u, v, key, data in G.edges(keys=True, data=True))

    # 4. Add title with statistics
    title = (
        f"Nodes: {num_nodes} | Edges: {num_edges} | Total Length: {total_length / 1000:.2f} km"
    )
    ax.set_title(title, fontsize=15, fontweight='bold', color='black', pad=20)

    # 5. Save the figure with 600 DPI
    fig.savefig(f'{name}.png', dpi=600, bbox_inches='tight')


def str_median(values):
    """Calculate median after converting string values to numbers."""
    # Convert strings to integers, filtering out non-numeric values
    numeric_values = []
    for v in values:
        try:
            if isinstance(v, str):
                numeric_values.append(int(v))
            elif isinstance(v, (int, float)):
                numeric_values.append(int(v))
        except (ValueError, TypeError):
            continue

    if not numeric_values:
        return None
    return int(median(numeric_values))


def weight_conversion_map(self):
    """
    Returns weight conversion mapping based on country.
    Reference: https://wiki.openstreetmap.org/wiki/Key:maxweight
    """
    return {
        "US": {
            "default_unit": "lbs",
            "conversions": {
                "lbs": 1.0,
                "lb": 1.0,
                "t": 2000.0,  # short tons to lbs
                "st": 2000.0,  # short tons to lbs
                "ton": 2000.0,
                "tons": 2000.0,
                "mt": 2204.62,  # metric tons to lbs
            }
        },
        "GB": {  # United Kingdom
            "default_unit": "kg",
            "conversions": {
                "t": 1000.0,  # metric tonnes to kg
                "kg": 1.0,
                "lbs": 0.453592,  # pounds to kg
                "lb": 0.453592
            }
        },
        "EU": {  # European Union
            "default_unit": "kg",
            "conversions": {
                "t": 1000.0,  # metric tonnes to kg
                "kg": 1.0,
                "q": 100.0,  # quintals to kg
            }
        }
    }


def get_weight_in_standard_unit(weight_str: str) -> float:
    """
    Convert weight string to standard unit (lbs for US, kg for EU/UK)
    """
    if not weight_str or pd.isna(weight_str):
        return 0

    weight_str = str(weight_str).lower().strip()
    if not weight_str:
        return 0

    try:
        # Extract numeric value and unit
        import re
        match = re.match(r'^([\d.]+)\s*([\w\s]*)$', weight_str)
        if not match:
            print(f"Could not parse weight format: {weight_str}")
            return 0

        value = float(match.group(1))
        unit = match.group(2).strip()

        # Get country-specific conversion map
        country = country_code.upper()
        if country not in weight_conversion_map:
            country = "EU"  # Default to EU if country not found

        conv_map = weight_conversion_map[country]

        # If no unit specified, use country's default unit
        if not unit:
            unit = conv_map["default_unit"]

        # Convert to standard unit for the country
        if unit in conv_map["conversions"]:
            return value * conv_map["conversions"][unit]
        else:
            print(f"Unknown weight unit '{unit}' for country {country}")
            return value  # Assume it's already in the standard unit

    except ValueError:
        print(f"Could not parse weight value: {weight_str}")
        return 0


def get_weight_limits_in_standard_unit(self) -> Tuple[float, float]:
    """
    Get MDV and HDV weight limits in country's standard unit
    """
    if self.country_code.upper() == "US":
        return self.mdv_max_lbs, self.hdv_max_lbs
    else:
        # Convert lbs to kg for non-US countries
        return (
            self.mdv_max_lbs * 0.453592,  # lbs to kg
            self.hdv_max_lbs * 0.453592
        )


def process_vehicle_classifications(G: nx.MultiDiGraph) -> nx.MultiDiGraph:
    """Process vehicle classifications based on FHWA weight classes."""
    # https://afdc.energy.gov/data/10380
    # https://wiki.openstreetmap.org/wiki/Key:maxweight#:~:text=In%20most%20of%20the%20United,but%20never%20as%20metric%20tons.
    print("Processing vehicle classifications...")

    # Convert graph to GeoDataFrames while preserving MultiIndex
    nodes, edges = ox.graph_to_gdfs(G)
    original_index = edges.index
    edges = edges.reset_index()

    # Copy HGV weight restrictions if present
    if "maxweight:hgv" in edges.columns:
        hgv_mask = ~edges["maxweight:hgv"].isna()
        if hgv_mask.any():
            edges.loc[hgv_mask, "maxweight"] = edges.loc[hgv_mask, "maxweight:hgv"].copy()

    if "maxweight" in edges.columns:
        # Convert weights to standard unit for the country
        numericWeight = edges["maxweight"].apply(
            get_weight_in_standard_unit
        )

        # Get weight limits in the appropriate unit
        mdv_max, hdv_max = get_weight_limits_in_standard_unit()

        # Check weight restrictions
        mdvBannedByWeight = numericWeight <= mdv_max
        hdvBannedByWeight = numericWeight <= hdv_max
    else:
        mdvBannedByWeight = pd.Series([False] * len(edges))
        hdvBannedByWeight = pd.Series([False] * len(edges))

    # Process vehicle access flags
    hgvAllowedByDefault = edges.hgv.str.lower() != "no" if "hgv" in edges.columns else pd.Series(
        [True] * len(edges))
    longVehiclesBanned = ~edges.maxlength.isna() if "maxlength" in edges.columns else pd.Series(
        [False] * len(edges))

    # Set final vehicle access flags
    hgv = hgvAllowedByDefault & ~hdvBannedByWeight & ~longVehiclesBanned
    mdv = hgvAllowedByDefault & ~mdvBannedByWeight

    edges["hgv"] = hgv
    edges["mdv"] = mdv

    # Restore the original MultiIndex
    edges = edges.set_index(original_index.names)

    # Convert back to graph
    G = ox.graph_from_gdfs(nodes, edges, graph_attrs=G.graph)

    return G


#########################
########## Main #########
#########################

print("START")

# Boundaries
census_year = 2018
state_fips = '06'
study_area = "sfbay"
study_area_crs = 26910
# study_area_fips = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']
study_area_fips = ['041', '075']
min_pop_for_dense_counties = 100000  # Counties with population > 100,000
min_pop_for_dense_tract = 2000  # Tracts with population > 2,000

study_area_dir = os.path.expanduser("~/Workspace/Simulation") + "/" + study_area
study_area_county_geo = study_area_dir + "/geo/" + study_area + "_counties_wgs84.geojson"
study_area_cbg_geo = study_area_dir + "/geo/" + study_area + "_cbgs_wgs84.geojson"
densely_populated_counties_geo = study_area_dir + "/geo/" + study_area + f"_densely_populated_counties_geq{min_pop_for_dense_counties / 1000:.0f}k_wgs84.geojson"
densely_populated_tracts_geo = study_area_dir + "/geo/" + study_area + f"_densely_populated_tracts_geq{min_pop_for_dense_counties / 1000:.0f}k_wgs84.geojson"

if os.path.exists(study_area_county_geo):
    print("Loading county boundaries...")
    region_boundary_wgs84 = gpd.read_file(study_area_county_geo)
else:
    print("Downloading county boundaries...")
    region_boundary_wgs84 = collect_geographic_boundaries(
        state_fips_code=state_fips,
        county_fips_codes=study_area_fips,
        year=census_year,
        study_area_geo_path=study_area_county_geo,
        projected_coordinate_system=study_area_crs,
        geo_level='county')

if os.path.exists(densely_populated_counties_geo):
    densely_populated_counties_wgs84 = gpd.read_file(densely_populated_counties_geo)
else:
    densely_populated_counties_wgs84 = collect_dense_county_boundaries(
        state_fips_code=state_fips,
        county_fips_codes=study_area_fips,
        year=census_year,
        densely_populated_counties_geo_path=densely_populated_counties_geo,
        projected_coordinate_system=study_area_crs,
        min_population=min_pop_for_dense_counties
    )

result = collect_dense_tract_boundaries(
    state_fips_code=state_fips,
    county_fips_codes=study_area_fips,
    year=census_year,
    densely_populated_tracts_geo_path=densely_populated_tracts_geo,
    projected_coordinate_system=study_area_crs,  # Washington State Plane North
    min_density_per_km2=2500
)

region_boundary_polygon = region_boundary_wgs84.geometry.union_all()
# densely_populated_polygon = densely_populated_counties_wgs84.geometry.unary_union
densely_populated_polygon = result.geometry.union_all()

# OSMNX Settings
ox.settings.log_console = True
ox.settings.use_cache = True
ox.settings.all_oneway = True

# Download OSM Network
cf_moderate = '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
# polygon, *, network_type='all', simplify=True, retain_all=False, truncate_by_edge=False, custom_filter=None
G_moderate = ox.graph_from_polygon(region_boundary_polygon,
                                   network_type="drive",
                                   simplify=False,
                                   retain_all=True,
                                   truncate_by_edge=True,
                                   custom_filter=cf_moderate)

cf_dense = '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified|residential"]'
G_dense = ox.graph_from_polygon(densely_populated_polygon,
                                network_type="drive",
                                simplify=False,
                                retain_all=True,
                                truncate_by_edge=True,
                                custom_filter=cf_dense)

cf_sparse = '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link"]'

G = nx.compose_all([G_moderate, G_dense])

G_simplified = ox.simplification.simplify_graph(
    G,
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

nodes_1, edges_1 = ox.graph_to_gdfs(G_simplified)
G_with_speeds = ox.add_edge_speeds(G_simplified)
nodes_2, edges_2 = ox.graph_to_gdfs(G_with_speeds)
print(
    f'Nodes: {len(nodes_2)} (deleted {len(nodes_1) - len(nodes_2)} nodes), Edges: {len(edges_2)} (deleted {len(edges_1) - len(edges_2)} edges)'
)
G_with_restrictions = process_vehicle_classifications(G_with_speeds)
G_wgs84 = ox.project_graph(G_with_restrictions, to_crs="epsg:4326")
G_connected = ox.truncate.largest_component(G_wgs84.copy())
plot(G_connected, f'momo6_connected_graph')

print("END")
