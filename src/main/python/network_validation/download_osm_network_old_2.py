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

from IPython.core.display_functions import display

print(ox.__version__)

is_places = True

# 0.00008983 = 10m
simpl_intersections = 2
splitLinksBy = ["highway", "lanes", "maxspeed"]

# Define custom filters
cf1 = '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
cf3 = '["highway"~"residential|motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
cf2 = '["highway"~"residential"]'
cf_main_highways = '["highway"="motorway"]'

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

places_filters = {
    "network_type": "drive",
    "simplify": False,
    "retain_all": True,
    "truncate_by_edge": False,
    "which_result": None,
    "custom_filter": [
        cf3, cf1
    ]}


# Helper function to get the appropriate value from the filter
def get_filter_value(filter_param, index, total_count):
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
            key: get_filter_value(value, i, len(places)) for key, value in places_filters.items()
        }
        graph = ox.graph_from_place(input_data, **dynamic_filters)
        graphs.append(graph)

    return nx.compose_all(graphs) if graphs else None


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
