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

# in the settings specify a single date


# INPUTS
# Generate the graph in 6 different ways
# Turn on the methods to be used, and insert the inputs
# Use lists to combine multiple graphs, and single values for common filter parameters

is_addresses = False
is_bboxes = False
is_places = True
is_points = False
is_polygons = False
is_xmls = False

# 0.00008983 = 10m
simpl_intersections = 2
splitLinksBy = ["highway", "lanes", "maxspeed"]

# Define custom filters
cf1 = '["highway"~"motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
cf3 = '["highway"~"residential|motorway|primary|trunk|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified"]'
cf2 = '["highway"~"residential"]'
cf_main_highways = '["highway"="motorway"]'

##############################  ADDRESS  ##############################
addresses = ["San Francisco, CA, USA", "San Francisco, CA, USA"]
# addresses = ["Milano, IT", "Milano, IT"]
addresses_filters = {
    "dist": 5000,
    "dist_type": 'bbox',  # "network", "bbox" if “bbox”, retain only those nodes within a
    # bounding box of the distance parameter. if “network”, retain
    # only those nodes within some network distance from the center-most node.
    "network_type": "drive",  # all", "all_public", "bike", "drive", "drive_service", "walk"
    "simplify": False,
    "retain_all": True,  # if True, return the entire graph even if it is not connected. otherwise,
    # retain only the largest weakly connected component.
    "truncate_by_edge": None,  # if True, retain nodes outside bounding box if at least one
    # of node’s neighbors is within the bounding box
    "custom_filter": [cf2, cf1]  # '["highway"~"motorway|trunk"]' ‘[“power”~”line”]’ or ‘[“highway”~”motorway|trunk”]’.
}

##############################  BBOX  ##############################

# Input bounding boxes (list of tuples representing bounding box coordinates)
bboxes = [(37.8, 37.7, -122.5, -122.4), (37.9, 37.8, -122.3, -122.2)]
bboxes_filters = {
    "network_typeTrue": ["drive", "bike"],
    "simplify": [False, False],
    "retain_all": True,
    "truncate_by_edge": [True, True],
    "custom_filter": [cf2, cf1]
}

##############################  PLACE  ##############################

# Input places (list of place names)
places = [
    {"county": "San Francisco", "state": "California"},
    {"county": "Alameda", "state": "California"},
    {"county": "Contra Costa", "state": "California"},
    {"county": "Marin", "state": "California"},
    {"county": "Napa", "state": "California"},
    {"county": "San Mateo", "state": "California"},
    {"county": "Santa Clara", "state": "California"},
    {"county": "Solano", "state": "California"},
    {"county": "Sonoma", "state": "California"}, ]

places_filters = {
    "network_type": "drive",
    "simplify": False,
    "retain_all": True,
    "truncate_by_edge": False,
    "which_result": None,
    "custom_filter": [
        cf3, cf1, cf1, cf1, cf1, cf1, cf1, cf1, cf1
    ]}

##############################  POINT  ##############################

# Input points (list of tuples, each containing (latitude, longitude))
points = [(37.556036, -122.268709)]

# San Francisco and Oakland
points_filters = {
    "dist": [1000],  # Retain only those nodes within this many meters of the center of the graph
    "dist_type": 'bbox',
    "network_type": ["drive", "bike"],
    "simplify": [False, False],
    "retain_all": True,
    "truncate_by_edge": [False, True],
    "custom_filter": cf1
}

# Input polygons (using geocode to get polygon boundaries)
# (shapely.geometry.Polygon or shapely.geometry.MultiPolygon) – the shape to get network data within.
# coordinates should be in unprojected latitude-longitude degrees (EPSG:4326).

##############################  POLYGON  ##############################


# polygons = [ox.geocode_to_gdf("Downtown San Francisco"), ox.geocode_to_gdf("Oakland")]
polygons = []
polygons_filters = {
    "network_type": ["drive", "bike"],
    "simplify": [False, False],
    "retain_all": True,
    "truncate_by_edge": [True, True],
    "custom_filter": '["building"~"yes"]'
}

# Input XML files (paths to files that contain OSM data in XML format)
xmls = ["/path/to/sf.osm", "/path/to/berkeley.osm"]
xmls_filters = {
    "bidirectional": False,  ####
    "simplify": [False, False],
    "retain_all": True,
    "encoding": "utf-8",  ####
    "custom_filter": '["highway"~"residential"]'
}


