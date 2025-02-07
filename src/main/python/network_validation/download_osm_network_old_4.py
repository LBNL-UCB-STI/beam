import osmnx as ox
import networkx as nx
import contextily as ctx
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


# Set the network type and other parameters
custom_settings = {
    'network_type': 'drive',
    'simplify': False,
}

# Get both county networks
print("sf")
sf = ox.graph_from_place('San Francisco, California, USA', **custom_settings)
print("marin")
marin = ox.graph_from_place('Marin, California, USA', **custom_settings)

# Combine them
print("compose_all")
G_complete = nx.compose_all([sf, marin])

# Get the Golden Gate Bridge area
# print("bridge_area")
# bridge_area = ox.graph_from_place('Golden Gate, San Francisco, California', **custom_settings)

# Combine all networks
# print("compose_all")
# G_complete = nx.compose_all([G, bridge_area])

# Ensure we have a connected network
print("largest_component")
G_complete = ox.truncate.largest_component(G_complete)

# Optional: Project the graph to UTM
print("project_graph")
G_complete = ox.project_graph(G_complete, to_crs="epsg:3857")

plot(G_complete, f'lolo_connected_graph')
print("End")
