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
import pandas as pd

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
class StudyAreaConfig:
    """Configuration for the study area."""
    name: str
    country: str
    state: str
    dense_counties: List[str]
    moderate_counties: List[str]
    area_crs: str  # Area-specific CRS

    @property
    def subdivisions(self) -> List[Dict[str, str]]:
        """Generate subdivisions list from dense and moderate counties."""
        all_counties = self.dense_counties + self.moderate_counties
        return [{"county": county, "state": self.state} for county in all_counties]

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'StudyAreaConfig':
        """Create StudyArea from dictionary configuration."""
        # Check if the data is nested under a 'study_area' key
        study_area_data = data.get('study_area', data)

        return cls(
            name=study_area_data['name'],
            country=study_area_data['country'],
            state=study_area_data['state'],
            dense_counties=study_area_data['dense_counties'],
            moderate_counties=study_area_data['moderate_counties'],
            area_crs=study_area_data['area_crs']
        )

    def to_dict(self) -> Dict[str, Any]:
        """Convert StudyArea to dictionary."""
        return {
            'name': self.name,
            'country': self.country,
            'state': self.state,
            'dense_counties': self.dense_counties,
            'moderate_counties': self.moderate_counties,
            'area_crs': self.area_crs,
            'subdivisions': self.subdivisions  # Include generated subdivisions
        }

    @classmethod
    def create_by_area(cls, area_name: str) -> 'StudyAreaConfig':
        """Create StudyArea configuration based on area name."""
        area_configs = {
            "sfbay": {
                "name": "SF Bay Area",
                "country": "United States",
                "state": "California",
                # "dense_counties": ["San Francisco", "Alameda", "San Mateo", "Santa Clara"],
                # "moderate_counties": ["Marin", "Contra Costa", "Solano", "Sonoma", "Napa"],
                "dense_counties": [],
                "moderate_counties": ["Napa"],
                "area_crs": "epsg:26910"  # NAD83 / UTM zone 10N - appropriate for Bay Area
            },
            "seattle": {
                "name": "Greater Seattle",
                "country": "United States",
                "state": "Washington",
                "dense_counties": ["King"],
                "moderate_counties": ["Snohomish", "Pierce"],
                "area_crs": "epsg:32148"  # NAD83 / UTM zone 10N - appropriate for Seattle
            },
            "austin": {
                "name": "Greater Austin",
                "country": "United States",
                "state": "Texas",
                "dense_counties": ["Travis"],
                "moderate_counties": ["Williamson", "Hays", "Bastrop", "Caldwell"],
                "area_crs": "epsg:32614"  # WGS 84 / UTM zone 14N - appropriate for Austin
            },
            "nyc": {
                "name": "New York City Metro",
                "country": "United States",
                "state": "New York",
                "dense_counties": ["New York", "Kings", "Queens", "Bronx", "Richmond"],
                "moderate_counties": ["Nassau", "Westchester", "Suffolk", "Bergen", "Hudson"],
                "area_crs": "epsg:32618"  # WGS 84 / UTM zone 18N - appropriate for NYC
            }
        }

        if area_name not in area_configs:
            raise ValueError(f"Study area '{area_name}' not supported. Available areas: {list(area_configs.keys())}")

        return cls.from_dict(area_configs[area_name])


@dataclass
class VehicleConfig:
    """
    Configuration for vehicle weight classifications based on Federal Highway Administration standards.
    https://afdc.energy.gov/data/10380
    Weights are stored in both US and metric units for OSM compatibility.

    Weight Classes:
    - Light Duty: < 10,000 lbs (< 4.536 metric tons)
    - Medium Duty: 10,001 - 26,000 lbs (4.537 - 11.793 metric tons)
    - Heavy Duty: > 26,001 lbs (> 11.794 metric tons)
    """
    # Light Duty Vehicle upper limit
    # ldv_max_metric_tons: float = 4.536  # 10,000 lbs in metric tons
    # ldv_max_lbs: float = 10000

    # Medium Duty Vehicle upper limit
    mdv_max_metric_tons: float = 11.793  # 26,000 lbs in metric tons
    mdv_max_lbs: float = 26000

    # Heavy Duty Vehicle limits
    hdv_max_metric_tons: float = 36.287  # 80,000 lbs in metric tons
    hdv_max_lbs: float = 80000

    # Additional California weight limits converted to metric tons
    # steering_axle_max_metric_tons: float = 5.67  # 12,500 lbs
    # single_axle_max_metric_tons: float = 9.072  # 20,000 lbs
    # tandem_axle_max_metric_tons: float = 15.422  # 34,000 lbs

    @staticmethod
    def lbs_to_metric_tons(lbs: float) -> float:
        """Convert pounds to metric tons."""
        return lbs / 2204.62

    @staticmethod
    def metric_tons_to_lbs(tons: float) -> float:
        """Convert metric tons to pounds."""
        return tons * 2204.62


