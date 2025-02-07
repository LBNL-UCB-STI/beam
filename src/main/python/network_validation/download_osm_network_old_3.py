import osmnx as ox
import networkx as nx
import contextily as ctx
from statistics import median
from osmnx import truncate


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


ox.settings.log_console = True
ox.settings.use_cache = True
ox.settings.all_oneway = True

cf2 = '["highway"~"residential"]'

G_small = ox.graph_from_place([
    {
        "county": "San Francisco",
        "state": "California"
    }],
    network_type="drive",
    simplify=False,
    custom_filter=cf2,
    retain_all=True,
    truncate_by_edge=True)

cf = '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'

# places = [
#     {"county": "Alameda", "state": "California"},
#     {"county": "Contra Costa", "state": "California"},
#     {"county": "Marin", "state": "California"},
#     {"county": "Napa", "state": "California"},
#     {"county": "San Francisco", "state": "California"},
#     {"county": "San Mateo", "state": "California"},
#     {"county": "Santa Clara", "state": "California"},
#     {"county": "Solano", "state": "California"},
#     {"county": "Sonoma", "state": "California"}
# ]

places = [
    {"county": "Marin", "state": "California"}
]

G_big = ox.graph_from_place(places,
                            network_type="drive",
                            simplify=False,
                            custom_filter=cf,
                            retain_all=True,
                            truncate_by_edge=True)

G_big = nx.compose_all([G_big, G_small])

nodes, edges = ox.graph_to_gdfs(G_big)

G_big_reconstructed = ox.graph_from_gdfs(nodes, edges)

G2_big = ox.simplification.simplify_graph(
    G_big,
    edge_attr_aggs={
        "length": sum,
        "travel_time": sum,
        "lanes": str_median,
        "hgv": min,
        "mdv": min
    }
)

G2_big = ox.add_edge_speeds(G2_big)
G2_big_l_unproj = ox.project_graph(G2_big, to_crs="epsg:4326")

# G2_big_l_unproj_sm = ox.utils_graph.get_largest_component(G2_big_l_unproj)
G2_big_l_unproj_sm = ox.truncate.largest_component(G2_big_l_unproj.copy())

plot(G2_big_l_unproj_sm, f'toto_connected_graph')