# FUNCTIONS

# Helper function to get the appropriate value from the filter
def get_filter_value(filter_param, index, total_count):
    if isinstance(filter_param, list):
        # If the parameter is a list, return the value for the current index
        return filter_param[index % len(filter_param)]
    else:
        # If the parameter is a single value, return the same value for all
        return filter_param


# Helper function to apply filters dynamically
def apply_filters(filters, index, total_count):
    return {key: get_filter_value(value, index, total_count) for key, value in filters.items()}


# Generic function to generate graphs based on a method and a list of inputs
def generate_graphs(inputs, filters, graph_function):
    graphs = []
    for i, input_data in enumerate(inputs):
        # Dynamically apply filters based on index
        print(input_data)
        dynamic_filters = apply_filters(filters, i, len(inputs))
        print(dynamic_filters)
        graph = graph_function(input_data, **dynamic_filters)
        #         plot(graph, f'{input_data}_{str(simpl_intersections)}_original_graph')
        graphs.append(graph)
    return nx.compose_all(graphs) if graphs else None


# Specific functions using the generate_graphs utility

def get_graph_from_address(addresses, filters):
    return generate_graphs(addresses, filters, ox.graph_from_address)


def get_graph_from_bbox(bboxes, filters):
    return generate_graphs(bboxes, filters, lambda bbox, **kwargs: ox.graph_from_bbox(*bbox, **kwargs))


def get_graph_from_place(places, filters):
    return generate_graphs(places, filters, ox.graph_from_place)


def get_graph_from_point(points, filters):
    return generate_graphs(points, filters, ox.graph_from_point)


def get_graph_from_polygon(polygons, filters):
    return generate_graphs(polygons, filters,
                           lambda polygon, **kwargs: ox.graph_from_polygon(polygon.geometry[0], **kwargs))


def get_graph_from_xml(xmls, filters):
    return generate_graphs(xmls, filters, ox.graph_from_xml)


# Function to generate and combine graphs
def combine_graphs():
    combined_graphs = []

    if is_addresses:
        address_graph = get_graph_from_address(addresses, addresses_filters)
        if address_graph is not None:
            combined_graphs.append(address_graph)

    if is_bboxes:
        bbox_graph = get_graph_from_bbox(bboxes, bboxes_filters)
        if bbox_graph is not None:
            combined_graphs.append(bbox_graph)

    if is_places:
        place_graph = get_graph_from_place(places, places_filters)
        if place_graph is not None:
            combined_graphs.append(place_graph)

    if is_points:
        point_graph = get_graph_from_point(points, points_filters)
        if point_graph is not None:
            combined_graphs.append(point_graph)

    if is_polygons:
        polygon_graph = get_graph_from_polygon(polygons, polygons_filters)
        if polygon_graph is not None:
            combined_graphs.append(polygon_graph)

    if is_xmls:
        xml_graph = get_graph_from_xml(xmls, xmls_filters)
        if xml_graph is not None:
            combined_graphs.append(xml_graph)

    # Return the combined graph if there are any valid graphs, else return None
    return nx.compose_all(combined_graphs) if combined_graphs else None


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


# MAIN

##############################  PLACE  ##############################


# Input places (list of place names)
studyArea = 'SanFrancisco'

G = combine_graphs()

G = ox.project_graph(G, to_crs="epsg:3857")

plot(G, f'{studyArea}_{str(simpl_intersections)}_original_graph')

G_final = G.copy()

############################## Add Attributes

G_final = ox.add_edge_speeds(G_final)
# G_final = ox.add_edge_lanes(G_final)
# G_final = ox.add_edge_capacities(G_final)

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


