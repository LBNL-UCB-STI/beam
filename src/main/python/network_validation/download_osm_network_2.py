"""
OSM Network Downloader and Processor
Downloads and processes OpenStreetMap network data for transportation analysis.
@author: cristian-poliziani, haitamlaarabi, zaneedell
"""

import os
from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional, Union, Tuple
import logging
from pathlib import Path
import json

import osmnx as ox
import networkx as nx
import contextily as ctx
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
import pickle
import xml.etree.ElementTree as ET
from shapely.geometry import Polygon, MultiPolygon

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


@dataclass
class StudyArea:
    """Configuration for the study area."""
    name: str
    country: str
    subdivisions: List[Dict[str, str]]

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'StudyArea':
        """Create StudyArea from dictionary configuration."""
        return cls(
            name=data['name'],
            country=data['country'],
            subdivisions=data['subdivisions']
        )

    @classmethod
    def load_from_json(cls, filepath: Union[str, Path]) -> 'StudyArea':
        """Load study area configuration from JSON file."""
        with open(filepath, 'r') as f:
            data = json.load(f)
        return cls.from_dict(data)

    def to_json(self, filepath: Union[str, Path]):
        """Save study area configuration to JSON file."""
        data = {
            'name': self.name,
            'country': self.country,
            'subdivisions': self.subdivisions
        }
        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)


@dataclass
class NetworkConfig:
    """Configuration settings for network download and processing."""
    study_area: StudyArea
    simplification_tolerance: float = 2  # meters
    split_links_by: List[str] = field(default_factory=lambda: ["highway", "lanes", "maxspeed"])
    network_type: str = "drive"
    retain_all: bool = True
    custom_filters: Dict[str, str] = field(default_factory=lambda: {
        "residential": '["highway"~"residential"]',
        "main": '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]',
        "all": '["highway"~"residential|motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
    })

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'NetworkConfig':
        """Create NetworkConfig from dictionary configuration."""
        study_area = StudyArea.from_dict(data['study_area'])
        return cls(
            study_area=study_area,
            simplification_tolerance=data.get('simplification_tolerance', 2),
            split_links_by=data.get('split_links_by', ["highway", "lanes", "maxspeed"]),
            network_type=data.get('network_type', "drive"),
            retain_all=data.get('retain_all', True),
            custom_filters=data.get('custom_filters', cls.custom_filters.default_factory())
        )

    @classmethod
    def load_from_json(cls, filepath: Union[str, Path]) -> 'NetworkConfig':
        """Load network configuration from JSON file."""
        with open(filepath, 'r') as f:
            data = json.load(f)
        return cls.from_dict(data)

    def to_json(self, filepath: Union[str, Path]):
        """Save network configuration to JSON file."""
        data = {
            'study_area': {
                'name': self.study_area.name,
                'country': self.study_area.country,
                'subdivisions': self.study_area.subdivisions
            },
            'simplification_tolerance': self.simplification_tolerance,
            'split_links_by': self.split_links_by,
            'network_type': self.network_type,
            'retain_all': self.retain_all,
            'custom_filters': self.custom_filters
        }
        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)


class NetworkDownloader:
    """Handles downloading OSM network data using different methods."""

    def __init__(self, config: NetworkConfig):
        self.config = config

    def download_network(self) -> nx.MultiDiGraph:
        """Download network based on study area configuration."""
        logger.info(f"Downloading network data for {self.config.study_area.name}")

        graphs = []
        for subdivision in self.config.study_area.subdivisions:
            try:
                G = ox.graph_from_place(
                    subdivision,
                    network_type=self.config.network_type,
                    simplify=False,
                    retain_all=self.config.retain_all,
                    custom_filter=self.config.custom_filters['all']
                )
                graphs.append(G)
                logger.info(f"Successfully downloaded network for {subdivision}")
            except Exception as e:
                logger.error(f"Failed to download network for {subdivision}: {str(e)}")

        return nx.compose_all(graphs) if graphs else None


