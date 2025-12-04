from pyrosm import OSM
import os
import warnings
import matplotlib.pyplot as plt


def main():
    # Suppress the FutureWarning
    warnings.filterwarnings('ignore', category=FutureWarning)

    pbf_network = os.path.expanduser("~/Workspace/Simulation/seattle/network/"
                                     "seattle-area-cbg120-ferry-weakConn-network/seattle-area-cbg120-ferry-weakConn-network.osm.pbf")
    osm = OSM(pbf_network)
    edges = osm.get_network(network_type="driving")

    edges_projected = edges.to_crs('EPSG:32048')
    edges_projected['length_m'] = edges_projected.geometry.length

    # Filter links under 15 meters
    short_links = edges_projected[edges_projected['length_m'] < 15].copy()
    short_links = short_links.sort_values('length_m')

    # Filter links longer than 10km
    long_links = edges_projected[edges_projected['length_m'] > 10000].copy()
    long_links = long_links.sort_values('length_m', ascending=False)

    print(f"Found {len(short_links)} links under 15 meters:\n")

    # Display each short link
    for idx, row in short_links.iterrows():
        highway = str(row.get('highway', 'N/A'))
        osm_id = str(row.get('id', 'N/A'))
        name = str(row.get('name', 'Unnamed'))

        print(f"Length: {row['length_m']:.2f}m | "
              f"Highway: {highway:15s} | "
              f"OSM ID: {osm_id:12s} | "
              f"Name: {name}")

    print(f"\n{'=' * 80}")
    print(f"Found {len(long_links)} links longer than 10 km:\n")

    # Display each long link
    for idx, row in long_links.iterrows():
        highway = str(row.get('highway', 'N/A'))
        osm_id = str(row.get('id', 'N/A'))
        name = str(row.get('name', 'Unnamed'))

        print(f"Length: {row['length_m'] / 1000:.2f}km | "
              f"Highway: {highway:15s} | "
              f"OSM ID: {osm_id:12s} | "
              f"Name: {name}")

    print(f"\n{'=' * 80}")
    print(f"Statistics for links under 15 meters:")
    print(f"Minimum length: {short_links['length_m'].min():.2f} meters")
    print(f"Maximum length: {short_links['length_m'].max():.2f} meters")
    print(f"Average length: {short_links['length_m'].mean():.2f} meters")
    print(f"Median length: {short_links['length_m'].median():.2f} meters")

    print(f"\nStatistics for entire network:")
    edges_no_outliers = edges_projected[edges_projected['length_m'] <= 500].copy()
    print(f"Total links: {len(edges_no_outliers)}")
    print(f"Minimum length: {edges_no_outliers['length_m'].min():.2f} meters")
    print(f"Maximum length: {edges_no_outliers['length_m'].max() / 1000:.2f} km")
    print(f"Average length: {edges_no_outliers['length_m'].mean():.2f} meters")
    print(f"Median length: {edges_no_outliers['length_m'].median():.2f} meters")

    # Create histogram for entire network
    plt.figure(figsize=(12, 6))
    plt.hist(edges_no_outliers['length_m'], bins=50, edgecolor='black', alpha=0.7)
    plt.xlabel('Length (meters)')
    plt.ylabel('Number of Links')
    plt.title('Distribution of All Link Lengths in Network')
    plt.grid(True, alpha=0.3)

    # Add vertical lines for mean and median
    plt.axvline(edges_no_outliers['length_m'].mean(), color='red', linestyle='--',
                linewidth=2, label=f'Mean: {edges_no_outliers["length_m"].mean():.2f}m')
    plt.axvline(edges_no_outliers['length_m'].median(), color='green', linestyle='--',
                linewidth=2, label=f'Median: {edges_no_outliers["length_m"].median():.2f}m')
    plt.legend()

    plt.tight_layout()
    plt.savefig('all_links_histogram.png', dpi=300)
    print(f"\nHistogram saved to 'all_links_histogram.png'")
    plt.show()


if __name__ == "__main__":
    main()