@dataclass
class CRSConfig:
    """Configuration for coordinate reference systems."""
    input_crs: str = "epsg:4326"  # WGS84 - OSM's native CRS
    working_crs: str = "epsg:3857"  # Web Mercator for analysis


@dataclass
class NetworkConfig:
    """Configuration settings for network download and processing."""
    study_area: StudyAreaConfig
    crs_config: CRSConfig = field(default_factory=CRSConfig)
    simplification_tolerance: float = 2  # meters
    split_links_by: List[str] = field(default_factory=lambda: ["highway", "lanes", "maxspeed"])
    network_type: str = "drive"
    retain_all: bool = True
    custom_filters: Dict[str, str] = field(default_factory=lambda: {
        "default": '["highway"~"motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]',
    })
    vehicle_config: VehicleConfig = field(default_factory=VehicleConfig)

    def to_json(self, filepath: Union[str, Path]):
        """Save network configuration to JSON file."""
        with open(filepath, 'w') as f:
            json.dump(self.to_dict(), f, indent=2)


class NetworkDownloader:
    """Handles downloading OSM network data using different methods."""

    def __init__(self, config: NetworkConfig):
        self.config = config

    def download_network(self) -> nx.MultiDiGraph:
        """Download network based on study area configuration."""
        logger.info(f"Downloading network data for {self.config.study_area.name}")

        # Enable console logging
        ox.settings.log_console = True

        # Enable caching
        ox.settings.use_cache = True

        # Treat all edges as one-way
        ox.settings.all_oneway = True

        useful_tags_way = [
            "highway",
            "highway=motorway",
            "highway=trunk",
            "highway=primary",
            "highway=secondary",
            "highway=tertiary",
            "highway=motorway_link",
            "highway=trunk_link",
            "highway=primary_link",
            "highway=secondary_link",
            "highway=tertiary_link",
            "highway=unclassified",
            "highway=residential",
            "maxweight",
            "maxweight:hgv",
            "hgv",
            "maxheight",
            "maxspeed",
            "oneway=*",
            "lanes"
        ]

        useful_tags_node = []

        # Set the useful tags in OSMnx settings
        # ox.settings.useful_tags_node = useful_tags_node
        ox.settings.useful_tags_way = useful_tags_way

        graphs = []
        for subdivision in self.config.study_area.subdivisions:
            try:
                # Get county-specific filter or default if not found
                county = subdivision['county'].lower().replace(" ", "_")
                custom_filter = self.config.custom_filters.get(
                    county,
                    self.config.custom_filters.get('default')  # Use default filter if county not found
                )

                G = ox.graph_from_place(
                    subdivision,
                    network_type=self.config.network_type,
                    simplify=False,
                    retain_all=self.config.retain_all,
                    custom_filter=custom_filter
                )
                graphs.append(G)
                logger.info(
                    f"Successfully downloaded network for {subdivision['county']} using {'custom' if county in self.config.custom_filters else 'default'} filter")
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

        # Project to configured CRS
        G = ox.project_graph(G, to_crs=self.config.crs_config.working_crs)
        logger.info(
            f"Projected network from {self.config.crs_config.input_crs} to {self.config.crs_config.working_crs}")

        # Add edge attributes
        G = self._add_edge_attributes(G)

        # Process vehicle classifications
        G = self._process_vehicle_classifications(G)

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
            track_merged=True,
            edge_attr_aggs={
                "length": sum,
                "travel_time": sum,
                "lanes": "strmedian",
                "hgv": min,
                "mdv": min
            }
        )

    def _process_vehicle_classifications(self, G: nx.MultiDiGraph) -> nx.MultiDiGraph:
        """Process vehicle classifications based on FHWA weight classes, handling metric tons from OSM."""
        logger.info("Processing vehicle classifications...")

        nodes, edges = ox.graph_to_gdfs(G)

        # Copy HGV weight restrictions if present
        if "maxweight:hgv" in edges.columns:
            edges.loc[~edges["maxweight:hgv"].isna(), "maxweight"] = edges.loc[
                ~edges["maxweight:hgv"].isna(), "maxweight:hgv"].copy()

        if "maxweight" in edges.columns:
            # Identify weight formats
            weightInMetricTons = edges["maxweight"].str.contains(" st").fillna(value=False)  # st means metric tons
            weightInLbs = edges["maxweight"].str.contains(" lbs").fillna(value=False)

            # Convert weights to numeric values
            numericWeight = (edges["maxweight"]
                             .str.replace(r"\s*st", "", regex=True)
                             .str.replace(r"\s*lbs", "", regex=True).astype(float)
                             )

            # Check weight restrictions using metric tons for OSM values
            mdvBannedByWeight = (
                    (weightInMetricTons & (numericWeight <= self.config.vehicle_config.mdv_max_metric_tons)) |
                    (weightInLbs & (numericWeight <= self.config.vehicle_config.mdv_max_lbs))
            )
            hdvBannedByWeight = (
                    (weightInMetricTons & (numericWeight <= self.config.vehicle_config.hdv_max_metric_tons)) |
                    (weightInLbs & (numericWeight <= self.config.vehicle_config.hdv_max_lbs))
            )
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

        edges["hgv"] = hgv.copy()
        edges["mdv"] = mdv.copy()

        # Convert back to graph
        G = ox.graph_from_gdfs(nodes, edges, graph_attrs=G.graph)

        return G


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