class NetworkProcessor:
    """Processes downloaded network data."""

    def __init__(self, config: NetworkConfig):
        self.config = config

    def process_network(self, G: nx.MultiDiGraph) -> nx.MultiDiGraph:
        """Apply all processing steps to the network."""
        if G is None:
            logger.error("No network to process")
            return None

        logger.info("Processing network...")

        # Project to Web Mercator
        G = ox.project_graph(G, to_crs="epsg:3857")

        # Add edge attributes
        G = self._add_edge_attributes(G)

        # Consolidate intersections
        G = self._consolidate_intersections(G)

        # Simplify network
        G = self._simplify_network(G)

        return G

    def _add_edge_attributes(self, G: nx.MultiDiGraph) -> nx.MultiDiGraph:
        """Add speed and other attributes to edges."""
        logger.info("Adding edge attributes...")
        G = ox.add_edge_speeds(G)
        return G

    def _consolidate_intersections(self, G: nx.MultiDiGraph) -> nx.MultiDiGraph:
        """Consolidate nearby intersections."""
        logger.info("Consolidating intersections...")
        G = ox.consolidate_intersections(
            G,
            tolerance=self.config.simplification_tolerance,
            rebuild_graph=True,
            dead_ends=True,
            reconnect_edges=True
        )

        # Update edge lengths
        nodes, edges = ox.graph_to_gdfs(G)
        edges['length'] = edges['geometry'].length
        G = ox.graph_from_gdfs(nodes, edges, graph_attrs=G.graph)

        return G

    def _simplify_network(self, G: nx.MultiDiGraph) -> nx.MultiDiGraph:
        """Simplify network topology."""
        logger.info("Simplifying network...")
        return ox.simplification.simplify_graph(
            G,
            edge_attrs_differ=self.config.split_links_by,
            remove_rings=False,
            track_merged=True
        )


class NetworkVisualizer:
    """Handles network visualization and plotting."""

    def __init__(self, output_dir: Path):
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def plot_network(self, G: nx.MultiDiGraph, name: str, dpi: int = 600):
        """Plot basic network visualization."""
        if G is None:
            logger.error("No network to plot")
            return

        fig, ax = ox.plot.plot_graph(
            G,
            bgcolor="#FFFFFF",
            node_color="#333333",
            node_size=0.02,
            node_edgecolor='none',
            node_zorder=3,
            edge_color="#FF5A5F",
            edge_linewidth=0.2,
            edge_alpha=0.8,
            show=False,
            close=False
        )

        # Add basemap
        ctx.add_basemap(ax, source=ctx.providers.CartoDB.Positron)

        # Add statistics
        self._add_network_stats(G, ax)

        # Save figure
        output_path = self.output_dir / f"{name}.png"
        fig.savefig(output_path, dpi=dpi, bbox_inches='tight')
        plt.close(fig)

    def plot_attribute(self, G: nx.MultiDiGraph, attribute: str, name: str):
        """Plot network colored by attribute."""
        if G is None:
            logger.error("No network to plot")
            return

        attribute_values = [G.edges[edge].get(attribute, 'unknown') for edge in G.edges]

        # Create color scheme
        if isinstance(attribute_values[0], (str, bool)):
            colors = self._create_categorical_colors(attribute_values)
        else:
            colors = self._create_numerical_colors(attribute_values)

        self._plot_colored_network(G, colors, attribute, name)

    def _create_categorical_colors(self, values):
        unique_values = list(set(values))
        colors = plt.cm.get_cmap('tab20', len(unique_values))(range(len(unique_values)))
        color_map = dict(zip(unique_values, colors))
        return [color_map[val] for val in values]

    def _create_numerical_colors(self, values):
        norm = mcolors.Normalize(vmin=min(values), vmax=max(values))
        color_map = plt.cm.ScalarMappable(norm=norm, cmap='plasma')
        return [color_map.to_rgba(val) for val in values]

    def _add_network_stats(self, G: nx.MultiDiGraph, ax: plt.Axes):
        """Add network statistics to plot."""
        num_nodes = len(G.nodes)
        num_edges = len(G.edges)
        total_length = sum(data.get('length', 0) for _, _, _, data in G.edges(keys=True, data=True))

        title = f"Nodes: {num_nodes} | Edges: {num_edges} | Total Length: {total_length / 1000:.2f} km"
        ax.set_title(title, fontsize=15, fontweight='bold', color='black', pad=20)


