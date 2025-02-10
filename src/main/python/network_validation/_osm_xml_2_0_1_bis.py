"""
@author: haitamlaarabi
Updating the osm_xml from OSMNX 2.0.1 to support unsimplified network while maintaining mapping to original OSM ways
"""

from pathlib import Path
from typing import Any
from xml.etree.ElementTree import Element, SubElement, ElementTree

import networkx as nx
import osmnx as ox
import pandas as pd
from warnings import warn
from osmnx import settings
import geopandas as gpd

import bz2
import gzip
import logging as lg
from contextlib import contextmanager
from pathlib import Path
from typing import TYPE_CHECKING
from typing import Any
from typing import TextIO
from warnings import warn
from xml.etree.ElementTree import Element
from xml.etree.ElementTree import ElementTree
from xml.etree.ElementTree import SubElement
from xml.etree.ElementTree import parse as etree_parse
from xml.sax import parse as sax_parse
from xml.sax.handler import ContentHandler

from osmnx import convert
from osmnx import projection
from osmnx import settings
from osmnx import truncate
from osmnx import utils
from osmnx import _osm_xml


def _save_graph_xml(
        G: nx.MultiDiGraph,
        filepath: str | Path | None,
        way_tag_aggs: dict[str, Any] | None,
        encoding: str = "utf-8",
) -> None:
    """
    Save graph to disk as an OSM XML file. Handles both simplified and unsimplified graphs.
    For simplified graphs, maintains mapping between simplified edges and original OSM ways.

    Parameters
    ----------
    G : nx.MultiDiGraph
        Graph to save as OSM XML file. Can be either simplified or unsimplified.
    filepath : str | Path | None
        Path to the saved file including extension. If None, use default
        `settings.data_folder/graph.osm`.
    way_tag_aggs : dict[str, Any] | None
        Keys are OSM way tag keys and values are aggregation functions.
    encoding : str
        The character encoding of the saved OSM XML file.

    Returns
    -------
    None
    """
    # default "oneway" value used to fill this tag where missing
    ONEWAY = False

    # round lat/lon coordinates to 7 decimals (approx 5 to 10 mm resolution)
    PRECISION = 7

    # warn user if ox.settings.all_oneway is not currently True
    if not ox.settings.all_oneway:
        msg = "Make sure graph was created with `ox.settings.all_oneway=True` to save as OSM XML."
        warn(msg, category=UserWarning, stacklevel=2)

    # warn user if graph is projected
    if ox.projection.is_projected(G.graph["crs"]):
        msg = (
            "Graph should be unprojected to save as OSM XML: the existing "
            "projected x-y coordinates will be saved as lat-lon node attributes. "
            "Project your graph back to lat-lon to avoid this."
        )
        warn(msg, category=UserWarning, stacklevel=2)

    # set default filepath if None was provided
    filepath = Path(ox.settings.data_folder) / "graph.osm" if filepath is None else Path(filepath)
    filepath.parent.mkdir(parents=True, exist_ok=True)

    # convert graph to node/edge gdfs
    gdf_nodes, gdf_edges = ox.convert.graph_to_gdfs(G, fill_edge_geometry=True)
    coords = [str(round(c, PRECISION)) for c in gdf_nodes.union_all().bounds]
    bounds = dict(zip(["minlon", "minlat", "maxlon", "maxlat"], coords))

    # add default values (if missing) for standard attrs
    for gdf in (gdf_nodes, gdf_edges):
        for col, value in _osm_xml.ATTR_DEFAULTS.items():
            if col not in gdf.columns:
                gdf[col] = value
            else:
                gdf[col] = gdf[col].fillna(value)

    # transform nodes gdf to meet OSM XML spec
    gdf_nodes = gdf_nodes.reset_index().rename(columns={"osmid": "id", "x": "lon", "y": "lat"})
    gdf_nodes[["lon", "lat"]] = gdf_nodes[["lon", "lat"]].round(PRECISION)
    gdf_nodes = gdf_nodes.drop(columns=["geometry"])

    # handle simplified graph edges
    is_simplified = G.graph.get("simplified", False)
    if is_simplified:
        # Extract nodes from edge geometries for simplified graphs
        gdf_edges = _process_simplified_edges(gdf_edges, PRECISION)

    # transform edges gdf to meet OSM XML spec
    if "oneway" in gdf_edges.columns:
        gdf_edges["oneway"] = gdf_edges["oneway"].fillna(ONEWAY).replace({True: "yes", False: "no"})
    gdf_edges = gdf_edges.rename(columns={"osmid": "id"})

    # create parent XML element then add bounds, nodes, ways as subelements
    element = Element("osm", attrib=_osm_xml.ROOT_ATTR_DEFAULTS)
    _ = SubElement(element, "bounds", attrib=bounds)
    _osm_xml._add_nodes_xml(element, gdf_nodes)
    _add_ways_xml(element, gdf_edges, way_tag_aggs, is_simplified)

    # write to disk
    ElementTree(element).write(filepath, encoding=encoding, xml_declaration=True)
    msg = f"Saved graph as OSM XML file at {str(filepath)!r}"
    utils.log(msg, level=lg.INFO)