# Helper function to plot graph by attribute with legend
def plot_graph_by_attribute(G, attribute, title, figsize=(12, 12)):
    # Extract values of the specified attribute from the edges
    attribute_values = [G.edges[edge].get(attribute, 'unknown') for edge in G.edges]

    # Determine if the attribute is categorical or numerical
    if isinstance(attribute_values[0], str) or isinstance(attribute_values[0], bool):  # Categorical
        unique_values = list(set(attribute_values))
        colors = plt.cm.get_cmap('tab20', len(unique_values))(range(len(unique_values)))
        color_map = dict(zip(unique_values, colors))
        edge_colors = [color_map[val] for val in attribute_values]
    else:  # Numerical
        norm = mcolors.Normalize(vmin=min(attribute_values), vmax=max(attribute_values))
        color_map = plt.cm.ScalarMappable(norm=norm, cmap='plasma')
        edge_colors = [color_map.to_rgba(val) for val in attribute_values]

    # Plot the graph with edges colored by the specified attribute
    fig, ax = plt.subplots(figsize=figsize)
    ox.plot_graph(
        G,
        ax=ax,
        bgcolor="#222222",
        node_color="#00FFAA",
        node_size=0.2,
        node_alpha=0.9,
        node_edgecolor="#333333",
        edge_color=edge_colors,
        edge_linewidth=0.7,
        edge_alpha=1,
        show=False,
        close=False
    )
    ctx.add_basemap(ax, source=ctx.providers.CartoDB.Positron)

    # Set title
    ax.set_title(title, color="white")

    # Add legend for categorical attributes
    if isinstance(attribute_values[0], str) or isinstance(attribute_values[0], bool):
        handles = [plt.Line2D([0], [0], color=color_map[val], lw=4) for val in unique_values]
        ax.legend(handles, unique_values, title=attribute, loc="lower right", frameon=False, fontsize=10)
    elif isinstance(attribute_values[0], (int, float)):
        # Add a color bar for numerical attributes
        cbar = plt.colorbar(color_map, ax=ax)
        cbar.set_label(attribute)

    fig.savefig(f'{studyArea}_{attribute}.png', dpi=600, bbox_inches='tight')
    plt.show()


# Plot by `highway` type with discrete legend
plot_graph_by_attribute(G_final, attribute='highway', title="Network Colored by Highway Type")

# Plot by `lanes` with discrete legend if categorical, or colorbar if numerical
plot_graph_by_attribute(G_final, attribute='lanes', title="Network Colored by Number of Lanes")

# Define the file path where you want to save the pickle file
save_path = f'{studyArea}_{simpl_intersections}_graph.pkl'

# Save the graph using pickle
with open(save_path, 'wb') as f:
    pickle.dump(G_final, f)
print(f"Graph successfully saved to '{save_path}'.")

# Save Network for the Simulation

# Save GPKG

ox.save_graph_geopackage(G_final, filepath="SFBayArea.gpkg")


# Save OSM

def save_graph_to_osm(G, filename="output.osm"):
    # Bounding box
    xs = [d['x'] for _, d in G.nodes(data=True) if 'x' in d]
    ys = [d['y'] for _, d in G.nodes(data=True) if 'y' in d]
    minlon, maxlon = min(xs), max(xs)
    minlat, maxlat = min(ys), max(ys)

    root = ET.Element("osm", version="0.6", generator="OSMnx2OSM")
    ET.SubElement(root, "bounds",
                  minlat=str(minlat), minlon=str(minlon),
                  maxlat=str(maxlat), maxlon=str(maxlon))

    node_map = {}
    node_id = 1

    # Write nodes + attributes as tags
    for n, d in G.nodes(data=True):
        lat, lon = d.get('y'), d.get('x')
        if lat is None or lon is None: continue
        node = ET.SubElement(root, "node",
                             id=str(node_id), lat=str(lat), lon=str(lon),
                             version="1", changeset="1", user="osmnx", uid="1",
                             timestamp="2020-01-01T00:00:00Z"
                             )
        node_map[n] = node_id
        for k, v in d.items():
            if k not in ("x", "y") and v is not None:
                ET.SubElement(node, "tag", k=str(k), v=str(v))
        node_id += 1

    # Write ways (edges) + attributes as tags
    way_id = -1
    for u, v, edata in G.edges(data=True):
        if u not in node_map or v not in node_map:
            continue
        way = ET.SubElement(root, "way",
                            id=str(way_id), version="1", changeset="1",
                            user="osmnx", uid="1", timestamp="2020-01-01T00:00:00Z")
        ET.SubElement(way, "nd", ref=str(node_map[u]))
        ET.SubElement(way, "nd", ref=str(node_map[v]))
        # At least one standard OSM tag
        ET.SubElement(way, "tag", k="highway", v="road")
        # Dump all other attributes
        for k, v_ in edata.items():
            if v_ is not None:
                ET.SubElement(way, "tag", k=str(k), v=str(v_))
        way_id -= 1

    ET.ElementTree(root).write(filename, encoding="utf-8", xml_declaration=True)