class NetworkExporter:
    """Handles exporting network to various formats."""

    def __init__(self, output_dir: Path):
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def save_pickle(self, G: nx.MultiDiGraph, name: str):
        """Save network as pickle file."""
        if G is None:
            logger.error("No network to save")
            return

        output_path = self.output_dir / f"{name}.pkl"
        with open(output_path, 'wb') as f:
            pickle.dump(G, f)
        logger.info(f"Saved network to {output_path}")

    def save_geopackage(self, G: nx.MultiDiGraph, name: str):
        """Save network as GeoPackage."""
        if G is None:
            logger.error("No network to save")
            return

        output_path = self.output_dir / f"{name}.gpkg"
        ox.save_graph_geopackage(G, filepath=str(output_path))
        logger.info(f"Saved network to {output_path}")

    def save_osm(self, G: nx.MultiDiGraph, name: str):
        """Save network as OSM XML file."""
        if G is None:
            logger.error("No network to save")
            return

        output_path = self.output_dir / f"{name}.osm"

        # Create OSM XML structure
        root = self._create_osm_root(G)

        # Write nodes
        node_map = self._write_osm_nodes(G, root)

        # Write ways
        self._write_osm_ways(G, root, node_map)

        # Save file
        ET.ElementTree(root).write(output_path, encoding="utf-8", xml_declaration=True)
        logger.info(f"Saved network to {output_path}")

    def _create_osm_root(self, G: nx.MultiDiGraph) -> ET.Element:
        """Create OSM XML root element with bounds."""
        xs = [d['x'] for _, d in G.nodes(data=True) if 'x' in d]
        ys = [d['y'] for _, d in G.nodes(data=True) if 'y' in d]

        root = ET.Element("osm", version="0.6", generator="OSMnx2OSM")
        ET.SubElement(root, "bounds",
                      minlat=str(min(ys)), minlon=str(min(xs)),
                      maxlat=str(max(ys)), maxlon=str(max(xs)))
        return root

    def _write_osm_nodes(self, G: nx.MultiDiGraph, root: ET.Element) -> Dict[Any, int]:
        """Write nodes to OSM XML and return node ID mapping."""
        node_map = {}
        node_id = 1

        for n, d in G.nodes(data=True):
            lat, lon = d.get('y'), d.get('x')
            if lat is None or lon is None:
                continue

            node = ET.SubElement(root, "node",
                                 id=str(node_id),
                                 lat=str(lat),
                                 lon=str(lon),
                                 version="1",
                                 changeset="1",
                                 user="osmnx",
                                 uid="1",
                                 timestamp="2020-01-01T00:00:00Z")

            node_map[n] = node_id

            # Add node tags
            for k, v in d.items():
                if k not in ("x", "y") and v is not None:
                    ET.SubElement(node, "tag", k=str(k), v=str(v))

            node_id += 1

        return node_map

    def _write_osm_ways(self, G: nx.MultiDiGraph, root: ET.Element, node_map: Dict[Any, int]):
        """Write ways (edges) to OSM XML."""
        way_id = -1

        for u, v, edata in G.edges(data=True):
            if u not in node_map or v not in node_map:
                continue

            way = ET.SubElement(root, "way",
                                id=str(way_id),
                                version="1",
                                changeset="1",
                                user="osmnx",
                                uid="1",
                                timestamp="2020-01-01T00:00:00Z")

            ET.SubElement(way, "nd", ref=str(node_map[u]))
            ET.SubElement(way, "nd", ref=str(node_map[v]))

            # Add required highway tag
            ET.SubElement(way, "tag", k="highway", v="road")

            # Add edge tags
            for k, v_ in edata.items():
                if v_ is not None:
                    ET.SubElement(way, "tag", k=str(k), v=str(v_))

            way_id -= 1


def create_sfbay_config() -> Dict[str, Any]:
    """Create an example configuration dictionary."""
    return {
        "study_area": {
            "name": "SF Bay Area",
            "country": "United States",
            "subdivisions": [
                {"county": county, "state": "California"}
                for county in [
                    "San Francisco", "Alameda", "Contra Costa", "Marin",
                    "Napa", "San Mateo", "Santa Clara", "Solano", "Sonoma"
                ]
            ]
        },
        "simplification_tolerance": 2,
        "split_links_by": ["highway", "lanes", "maxspeed"],
        "network_type": "drive",
        "retain_all": True,
        "custom_filters": {
            "residential": '["highway"~"residential"]',
            "main": '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]',
            "all": '["highway"~"residential|motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
        }
    }


def main():
    """Main execution function."""
    # Create example configuration
    config_dir = Path("config")
    config_dir.mkdir(exist_ok=True)

    example_config_path = config_dir / "example_config.json"
    if not example_config_path.exists():
        example_config = create_sfbay_config()
        with open(example_config_path, 'w') as f:
            json.dump(example_config, f, indent=2)
        logger.info(f"Created example configuration at {example_config_path}")

    # Load configuration
    config = NetworkConfig.load_from_json(example_config_path)

    # Initialize components
    output_dir = Path("output") / config.study_area.name.lower().replace(" ", "_")
    output_dir.mkdir(parents=True, exist_ok=True)

    downloader = NetworkDownloader(config)
    processor = NetworkProcessor(config)
    visualizer = NetworkVisualizer(output_dir)
    exporter = NetworkExporter(output_dir)

    try:
        # Download network
        G = downloader.download_network()

        # Process network
        G = processor.process_network(G)

        if G is not None:
            # Create visualizations
            visualizer.plot_network(G, "network")
            visualizer.plot_attribute(G, "highway", "highway_types")
            visualizer.plot_attribute(G, "lanes", "lanes")

            # Export network
            exporter.save_pickle(G, "network")
            exporter.save_geopackage(G, "network")
            exporter.save_osm(G, "network")

            # Save configuration used
            config.to_json(output_dir / "config_used.json")

    except Exception as e:
        logger.error(f"Error processing network: {str(e)}")


if __name__ == "__main__":
    main()