def create_config_by_area(study_area: str) -> Dict[str, Any]:
    """Creates configuration based on study area name."""

    # Base highway types for all areas
    sparse_network_filter = ["motorway", "trunk", "motorway_link", "trunk_link",
                             "primary", "secondary", "primary_link", "secondary_link",
                             "tertiary", "tertiary_link"]
    moderate_network_filter = sparse_network_filter + ["unclassified"]
    dense_network_filter = moderate_network_filter + ["residential"]

    # Create StudyArea configuration
    study_area_config = StudyAreaConfig.create_by_area(study_area)

    # Create county filters
    county_filters = {}

    # Set default filter
    default_filter = '["highway"~"' + '|'.join(sparse_network_filter) + '"]'
    county_filters['default'] = default_filter

    # Add dense county filters
    for county in study_area_config.dense_counties:
        filter_str = '["highway"~"' + '|'.join(dense_network_filter) + '"]'
        county_filters[county.lower().replace(" ", "_")] = filter_str

    # Add moderate county filters
    for county in study_area_config.moderate_counties:
        filter_str = '["highway"~"' + '|'.join(moderate_network_filter) + '"]'
        county_filters[county.lower().replace(" ", "_")] = filter_str

    # Create final configuration
    return {
        "study_area": study_area_config,
        "crs_config": CRSConfig(
            input_crs="epsg:4326",  # OSM data is always in WGS84
            working_crs=study_area_config.area_crs
        ),
        "simplification_tolerance": 2,
        "split_links_by": ["highway", "lanes", "maxspeed"],
        "network_type": "drive",
        "retain_all": True,
        "custom_filters": county_filters,
        "vehicle_config": {
            "mdv_max_metric_tons": 11.793,
            "hdv_max_metric_tons": 36.287,
            "mdv_max_lbs": 26000,
            "hdv_max_lbs": 80000
        }
    }


def process_study_area(study_area: str) -> None:
    """Process a specific study area."""
    # Generate configuration directly
    network_config = NetworkConfig(**create_config_by_area(study_area))

    # Initialize components
    output_dir = Path("output") / study_area
    output_dir.mkdir(parents=True, exist_ok=True)

    downloader = NetworkDownloader(network_config)
    processor = NetworkProcessor(network_config)
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
            network_config.to_json(output_dir / "config_used.json")

    except Exception as e:
        logger.error(f"Error processing {study_area}: {str(e)}")


def main():
    """Main execution function."""
    # Create config directory
    config_dir = Path("config")
    config_dir.mkdir(exist_ok=True)

    # List of available study areas
    study_areas = ["sfbay", "seattle", "austin", "nyc"]

    # Process specific area or all areas
    selected_area = "sfbay"  # Change this to process different areas
    # selected_area = None  # Set to None to process all areas

    if selected_area is not None:
        if selected_area not in study_areas:
            raise ValueError(f"Invalid study area. Choose from: {study_areas}")
        process_study_area(selected_area)
    else:
        # Process all areas
        for area in study_areas:
            logger.info(f"Processing {area}...")
            process_study_area(area)


if __name__ == "__main__":
    main()
