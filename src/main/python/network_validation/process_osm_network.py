import os
import warnings

import matplotlib.pyplot as plt
import networkx as nx
from pyrosm import OSM


def main():
    # Suppress the FutureWarning
    warnings.filterwarnings('ignore', category=FutureWarning)

    # pbf_network = ("~/Workspace/Simulation/seattle/network/seattle-area-cbg120-ferry-weakConn-network/"
    #                        "seattle-area-cbg120-ferry-weakConn-network.osm.pbf")

    pbf_network = ("~/Workspace/Simulation/sfbay/network/sfbay-area-cbg5500-weakConn-network/"
                         "sfbay-area-cbg5500-weakConn-network.osm.pbf")

    pbf_network = os.path.expanduser(pbf_network)
    osm = OSM(pbf_network)

    # Get driving network edges (and nodes implicitly)
    # Try to get nodes as well if possible
    try:
        nodes, edges = osm.get_network(network_type="driving", nodes=True)
        print(f"Retrieved {len(nodes)} nodes and {len(edges)} edges from OSM")
        has_nodes = True
    except:
        edges = osm.get_network(network_type="driving")
        print(f"Retrieved {len(edges)} edges from OSM (nodes not separately available)")
        has_nodes = False

    # Project to a suitable CRS for length calculation
    edges_projected = edges.to_crs('EPSG:32048')
    edges_projected['length_m'] = edges_projected.geometry.length

    # ---------------------------------------------------------------------
    # CONNECTED COMPONENTS CHECK (NEW)
    # ---------------------------------------------------------------------
    print("\n" + "=" * 80)
    print("BUILDING CONNECTIVITY GRAPH")
    print("=" * 80)

    # Build an undirected graph from the edge geometries
    # Extract start and end coordinates from each LineString
    G = nx.Graph()

    print("Extracting node coordinates from edge geometries...")
    node_coords_to_id = {}  # Map (x, y) -> node_id
    next_node_id = 0

    edge_count = 0
    for idx, row in edges_projected.iterrows():
        geom = row.geometry

        # Get first and last coordinate of the LineString
        coords = list(geom.coords)
        start_coord = coords[0]
        end_coord = coords[-1]

        # Create/get node IDs for start and end
        if start_coord not in node_coords_to_id:
            node_coords_to_id[start_coord] = next_node_id
            next_node_id += 1

        if end_coord not in node_coords_to_id:
            node_coords_to_id[end_coord] = next_node_id
            next_node_id += 1

        start_id = node_coords_to_id[start_coord]
        end_id = node_coords_to_id[end_coord]

        # Add edge to graph
        G.add_edge(start_id, end_id)
        edge_count += 1

        if edge_count % 10000 == 0:
            print(f"  Processed {edge_count} edges...")

    print(f"Finished processing {edge_count} edges")
    print(f"Created {len(node_coords_to_id)} unique nodes from edge endpoints")

    # Compute connected components (as sets of node IDs)
    components = list(nx.connected_components(G))
    num_components = len(components)

    print("\n" + "=" * 80)
    print("CONNECTED COMPONENT ANALYSIS")
    print("=" * 80)
    print(f"Number of connected components (undirected): {num_components}")

    # Sort components by size (descending)
    components_sorted = sorted(components, key=len, reverse=True)

    largest = components_sorted[0]
    largest_size = len(largest)
    total_nodes = G.number_of_nodes()
    total_edges = G.number_of_edges()

    # Compute edges in the largest component
    largest_subgraph = G.subgraph(largest)
    largest_edges = largest_subgraph.number_of_edges()

    print(f"Total nodes: {total_nodes}")
    print(f"Total edges: {total_edges}")
    print(f"Largest component nodes: {largest_size} "
          f"({largest_size / total_nodes * 100:.2f}% of all nodes)")
    print(f"Largest component edges: {largest_edges} "
          f"({largest_edges / total_edges * 100:.2f}% of all edges)")

    # Show sizes of a few top components
    top_k = min(10, num_components)
    top_sizes = [len(c) for c in components_sorted[:top_k]]
    print(f"\nSizes of top {top_k} components (in nodes): {top_sizes}")

    if num_components > 1:
        print("\nWARNING: Network is not fully connected.")
        print(f"  - There are {num_components} disconnected components")
        print("  - Consider cleaning/removing small components or verifying "
              "that activity locations lie on the largest component.")

        # Show details about smaller components
        if num_components > 1:
            small_components = components_sorted[1:]
            num_small = len(small_components)
            total_small_nodes = sum(len(c) for c in small_components)
            print(f"\nSmall component summary:")
            print(f"  - {num_small} small components contain {total_small_nodes} nodes total")
            print(f"  - Smallest component has {top_sizes[-1]} nodes")
            if num_components <= 20:
                print(f"  - All component sizes: {top_sizes}")
    else:
        print("\nNetwork appears fully connected (single component).")

    # ---------------------------------------------------------------------
    # EXISTING LENGTH CHECKS
    # ---------------------------------------------------------------------

    # Filter links under 15 meters
    short_links = edges_projected[edges_projected['length_m'] < 15].copy()
    short_links = short_links.sort_values('length_m')

    # Filter links longer than 10km
    long_links = edges_projected[edges_projected['length_m'] > 10000].copy()
    long_links = long_links.sort_values('length_m', ascending=False)

    print(f"\n{'=' * 80}")
    print(f"Found {len(short_links)} links under 15 meters:\n")

    # Display each short link
    for idx, row in short_links.iterrows():
        highway = str(row.get('highway', 'N/A'))
        osm_id = str(row.get('id', 'N/A'))
        name = str(row.get('name', 'Unnamed'))

        print(
            f"Length: {row['length_m']:.2f}m | "
            f"Highway: {highway:15s} | "
            f"OSM ID: {osm_id:12s} | "
            f"Name: {name}"
        )

    print(f"\n{'=' * 80}")
    print(f"Found {len(long_links)} links longer than 10 km:\n")

    # Display each long link
    for idx, row in long_links.iterrows():
        highway = str(row.get('highway', 'N/A'))
        osm_id = str(row.get('id', 'N/A'))
        name = str(row.get('name', 'Unnamed'))

        print(
            f"Length: {row['length_m'] / 1000:.2f}km | "
            f"Highway: {highway:15s} | "
            f"OSM ID: {osm_id:12s} | "
            f"Name: {name}"
        )

    print(f"\n{'=' * 80}")
    print(f"Statistics for links under 15 meters:")
    if len(short_links) > 0:
        print(f"Minimum length: {short_links['length_m'].min():.2f} meters")
        print(f"Maximum length: {short_links['length_m'].max():.2f} meters")
        print(f"Average length: {short_links['length_m'].mean():.2f} meters")
        print(f"Median length: {short_links['length_m'].median():.2f} meters")
    else:
        print("No links shorter than 15 meters.")

    print(f"\nStatistics for entire network (links <= 500m):")
    edges_no_outliers = edges_projected[edges_projected['length_m'] <= 500].copy()
    print(f"Total links: {len(edges_no_outliers)}")
    if len(edges_no_outliers) > 0:
        print(f"Minimum length: {edges_no_outliers['length_m'].min():.2f} meters")
        print(f"Maximum length: {edges_no_outliers['length_m'].max() / 1000:.2f} km")
        print(f"Average length: {edges_no_outliers['length_m'].mean():.2f} meters")
        print(f"Median length: {edges_no_outliers['length_m'].median():.2f} meters")
    else:
        print("No links with length <= 500m to report.")

    # Create histogram for entire network
    if len(edges_no_outliers) > 0:
        plt.figure(figsize=(12, 6))
        plt.hist(edges_no_outliers['length_m'], bins=50, edgecolor='black', alpha=0.7)
        plt.xlabel('Length (meters)')
        plt.ylabel('Number of Links')
        plt.title('Distribution of All Link Lengths in Network')
        plt.grid(True, alpha=0.3)

        # Add vertical lines for mean and median
        mean_len = edges_no_outliers['length_m'].mean()
        median_len = edges_no_outliers['length_m'].median()

        plt.axvline(mean_len, color='red', linestyle='--',
                    linewidth=2, label=f'Mean: {mean_len:.2f}m')
        plt.axvline(median_len, color='green', linestyle='--',
                    linewidth=2, label=f'Median: {median_len:.2f}m')
        plt.legend()

        plt.tight_layout()
        plt.savefig('all_links_histogram.png', dpi=300)
        print(f"\nHistogram saved to 'all_links_histogram.png'")
        plt.show()
    else:
        print("\nSkipping histogram: no links after outlier filtering.")


if __name__ == "__main__":
    main()