def _process_simplified_edges(
        gdf_edges: gpd.GeoDataFrame,
        precision: int,
) -> gpd.GeoDataFrame:
    """
    Process simplified graph edges to extract intermediate nodes from geometries.

    Parameters
    ----------
    gdf_edges : gpd.GeoDataFrame
        GeoDataFrame of graph edges
    precision : int
        Precision for rounding coordinates

    Returns
    -------
    gpd.GeoDataFrame
        Processed edges with extracted nodes
    """
    processed_edges = []

    for idx, edge in gdf_edges.iterrows():
        # Skip edges without geometry (shouldn't happen in simplified graphs)
        if not edge.get('geometry'):
            processed_edges.append(edge)
            continue

        # Extract coordinates from the LineString geometry
        coords = [(round(x, precision), round(y, precision))
                  for x, y in edge.geometry.coords]

        # Create new node IDs for intermediate points if needed
        node_ids = []
        node_ids.append(idx[0])  # First node is the origin

        # Add intermediate nodes (will be added to XML later)
        for i, (x, y) in enumerate(coords[1:-1], start=1):
            node_id = f"{idx[0]}_{idx[1]}_{i}"  # Create unique ID for intermediate node
            node_ids.append(node_id)

        node_ids.append(idx[1])  # Last node is the destination

        # Store the node sequence for the way
        edge_dict = edge.to_dict()
        edge_dict['node_sequence'] = node_ids
        edge_dict['coords'] = coords
        processed_edges.append(edge_dict)

    return pd.DataFrame(processed_edges)


def _add_ways_xml(
        parent: Element,
        gdf_edges: gpd.GeoDataFrame,
        way_tag_aggs: dict[str, Any] | None,
        is_simplified: bool,
) -> None:
    """
    Add graph edges (grouped as ways) as subelements of an XML parent element.
    Handles both simplified and unsimplified graphs.

    Parameters
    ----------
    parent : Element
        The XML parent element
    gdf_edges : gpd.GeoDataFrame
        A GeoDataFrame of graph edges
    way_tag_aggs : dict[str, Any] | None
        Edge attribute aggregation functions
    is_simplified : bool
        Whether the graph is simplified
    """
    way_tags = set(settings.useful_tags_way)
    way_attrs = list({"id"}.union(_osm_xml.ATTR_DEFAULTS))

    # Handle different edge grouping for simplified vs unsimplified graphs
    if is_simplified:
        # For simplified graphs, each edge might represent multiple original ways
        for _, edge in gdf_edges.iterrows():
            # Create way element
            attrs = {k: str(edge[k]) for k in way_attrs if pd.notna(edge[k])}
            way_element = SubElement(parent, "way", attrib=attrs)

            # Add nodes (including intermediate nodes from geometry)
            for node_id in edge['node_sequence']:
                _ = SubElement(way_element, "nd", attrib={"ref": str(node_id)})

            # Add original way IDs as a relation if merged_edges exists
            if 'merged_edges' in edge:
                relation = SubElement(parent, "relation", attrib={"type": "simplified_way"})
                for u, v in edge['merged_edges']:
                    _ = SubElement(relation, "member", attrib={
                        "type": "way",
                        "ref": str(edge['id']),
                        "role": "simplified"
                    })

            # Add tags
            for tag in way_tags.intersection(edge.index):
                if pd.notna(edge[tag]):
                    _ = SubElement(way_element, "tag", attrib={"k": tag, "v": str(edge[tag])})
    else:
        # Original way handling for unsimplified graphs
        for osmid, way in gdf_edges.groupby("id"):
            attrs = way[way_attrs].iloc[0].astype(str).to_dict()
            way_element = SubElement(parent, "way", attrib=attrs)

            # Add nodes
            if len(way) == 1:
                nodes = way.index[0][:2]
            else:
                nodes = _osm_xml._sort_nodes(nx.MultiDiGraph(way.index.to_list()), osmid)

            for node in nodes:
                _ = SubElement(way_element, "nd", attrib={"ref": str(node)})

            # Add tags
            for tag in way_tags.intersection(way.columns):
                if way_tag_aggs is not None and tag in way_tag_aggs:
                    value = way[tag].agg(way_tag_aggs[tag])
                else:
                    value = way[tag].iloc[0]
                if pd.notna(value):
                    _ = SubElement(way_element, "tag", attrib={"k": tag, "v": str(value)})
