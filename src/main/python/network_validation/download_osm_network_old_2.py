import osmnx as ox
import networkx as nx
import pandas as pd
import matplotlib.pyplot as plt
import networkx as nx
import osmnx as ox
import matplotlib.colors as mcolors
import pickle
import contextily as ctx
import subprocess
import xml.etree.ElementTree as ET
from typing import List, Dict, Any, Union, Tuple

from IPython.core.display_functions import display
from osmnx import truncate

print(ox.__version__)

is_places = True

# 0.00008983 = 10m
simpl_intersections = 2
splitLinksBy = ["highway", "lanes", "maxspeed"]

# Define custom filters
dense = '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified|residential"]'
moderate = '["highway"~"motorway|trunk|motorway_link|trunk_link|primary|secondary|primary_link|secondary_link|tertiary|tertiary_link|unclassified"]'

##############################  PLACE  ##############################

# Input places (list of place names)
# places = [
#     {"county": "San Francisco", "state": "California"},
#     {"county": "Alameda", "state": "California"},
#     {"county": "Contra Costa", "state": "California"},
#     {"county": "Marin", "state": "California"},
#     {"county": "Napa", "state": "California"},
#     {"county": "San Mateo", "state": "California"},
#     {"county": "Santa Clara", "state": "California"},
#     {"county": "Solano", "state": "California"},
#     {"county": "Sonoma", "state": "California"}, ]

places = [
    {"county": "San Francisco", "state": "California"},
    {"county": "Marin", "state": "California"},
]
print(places)

places_filters = {
    "network_type": "drive",
    "simplify": False,
    "retain_all": True,
    "truncate_by_edge": False,
    "which_result": None,
    "custom_filter": [
        dense, moderate
    ]}
print(places_filters)

# Medium Duty Vehicle upper limit
mdv_max_lbs: float = 26000  # lbs
# Heavy Duty Vehicle limits
hdv_max_lbs: float = 80000  # lbs
# Country code for weight unit handling
country_code: str = "US"


# Helper function to get the appropriate value from the filter
def get_filter_value(filter_param, index):
    if isinstance(filter_param, list):
        # If the parameter is a list, return the value for the current index
        return filter_param[index % len(filter_param)]
    else:
        # If the parameter is a single value, return the same value for all
        return filter_param


# Function to generate and combine graphs
def combine_graphs():
    combined_graphs = []
    graphs = []
    for i, input_data in enumerate(places):
        dynamic_filters = {
            key: get_filter_value(value, i) for key, value in places_filters.items()
        }
        graph = ox.graph_from_place(input_data, **dynamic_filters)
        graphs.append(graph)

    return nx.compose_all(graphs) if graphs else None


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

    ctx.add_basemap(ax, source=ctx.providers.CartoDB.Positron)

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


def analyze_specific_edge_attributes(df):
    # Descriptive stats for numeric attributes
    numeric_summary = df[['length', 'speed_kph']].describe().T.round(2)
    print("\nDescriptive statistics for numeric attributes in edges:")
    display(numeric_summary)

    # Value counts for each categorical attribute in edges
    categorical_attributes = ['oneway', 'maxspeed', 'lanes', 'sidewalk', 'cycleway',
                              'access', 'maxweight', 'hgv', 'highway']
    for attr in categorical_attributes:
        print(f"\nValue counts for '{attr}' in edges:")
        value_counts_df = df[attr].value_counts(dropna=False).to_frame(name="Count")
        display(value_counts_df)


def analyze_specific_node_attributes(df):
    # Value counts for each categorical attribute in nodes
    node_categorical_attributes = ['street_count', 'traffic_signals']
    for attr in node_categorical_attributes:
        print(f"\nValue counts for '{attr}' in nodes:")
        value_counts_df = df[attr].value_counts(dropna=False).to_frame(name="Count")
        display(value_counts_df)


# Input places (list of place names)
studyArea = 'SanFrancisco'

G = combine_graphs()

G = ox.project_graph(G, to_crs="epsg:3857")

plot(G, f'{studyArea}_{str(simpl_intersections)}_original_graph')

G_final = G.copy()

############################## Add Attributes

G_final = ox.add_edge_speeds(G_final)

nodes, edges = ox.graph_to_gdfs(G_final)
print(f'Nodes: {len(nodes)}, Edges: {len(edges)}')

####

G_final = process_vehicle_classifications(G_final)

############################## Consolidate Nodes

print('consolidate intersections')

G_final = ox.consolidate_intersections(G_final, tolerance=simpl_intersections, rebuild_graph=True, dead_ends=True,
                                       reconnect_edges=True
                                       )

# Update length
nodes, edges = ox.graph_to_gdfs(G_final)
edges['length'] = edges['geometry'].length
G_final = ox.graph_from_gdfs(nodes, edges, graph_attrs=G_final.graph)

# Plot

plot(G_final, f'{studyArea}_{str(simpl_intersections)}_consolidated_graph')

############################## Simplify Network
print('simplify network')
G_final = ox.simplification.simplify_graph(G_final,
                                           edge_attrs_differ=splitLinksBy,
                                           remove_rings=False,
                                           track_merged=True,
                                           )

plot(G_final, f'{studyArea}_{str(simpl_intersections)}_simplified_graph')

G_connected = ox.truncate.largest_component(G_final)

plot(G_connected, f'{studyArea}_{str(simpl_intersections)}_connected_graph')
