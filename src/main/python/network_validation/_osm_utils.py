import hashlib
import math
import os
import pickle
import sys
from collections import Counter, defaultdict
from statistics import mean
from statistics import median

import geopandas as gpd
import networkx as nx
import osmium
import osmnx as ox
import pandas as pd
import pyproj
import shapely.geometry
from networkx.algorithms import strongly_connected_components
from networkx.algorithms import weakly_connected_components
from osmnx import settings
from shapely.ops import unary_union

from _data_collection_utils import collect_census_data
from _data_collection_utils import collect_geographic_boundaries
from _data_collection_utils import filter_boundaries_by_density

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)


# =========================================================================
# CUSTOM AGGREGATION FUNCTIONS FOR GRAPH SIMPLIFICATION
# =========================================================================

def most_restrictive_bool_str(values):
    """Returns "no" if any value is "no", otherwise returns "yes" (or True/False logic)."""
    # Filters out None/NaN/empty strings, converts booleans/numbers to strings for safety
    valid_values = [str(v).strip().lower() for v in values if pd.notna(v) and str(v).strip()]
    if not valid_values:
        return None

    # If any value is a clear restriction, enforce restriction.
    return "no" if "no" in valid_values or "false" in valid_values or "0" in valid_values else "yes"


def min_numeric_or_string(values):
    """
    Finds the minimum numeric value from a list, ignoring non-numeric strings.
    If no numeric value is found, returns the first non-NaN string.
    Used for 'maxweight' where the minimum constraint is the most restrictive.
    """
    numeric_values = []
    first_string = None

    for v in values:
        if pd.isna(v):
            continue

        try:
            # Try to convert to float (handles strings like "1000")
            numeric_v = float(v)
            if not math.isnan(numeric_v):
                numeric_values.append(numeric_v)
        except (ValueError, TypeError):
            # If conversion fails, it's a string (e.g., "30 tons" or "5000 kg").
            if first_string is None and isinstance(v, str):
                first_string = v
            continue

    if numeric_values:
        return min(numeric_values)

    # Fallback: if no valid numeric value, return the first encountered string (e.g., "5000 kg")
    return first_string if first_string is not None else None


def first_valid_value(values):
    """
    Returns the first non-NaN, non-empty value encountered.
    Used for dimensions like 'maxheight', 'maxwidth' where a single value is sufficient
    and aggregation is complex.
    """
    for v in values:
        if pd.notna(v) and str(v).strip():
            return v
    return None


# =========================================================================
# helper functions
# =========================================================================

def process_ferry_edges(ferry_graph) -> nx.MultiDiGraph:
    """Process ferry edges to make them compatible with car network"""
    if ferry_graph.number_of_edges() == 0:
        print("No ferry edges found in the graph.")
        return nx.MultiDiGraph()

    # Extract nodes and edges
    ferry_nodes, ferry_edges = ox.graph_to_gdfs(ferry_graph)
    print(f"Total ferry edges: {len(ferry_edges)}")

    # Print available columns to debug
    print(f"Available columns: {ferry_edges.columns.tolist()}")

    # Create default masks - assume access is allowed unless explicitly denied
    # This is more lenient and works better with OSM data which often lacks explicit tags
    car_mask = pd.Series(True, index=ferry_edges.index)

    # Check for explicit denials first
    if 'motorcar' in ferry_edges.columns:
        car_mask &= ~(ferry_edges['motorcar'] == 'no')
        print(f"After motorcar check: {car_mask.sum()} car-accessible edges")

    if 'motor_vehicle' in ferry_edges.columns:
        motor_vehicle_denied = ferry_edges['motor_vehicle'] == 'no'
        car_mask &= ~motor_vehicle_denied
        print(f"After motor_vehicle check: {car_mask.sum()} car-accessible edges")

    # Select ferry edges that allow passenger cars
    selected_edges = ferry_edges[car_mask].copy()

    if selected_edges.empty:
        print("No ferry routes found that allow passenger cars")
        return nx.MultiDiGraph()

    print(f"Found {len(selected_edges)} suitable ferry edges")

    # Set ferry attributes
    selected_edges['reversed'] = False
    selected_edges['maxspeed'] = "10 mph"
    selected_edges['highway'] = "unclassified"
    selected_edges['oneway'] = "no"
    selected_edges['lanes'] = "2"
    selected_edges["hgv"] = False  # Mark as not accessible to heavy-duty
    selected_edges["mdv"] = True  # Mark as accessible to medium-duty

    # Keep only nodes that are used by the filtered edges
    used_nodes = set(selected_edges.index.get_level_values(0)).union(
        set(selected_edges.index.get_level_values(1))
    )
    selected_nodes = ferry_nodes.loc[list(used_nodes)]

    # Reconstruct graph and project
    g_ferry_reconstructed = ox.graph_from_gdfs(selected_nodes, selected_edges)

    return g_ferry_reconstructed


def convert_weight(value: float, from_unit: str, to_unit: str) -> float:
    """Convert weight between different units."""
    # Conversion factors
    conversions = {
        "lbs_to_kg": 0.453592,
        "kg_to_lbs": 2.20462,
        "tons_to_kg": 1000,
        "kg_to_tons": 0.001
    }

    if from_unit == to_unit:
        return value

    conversion_key = f"{from_unit}_to_{to_unit}"
    if conversion_key in conversions:
        return value * conversions[conversion_key]

    # Handle two-step conversions if needed
    if from_unit == "lbs" and to_unit == "tons":
        return value * conversions["lbs_to_kg"] * conversions["kg_to_tons"]
    if from_unit == "tons" and to_unit == "lbs":
        return value * conversions["tons_to_kg"] * conversions["kg_to_lbs"]

    raise ValueError(f"Unsupported conversion from {from_unit} to {to_unit}")


def standardize_weight(weight_str: str, target_unit: str) -> float:
    """Convert weight string to numeric value in target unit."""
    if pd.isna(weight_str):
        return None

    # Handle numeric-only strings (assume they're in target unit)
    if str(weight_str).replace('.', '').isdigit():
        return float(weight_str)

    # Extract number and unit from string
    import re

    # Enhanced pattern to match more formats:
    # - Numbers with optional decimal point
    # - Various unit formats: tons, ton, t, kg, lbs, lb, st (stone)
    match = re.match(r'(\d+\.?\d*)\s*(tons?|t|kg|lbs?|st|stone)', str(weight_str).lower())
    if not match:
        # Try again with a simpler pattern in case the unit is missing or unusual
        number_match = re.match(r'(\d+\.?\d*)', str(weight_str))
        if number_match:
            # If we can extract just a number, assume it's in the target unit
            return float(number_match.group(1))
        return None

    value, unit = match.groups()
    value = float(value)

    # Standardize unit names
    unit_mapping = {
        't': 'tons',
        'ton': 'tons',
        'lb': 'lbs',
        'kg': 'kg',
        'st': 'stone',
        'stone': 'stone'
    }
    unit = unit_mapping.get(unit, unit)

    # Convert to standard unit first (kg), then to target unit
    # Conversion factors to kg
    to_kg = {
        'lbs': 0.453592,
        'kg': 1.0,
        'tons': 1000.0,
        'stone': 6.35029  # 1 stone = 14 lbs = 6.35029 kg
    }

    # Convert to kg first
    weight_in_kg = value * to_kg.get(unit, 1.0)

    # Then convert from kg to target unit
    from_kg = {
        'lbs': 2.20462,
        'kg': 1.0,
        'tons': 0.001
    }

    # If target unit isn't recognized, default to kg
    conversion_factor = from_kg.get(target_unit, 1.0)

    return weight_in_kg * conversion_factor


def standardize_oneway(value):
    """
    Standardize oneway tag to "yes", "reverse", or "no" strings to match MATSim expectations.
    - "yes" for forward direction oneway
    - "reverse" for backward direction oneway
    - "no" for bidirectional
    """
    # Return None if the value is None or empty
    if value is None or value == '':
        return "no"

    # Values that explicitly mean "yes" (forward oneway)
    valid_yes = {'yes', 'true', '1', True, 1}

    # Values that explicitly mean reverse oneway
    valid_reverse = {'-1', 'reverse'}

    # Values that explicitly mean "no"
    valid_no = {'no', 'false', '0', False, 0}

    # Handle strings
    if isinstance(value, str):
        value = value.lower().strip()
        # Handle semicolon-separated values
        if ';' in value:
            parts = [part.strip().lower() for part in value.split(';')]
            if all(part in valid_yes for part in parts):
                return "yes"
            elif all(part in valid_reverse for part in parts):
                return "reverse"
            else:
                return "no"

        # Handle single string
        if value in valid_yes:
            return "yes"
        elif value in valid_reverse:
            return "reverse"
        elif value in valid_no:
            return "no"
        else:
            # If we can't interpret it, MATSim logs a warning and ignores it
            return "no"

    # Handle boolean and numeric
    if isinstance(value, (bool, int)):
        if value in valid_yes:
            return "yes"
        else:
            return "no"

    # Handle lists (if that's a use case)
    if isinstance(value, list):
        if all(str(v).lower().strip() in valid_yes for v in value if v):
            return "yes"
        elif all(str(v).lower().strip() in valid_reverse for v in value if v):
            return "reverse"
        else:
            return "no"

    # Default case
    return "no"


def standardize_motor_vehicle(value):
    """
    Standardize motor_vehicle tag to "yes" or "no" strings, focusing on a defined set of restrictive values.

    Parameters:
    -----------
    value : any
        The motor_vehicle tag value

    Returns:
    --------
    str
        "no" if motor vehicles are restricted (no, false, 0, private)
        "yes" otherwise
    """
    # Define restrictive values
    restrictive_values = {"no", "false", "0"}

    # If value is None or empty, assume motor vehicles are allowed
    if value is None or pd.isna(value) or (isinstance(value, str) and not value.strip()):
        return "yes"

    # Convert to string and lowercase for consistent processing
    if not isinstance(value, str):
        value = str(value)

    value = value.lower().strip()

    import re
    # Handle special cases with multiple values (separated by semicolons or vertical bars)
    if ';' in value or '|' in value:
        # Split by either semicolon or vertical bar
        parts = re.split(r'[;|]+', value)
        parts = [p.strip() for p in parts if p.strip()]

        # If any part is in the restrictive values, the overall value is "no"
        if any(p in restrictive_values for p in parts):
            return "no"
        else:
            return "yes"

    # Check if the value is in the restrictive set
    if value in restrictive_values:
        return "no"

    # All other values indicate some form of access
    return "yes"


def standardize_maxspeed(value, default_kph=None):
    """
    Standardize maxspeed values and return them in the format "25 mph".

    Parameters:
    -----------
    value : any
        The maxspeed tag value
    default_kph : int, optional
        Default speed in kph to use if the value can't be parsed

    Returns:
    --------
    str or None
        Speed in format "XX mph", or None if the value can't be parsed and no default is provided
    """
    if value is None or pd.isna(value) or (isinstance(value, str) and not value.strip()):
        if default_kph is not None:
            return f"{round(default_kph / 1.60934)} mph"  # Convert kph to mph
        return None

    # Convert to string for processing
    if not isinstance(value, str):
        value = str(value)

    value = value.lower().strip()

    # Handle special cases
    if value == "signals" or value == "none" or value == "variable":
        if default_kph is not None:
            return f"{round(default_kph / 1.60934)} mph"  # Convert kph to mph
        return None

    import re
    # Try to extract numeric value and unit
    match = re.match(r'^(\d+(?:\.\d+)?)\s*(mph|kmh|km/h|kph)?$', value)
    if match:
        speed_val = float(match.group(1))
        unit = match.group(2) if match.group(2) else "kph"  # Default to kph if no unit

        # Convert to mph if necessary
        if unit in ["kmh", "km/h", "kph"]:
            speed_mph = round(speed_val / 1.60934)  # Convert kph to mph
        else:
            # Already in mph
            speed_mph = round(speed_val)

        return f"{speed_mph} mph"

    # If we can't parse the value and have a default
    if default_kph is not None:
        return f"{round(default_kph / 1.60934)} mph"  # Convert kph to mph

    # If we can't parse the value and don't have a default
    return None


def standardize_access(value):
    """
    Standardize access tag to "yes" or "no" strings, focusing on a defined set of restrictive values.

    Parameters:
    -----------
    value : any
        The access tag value

    Returns:
    --------
    str
        "no" if access is restricted (no, private, forestry, permit, etc.)
        "yes" otherwise
    """
    # Define restrictive values - values that indicate restricted access
    restrictive_values = {"no", "false", "0"}

    # If value is None or empty, assume access is allowed
    if value is None or pd.isna(value) or (isinstance(value, str) and not value.strip()):
        return "yes"

    # Convert to string and lowercase for consistent processing
    if not isinstance(value, str):
        value = str(value)

    value = value.lower().strip()

    import re
    # Handle special cases with multiple values (separated by semicolons or vertical bars)
    if ';' in value or '|' in value:
        # Split by either semicolon or vertical bar
        parts = re.split(r'[;|]+', value)
        parts = [p.strip() for p in parts if p.strip()]

        # If any part is in the restrictive values, the overall value is "no"
        if any(p in restrictive_values for p in parts):
            return "no"
        else:
            return "yes"

    # Check if the value is in the restrictive set
    if value in restrictive_values:
        return "no"

    # All other values (yes, permissive, etc.) indicate general access
    return "yes"


def standardize_hgv(value):
    """
    Standardize HGV access values to boolean (True/False)
    Returns True if HGVs are allowed, False if they are not
    """
    if not value:
        return True  # Default to allowed if no value

    # Values that indicate HGV prohibition
    restrictive_values = {"no", "false", "0"}

    # Handle boolean inputs
    if isinstance(value, bool):
        return value

    # Handle semicolon-separated string values
    if isinstance(value, str) and ';' in value:
        # If any part is "no", the whole is restricted
        for part in value.split(';'):
            if part.strip().lower() in restrictive_values:
                return False
        return True

    # Handle list case
    if isinstance(value, list):
        if not value:
            return True
        # If any value is "no", the whole is restricted
        for v in value:
            if str(v).strip().lower() in restrictive_values:
                return False
        return True

    # Handle single string value
    if isinstance(value, str):
        return str(value).strip().lower() not in restrictive_values

    # For any other case, convert to string and check
    return str(value).strip().lower() not in restrictive_values


def process_tags(_g: nx.MultiDiGraph, config: dict) -> nx.MultiDiGraph:
    """Process vehicle classifications based on FHWA weight classes."""
    print("Processing vehicle classifications...")

    # Get weight limits and unit from config
    weight_config = config["weight_limits"]
    target_unit = weight_config["unit"]
    mdv_max = weight_config["mdv_max"]
    hdv_max = weight_config["hdv_max"]

    # Get graph data while preserving MultiIndex
    nodes, edges = ox.graph_to_gdfs(_g)

    # Standardize tags
    edges['oneway'] = edges['oneway'].apply(standardize_oneway)
    edges['motor_vehicle'] = edges['motor_vehicle'].apply(standardize_motor_vehicle)
    edges['maxspeed'] = edges['maxspeed'].apply(standardize_maxspeed)
    edges['access'] = edges['access'].apply(standardize_access)
    # Initialize hgv and mdv as True by default if they don't exist
    edges["mdv"] = True
    if "hgv" not in edges.columns:
        edges["hgv"] = True
    edges["hgv"] = edges["hgv"].apply(standardize_hgv)

    # Copy HGV weight restrictions if present
    if "maxweight:hgv" in edges.columns:
        hgv_mask = ~edges["maxweight:hgv"].isna()
        if hgv_mask.any():
            edges.loc[hgv_mask, "maxweight"] = edges.loc[hgv_mask, "maxweight:hgv"].copy()

    if "maxweight" in edges.columns:
        print("Processing weight restrictions...")
        # Convert weights to standard unit specified in config
        edges["maxweight"] = edges["maxweight"].apply(lambda x: standardize_weight(x, target_unit))

        # Update hgv and mdv based on weight restrictions
        # Medium-duty vehicles are restricted when weight is below MDV limit
        mdv_restricted_mask = edges["maxweight"].notna() & (edges["maxweight"] <= mdv_max)
        edges.loc[mdv_restricted_mask, "mdv"] = False

        # Heavy-duty vehicles are restricted when weight is below HDV limit
        # Create a mask for MDVs being restricted
        mdv_is_restricted = edges["mdv"] == False
        # Combine masks properly
        hdv_restricted_mask = mdv_is_restricted | (edges["maxweight"].notna() & (edges["maxweight"] <= hdv_max))
        edges.loc[hdv_restricted_mask, "hgv"] = False

    # Process other restrictions like maxlength
    if "maxlength" in edges.columns:
        # If maxlength is set, assume heavy vehicles are restricted
        length_restricted_mask = ~edges["maxlength"].isna()
        edges.loc[length_restricted_mask, "hgv"] = False

    if 'oneway' in edges.columns:
        edges['oneway'] = edges['oneway'].astype(str).str.lower()
        # Map non-standard values to standard BEAM-compatible values
        edges['oneway'] = edges['oneway'].replace({
            'reverse': '-1',
            'true': 'yes',
            '-1.0': '-1',
            '1.0': 'yes'
        })

    # Ensure hgv, mdv and oneway are strictly boolean
    edges["hgv"] = edges["hgv"].astype(bool)
    edges["mdv"] = edges["mdv"].astype(bool)

    # Convert back to MultiDiGraph
    g_updated = ox.graph_from_gdfs(nodes, edges)

    return g_updated


def create_unique_edge_id(u, v, osmid, k=None):
    """
    Create a unique edge ID by combining start node, end node, and osmid.

    Parameters:
    -----------
    u : node ID of the edge's source
    v : node ID of the edge's target
    osmid : original OSM way ID
    k : optional key for MultiDiGraphs (default: None)

    Returns:
    --------
    str : A unique edge identifier
    """
    # Handle the case where osmid might be a list
    if isinstance(osmid, list):
        osmid_str = '_'.join(map(str, osmid))
    else:
        osmid_str = str(osmid)

    # Include the key if provided (for MultiDiGraphs)
    if k is not None:
        unique_id = f"{u}_{v}_{k}_{osmid_str}"
    else:
        unique_id = f"{u}_{v}_{osmid_str}"

    # Optionally hash it if you want a shorter fixed-length ID
    hash_object = hashlib.md5(unique_id.encode())
    return hash_object.hexdigest()[:12]  # 12 characters should be sufficient


def find_long_tags_in_gdf(gdf, element_type="elements"):
    """
    Find columns and combinations of attributes that exceed 250 characters in a GeoDataFrame.

    Parameters:
    -----------
    gdf : GeoDataFrame
        The input GeoDataFrame (can be either nodes or edges)
    element_type : str, optional
        The type of elements being analyzed ("nodes" or "edges") for output messages

    Returns:
    --------
    tuple
        (long_tags, long_comb_tags) where:
        - long_tags: dict of individual columns with values >= 250 characters
        - long_comb_tags: dict of rows with combined attribute length >= 250 characters
    """
    print(f"\nAnalyzing {element_type}...")

    # Find individual columns with values longer than 250 characters
    long_tags = {}
    for column in gdf.columns:
        # Convert all values to strings and check their lengths
        max_length = gdf[column].astype(str).str.len().max()
        if max_length >= 250:
            long_tags[column] = max_length

    # Print results for individual columns
    if long_tags:
        print(f"\nIndividual {element_type} columns with values >= 250 characters:")
        for column, length in long_tags.items():
            print(f"Column '{column}': max length = {length} characters")
            # Print an example of a long value
            long_value_idx = gdf[column].astype(str).str.len().idxmax()
            print(f"Example long value: {gdf[column].iloc[long_value_idx]}\n")
    else:
        print(f"No individual {element_type} columns found with values >= 250 characters")

    # Find combinations of attributes that exceed 250 characters
    print(f"\nChecking {element_type} attribute combinations...")
    # Get all rows where any combination of attributes might be long
    long_comb_tags = {}
    for idx, row in gdf.iterrows():
        comb_length = 0
        contributing_cols = []

        for col in gdf.columns:
            value = str(row[col])
            if len(value) > 0 and value.lower() != 'nan':  # Skip empty or NaN values
                value_length = len(value)
                comb_length += value_length
                if value_length > 0:  # Only add if the value has length
                    contributing_cols.append({
                        'column': col,
                        'length': value_length,
                        'value': value
                    })

        if comb_length >= 250:
            long_comb_tags[idx] = {
                'total_length': comb_length,
                'contributing_columns': contributing_cols
            }

    # Print results for combinations
    if long_comb_tags:
        print(f"\n{element_type.capitalize()} rows with combined attribute length >= 250 characters:")
        for idx, info in long_comb_tags.items():
            print(f"\nRow {idx}:")
            print(f"Total combined length: {info['total_length']} characters")
            print("Contributing columns:")
            for col_info in info['contributing_columns']:
                print(f"- {col_info['column']}: length={col_info['length']} chars")
                if col_info['length'] > 50:  # Show value only if it's significantly long
                    print(f"  Value: {col_info['value'][:50]}...")  # Show first 50 chars
    else:
        print(f"No combinations of {element_type} attributes found exceeding 250 characters")

    return long_tags, long_comb_tags


def meters_to_degrees(lon, lat, utm_epsg, buffer_meters):
    """
    Calculate the equivalent buffer distance in degrees for a given buffer in meters,
    using a specified UTM projection for better precision.

    Parameters:
    -----------
    lon : float
        Longitude coordinate (x) in WGS84
    lat : float
        Latitude coordinate (y) in WGS84
    utm_epsg : int
        The EPSG code for the UTM coordinate reference system (e.g., 26910 for UTM Zone 10N)
    buffer_meters : float
        Buffer distance in meters

    Returns:
    --------
    float
        Equivalent buffer distance in degrees
    """
    # Create UTM CRS from EPSG code
    utm_crs = f"EPSG:{utm_epsg}"

    # Create transformers
    wgs84_to_utm = pyproj.Transformer.from_crs("EPSG:4326", utm_crs, always_xy=True)
    utm_to_wgs84 = pyproj.Transformer.from_crs(utm_crs, "EPSG:4326", always_xy=True)

    # Convert coordinates to UTM
    x_utm, y_utm = wgs84_to_utm.transform(lon, lat)

    # Calculate points at buffer distance in cardinal directions
    east_utm = (x_utm + buffer_meters, y_utm)
    north_utm = (x_utm, y_utm + buffer_meters)

    # Convert buffered points back to WGS84
    east_lon, east_lat = utm_to_wgs84.transform(*east_utm)
    north_lon, north_lat = utm_to_wgs84.transform(*north_utm)

    # Calculate degree differences
    lon_diff = abs(east_lon - lon)  # East-West difference (longitude)
    lat_diff = abs(north_lat - lat)  # North-South difference (latitude)

    # Return the average as an approximation
    # You could also return both separately if you need different buffers for lat/lon
    return (lon_diff + lat_diff) / 2


def to_convex_hull(input_data, utm_epsg, buffer_in_meters):
    """
    Create a buffered convex hull from input data.

    Parameters:
    -----------
    input_data : GeoDataFrame, GeoSeries, or Shapely geometry
        The input geographic data
    utm_epsg : int
        EPSG code for the UTM projection to use for accurate distance calculations
    buffer_in_meters : float
        Buffer distance in meters

    Returns:
    --------
    Shapely geometry
        The buffered convex hull
    """
    # Handle different input types
    if isinstance(input_data, gpd.GeoDataFrame):
        # GeoDataFrame: get the convex hull of all geometries
        convex_hull = input_data.geometry.unary_union.convex_hull
    elif isinstance(input_data, gpd.GeoSeries):
        # GeoSeries: get the convex hull of all geometries
        convex_hull = input_data.unary_union.convex_hull
    elif hasattr(input_data, 'geom_type'):
        # Shapely geometry: get its convex hull
        convex_hull = input_data.convex_hull
    else:
        raise TypeError("Input must be a GeoDataFrame, GeoSeries, or Shapely geometry")

    # Get centroid
    lon = convex_hull.centroid.x
    lat = convex_hull.centroid.y

    # Convert buffer distance
    buffer_in_degrees = meters_to_degrees(lon, lat, utm_epsg, buffer_in_meters)

    # Buffer in degrees
    buffered_convex_hull = convex_hull.buffer(buffer_in_degrees)

    return buffered_convex_hull


def adjust_and_add_graph(graphs, current_graph):
    # Get nodes and edges of current graph
    current_nodes, current_edges = ox.graph_to_gdfs(current_graph)

    # Collect all unique columns from existing graphs
    existing_columns = set()
    for existing_graph in graphs:
        _, existing_edges = ox.graph_to_gdfs(existing_graph)
        existing_columns.update(existing_edges.columns)

    # Add missing columns to current graph's edges
    for col in existing_columns:
        if col not in current_edges.columns:
            current_edges[col] = ""

    # Also ensure existing graphs have columns from current graph
    current_columns = set(current_edges.columns)
    for i, existing_graph in enumerate(graphs):
        existing_nodes, existing_edges = ox.graph_to_gdfs(existing_graph)

        columns_added = False
        for col in current_columns:
            if col not in existing_edges.columns:
                existing_edges[col] = ""
                columns_added = True

        # Only rebuild the graph if columns were added
        if columns_added:
            graphs[i] = ox.graph_from_gdfs(existing_nodes, existing_edges)

    # Add the graph to the list if it has edges
    graphs.append(ox.graph_from_gdfs(current_nodes, current_edges))


def median_lanes(values):
    """
    Calculate median after converting string values to numbers.
    Handles:
    - Lists of values
    - Semicolon-separated values
    - Mixed numeric types
    """
    # Initialize empty list for numeric values
    numeric_values = []

    # Handle case where values is already a single value, not an iterable
    if isinstance(values, (int, float)):
        return int(values)
    elif isinstance(values, str):
        values = [values]

    # Process each value in the iterable
    for v in values:
        # Skip None values
        if v is None:
            continue

        # Handle different types
        if isinstance(v, (int, float)):
            numeric_values.append(int(v))
            continue

        if not isinstance(v, str):
            v = str(v)

        # Split by semicolon to handle multiple values
        parts = v.split(';')
        for part in parts:
            part = part.strip()
            try:
                numeric_values.append(int(part))
            except (ValueError, TypeError):
                # Skip non-numeric parts
                continue

    if not numeric_values:
        return None
    return int(median(numeric_values))


def most_restrictive_access(values):
    """
    Returns the most restrictive access value from a list based on a predefined priority order.

    Parameters:
    -----------
    values : list
        List of access values

    Returns:
    --------
    str
        The most restrictive access value, or None if no valid values
    """
    # Define a priority order for access restrictions (from most to least restrictive)
    priority = {
        "no": 1,  # Most restrictive
        "private": 2,
        "permit": 3,
        "destination": 4,
        "delivery": 5,
        "customers": 6,
        "forestry": 7,
        "agricultural": 8,
        "discouraged": 9,
        "permissive": 10,
        "yes": 11  # Least restrictive
    }

    # Default priority for unknown values - place between "discouraged" and "permissive"
    default_priority = 9.5

    if not values:
        return None

    # Process each value and find the most restrictive
    most_restrictive = None
    highest_priority = float('inf')  # Lower number = higher priority

    for value in values:
        if value is None or value == "nan" or pd.isna(value):
            continue

        if isinstance(value, str):
            value = value.strip().lower()
            if not value or value == "nan":
                continue

        # Get priority for this value
        value_priority = priority.get(value, default_priority)

        # Update most restrictive if this has higher priority (lower number)
        if value_priority < highest_priority:
            most_restrictive = value
            highest_priority = value_priority

    return most_restrictive


def bool_all(values):
    """
    Returns False if any value is False, otherwise returns True.
    Expects only boolean values (True or False).

    Parameters:
    -----------
    values : list
        List of boolean values

    Returns:
    --------
    bool
        False if any value is False, True otherwise
    """
    if not values:
        return None

    # If any value is False, return False
    return all(values)


def mean_maxspeed(speed_values):
    """
    Calculate the mean speed from a list of speed values in the format "XX mph".

    Parameters:
    -----------
    speed_values : list
        List of speed values in format "XX mph"

    Returns:
    --------
    str
        Mean speed in format "XX mph", or None if no valid speeds found
    """
    if not speed_values:
        return None

    # Extract numeric values
    speeds_mph = []
    import re

    for value in speed_values:
        if not value or pd.isna(value):
            continue

        # Convert to string if needed
        if not isinstance(value, str):
            value = str(value)

        # Extract the numeric part
        match = re.match(r'^(\d+(?:\.\d+)?)\s*mph$', value.lower().strip())
        if match:
            speeds_mph.append(float(match.group(1)))

    # Calculate mean if we have valid values
    if speeds_mph:
        mean_speed = mean(speeds_mph)
        return f"{round(mean_speed)} mph"

    return None


def yes_no_all(values):
    """
    Returns "no" if any value is "no", otherwise returns "yes".
    Expects string values ("yes" or "no").

    Parameters:
    -----------
    values : list
        List of string values ("yes" or "no")

    Returns:
    --------
    str
        "no" if any value is "no", "yes" otherwise
    """
    if not values:
        return None

    # If any value is "no", return "no"
    return "no" if "no" in values else "yes"


def project_graph(G: nx.MultiDiGraph, to_crs=None, to_latlong=False) -> nx.MultiDiGraph:
    """
    Project a graph from its current CRS to another.

    If `to_latlong` is True, this projects the graph to the coordinate
    reference system defined by `settings.default_crs`. Otherwise it projects
    it to the CRS defined by `to_crs`. If `to_crs` is `None`, it projects it
    to the CRS of an appropriate UTM zone given `geometry`'s bounds.

    Parameters
    ----------
    G
        The graph to be projected.
    to_crs
        If None, project to an appropriate UTM zone. Otherwise project to
        this CRS.
    to_latlong
        If True, project to `settings.default_crs` and ignore `to_crs`.

    Returns
    -------
    G_proj
        The projected graph.
    """
    if to_latlong:
        to_crs = settings.default_crs

    # STEP 1: PROJECT THE NODES
    gdf_nodes = ox.convert.graph_to_gdfs(G, edges=False)

    # project the nodes GeoDataFrame and extract the projected x/y values
    gdf_nodes_proj = ox.projection.project_gdf(gdf_nodes, to_crs=to_crs)
    gdf_nodes_proj["x"] = gdf_nodes_proj["geometry"].x
    gdf_nodes_proj["y"] = gdf_nodes_proj["geometry"].y
    to_crs = gdf_nodes_proj.crs

    # STEP 2: PROJECT THE EDGES
    # Always get edges with geometry, regardless of whether the graph is simplified
    gdf_edges = ox.convert.graph_to_gdfs(G, nodes=False, fill_edge_geometry=True)

    # If edges don't have a CRS but do have geometry, assign the source CRS
    if gdf_edges.crs is None and not gdf_edges.empty and 'geometry' in gdf_edges.columns:
        # If we're unsure about the source CRS, use what we know from the nodes
        source_crs = G.graph.get('crs', gdf_nodes.crs)
        if source_crs is not None:
            gdf_edges.crs = source_crs
            print(f"Setting edge CRS to {source_crs} before projection")

    # Project the edges
    gdf_edges_proj = ox.projection.project_gdf(gdf_edges, to_crs=to_crs)

    # Debug output to verify projection worked
    if not gdf_edges_proj.empty and 'geometry' in gdf_edges_proj.columns:
        sample_geom = gdf_edges_proj.iloc[0]['geometry']
        if sample_geom is not None:
            print(f"Sample edge coordinate after projection: {next(iter(sample_geom.coords))}")

    # STEP 3: REBUILD GRAPH
    # turn projected node/edge gdfs into a graph and update its CRS attribute
    G_proj = ox.convert.graph_from_gdfs(gdf_nodes_proj, gdf_edges_proj, graph_attrs=G.graph)
    G_proj.graph["crs"] = to_crs

    print(f"Projected graph with {len(G)} nodes and {len(G.edges)} edges")

    # Final verification
    nodes_check, edges_check = ox.convert.graph_to_gdfs(G_proj)
    print(f"Verified: Nodes CRS: {nodes_check.crs}, Edges CRS: {edges_check.crs}")

    return G_proj


def validate_graph_topology(G):
    """
    Validate graph topology and fix common issues.

    Fixes:
    - Self-loop edges (u == v) - often from OSMnx bugs
    - Isolated nodes (nodes with no edges)

    Parameters
    ----------
    G : networkx.MultiDiGraph
        Input graph

    Returns
    -------
    G : networkx.MultiDiGraph
        Validated and fixed graph
    stats : dict
        Dictionary of validation statistics
    """
    stats = {
        'original_nodes': G.number_of_nodes(),
        'original_edges': G.number_of_edges(),
        'self_loops_removed': 0,
        'isolated_nodes_removed': 0,
        'parallel_edges': 0
    }

    print("\n=== Validating Network Topology ===")

    # 1. Remove self-loops (u == v)
    self_loops = list(nx.selfloop_edges(G))
    if self_loops:
        print(f"⚠ Found {len(self_loops)} self-loop edges (same node as start and end)")

        # Show examples
        for u, v in self_loops[:3]:  # Unpack only u and v
            edge_data = G[u][v]  # No need for key since we know u == v for self-loops
            print(f"    Example: Node {u} -> {u}, "
                  f"length={edge_data.get('length', 'N/A')}m, "
                  f"highway={edge_data.get('highway', 'N/A')}")
        if len(self_loops) > 3:
            print(f"    ... and {len(self_loops) - 3} more")

        G.remove_edges_from(self_loops)
        stats['self_loops_removed'] = len(self_loops)
        print(f"✓ Removed {len(self_loops)} self-loop edges")
    else:
        print("✓ No self-loop edges found")

    # 2. Remove isolated nodes
    isolated = list(nx.isolates(G))
    if isolated:
        print(f"ℹ Found {len(isolated)} isolated nodes (no edges)")
        G.remove_nodes_from(isolated)
        stats['isolated_nodes_removed'] = len(isolated)
        print(f"✓ Removed {len(isolated)} isolated nodes")
    else:
        print("✓ No isolated nodes found")

    # 3. Count parallel edges (just informational)
    parallel_count = sum(1 for u, v in G.edges() if G.number_of_edges(u, v) > 1)
    stats['parallel_edges'] = parallel_count
    if parallel_count > 0:
        print(f"ℹ Found {parallel_count} parallel edges (this is normal for bidirectional roads)")

    stats['final_nodes'] = G.number_of_nodes()
    stats['final_edges'] = G.number_of_edges()

    print(f"\nValidation Summary:")
    print(f"  Before: {stats['original_nodes']} nodes, {stats['original_edges']} edges")
    print(f"  After:  {stats['final_nodes']} nodes, {stats['final_edges']} edges")
    print(f"  Removed: {stats['self_loops_removed']} self-loops, {stats['isolated_nodes_removed']} isolated nodes")
    print("=" * 50)

    return G, stats


def download_and_prepare_osm_network(_network_config: dict, _area_config: dict, _geo_config: dict,
                                     work_dir) -> nx.MultiDiGraph:
    """Download and prepare OSM network based on study area configuration."""
    print("=== Starting OSM Network Download and Preparation ===")

    # Apply OSMNX settings
    for setting, value in _network_config["osmnx_settings"].items():
        setattr(ox.settings, setting, value)
    print("✓ OSMNX settings applied")

    # List to store the graphs
    graphs = []

    # Set up study area parameters
    study_area = _area_config['name']
    base_name = f"{work_dir}/geo/{study_area}"
    census_year = _area_config["census_year"]
    state_fips_code = _area_config["state_fips"]
    county_fips_codes = _area_config["county_fips"]
    tolerance = _network_config["tolerance"]
    utm_epsg = _geo_config["utm_epsg"]
    should_strongly_connect = _network_config.get("strongly_connected_components", False)

    # --- START CACHING LOGIC ---
    # Define a unique cache path for the raw combined graph (before projection/processing)
    raw_graph_cache_path = os.path.join(work_dir, 'network', f'raw_osm_graph_{study_area}.pkl')

    g_combined = None

    if os.path.exists(raw_graph_cache_path):
        print(f"CACHE HIT: Loading raw combined graph from {raw_graph_cache_path}")
        try:
            with open(raw_graph_cache_path, 'rb') as f:
                g_combined = pickle.load(f)
            print("✓ Raw graph loaded from cache.")
        except Exception as e:
            print(f"WARNING: Failed to load cached graph: {e}. Re-downloading.")
            g_combined = None

    if g_combined is None:
        print(f"CACHE MISS: Downloading and combining network layers to {raw_graph_cache_path}")
        # (Original download and combine logic starts here)

        print(f"Collecting {study_area} boundaries...")

        # Process each layer defined in the configuration
        for layer_name, layer_config in _network_config["graph_layers"].items():
            # Get layer configuration
            geo_level = layer_config["geo_level"]
            min_density = layer_config.get("min_density_per_km2", 0)
            custom_filter = layer_config["custom_filter"]
            buffer_in_meters = layer_config["buffer_zone_in_meters"]

            # Create the region boundary GeoDataFrame
            region_boundary_wgs84 = collect_geographic_boundaries(
                state_fips_code=state_fips_code,
                county_fips_codes=county_fips_codes,
                year=census_year,
                area_name=study_area,
                geo_level=geo_level,
                work_dir=work_dir
            )

            # Process specific layer types
            if layer_name == "main":
                print(f"Processing {layer_name} layer")
                graph_layer = to_convex_hull(region_boundary_wgs84, utm_epsg, buffer_in_meters)
                network_type = "drive"
                simplify = False
                retain_all = True
                truncate_by_edge = True

            elif layer_name == "residential":
                density_info = f" with minimum density: {min_density} pop/km²" if min_density > 0 else ""
                print(f"Processing {layer_name} layer{density_info}")

                # Get population data
                pop_data = collect_census_data(
                    state_fips_code,
                    county_fips_codes,
                    census_year,
                    census_data_file=f"{base_name}_acs_census_{geo_level}_{census_year}.csv",
                    geo_level=geo_level
                )

                filtered_boundaries = filter_boundaries_by_density(
                    region_boundary_wgs84,
                    pop_data,
                    utm_epsg,
                    geo_level,
                    min_density,
                    density_geo_file=f"{base_name}_{geo_level}_{census_year}_{min_density}ppsk_wgs84.geojson",
                )

                graph_layer = shapely.ops.unary_union([
                    to_convex_hull(geom, utm_epsg, buffer_in_meters) for geom in filtered_boundaries.geometry
                ])

                network_type = "drive"
                simplify = False
                retain_all = True
                truncate_by_edge = True

            elif layer_name == "ferry":
                print(f"Processing ferry layer to connect islands...")
                graph_layer = to_convex_hull(region_boundary_wgs84, utm_epsg, buffer_in_meters)
                network_type = "all"
                simplify = True
                retain_all = True
                truncate_by_edge = False

            else:
                raise ValueError(f"Invalid layer name: {layer_name}")

            print("✓ Boundaries collected and unified")

            # Download OSM Network
            print(f"Downloading OSM network with filter: {custom_filter}")
            g = ox.graph_from_polygon(
                graph_layer,
                network_type=network_type,
                simplify=simplify,
                retain_all=retain_all,
                truncate_by_edge=truncate_by_edge,
                custom_filter=custom_filter
            )
            print(f"✓ Downloaded network with {g.number_of_nodes()} nodes and {g.number_of_edges()} edges")

            # Special processing for ferry network
            if layer_name == "ferry":
                g = process_ferry_edges(g)
                if g.number_of_edges() > 0:
                    print(f"✓ Processed {g.number_of_edges()} ferry connections")
                else:
                    print("✗ No suitable ferry connections found")
                    continue  # Skip adding this empty graph

            # Add the graph to the list if it has edges
            adjust_and_add_graph(graphs, g)

        # Combine all graphs
        print("=== Processing Combined Network ===")
        g_combined = nx.compose_all(graphs)
        print(f"✓ Combined network has {g_combined.number_of_nodes()} nodes and {g_combined.number_of_edges()} edges")

        # Save the combined graph to cache before any heavy processing
        try:
            with open(raw_graph_cache_path, 'wb') as f:
                pickle.dump(g_combined, f)
            print(f"✓ Raw combined graph saved to cache: {raw_graph_cache_path}")
        except Exception as e:
            print(f"WARNING: Could not save graph to cache: {e}")
    # --- END CACHING LOGIC ---

    # Project to UTM for processing
    print("Projecting graph to UTM...")
    g_projected = project_graph(g_combined, to_crs=utm_epsg)
    print("✓ Network projected to UTM")

    # Add edge speeds
    print("Adding edge speeds...")
    g_with_speeds = ox.add_edge_speeds(g_projected)
    print("✓ Edge speeds added")

    # Process tags for vehicle types
    print("Processing tags...")
    g_processed_tags = process_tags(g_with_speeds, _network_config)
    print("✓ Edge tags processed")

    # Consolidate intersections
    print("Consolidating intersections...")
    g_consolidated = ox.consolidate_intersections(
        g_processed_tags,
        tolerance=tolerance,
        rebuild_graph=True,
        dead_ends=True,
        reconnect_edges=True
    )
    print("✓ Intersections consolidated")

    # Simplify the graph
    print("Simplifying graph...")
    g_simplified = ox.simplification.simplify_graph(
        g_consolidated,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True,
        edge_attr_aggs={
            "length": sum,  # This now sums the correctly recalculated lengths
            "travel_time": sum,
            "hgv": bool_all,
            "mdv": bool_all,
            "lanes": median_lanes,
            "speed_kph": mean,
            "maxspeed": mean_maxspeed,
            "oneway": yes_no_all,
            "access": yes_no_all,
            "reversed": bool_all,
            "maxweight": min_numeric_or_string,
            'bridge': first_valid_value,
            'tunnel': first_valid_value,
            'foot': yes_no_all,
            'bicycle': yes_no_all,
            'sidewalk': first_valid_value,
            'cycleway': first_valid_value,
            'maxheight': min_numeric_or_string,
            'maxwidth': min_numeric_or_string,
            'motor_vehicle': yes_no_all,
        }
    )
    print("✓ Network simplified")

    # Create unique edge IDs
    nodes, edges = ox.graph_to_gdfs(g_simplified)
    edges['edge_id'] = edges.apply(
        lambda row: create_unique_edge_id(row['u_original'], row['v_original'], row['osmid'], row.get('key', None)),
        axis=1
    )
    g_hashed = ox.graph_from_gdfs(nodes, edges)
    print("✓ Unique edge IDs created")

    # Project back to WGS84
    print("Projecting to WGS84...")
    g_wgs84 = project_graph(g_hashed, to_latlong=True)
    print("✓ Projected to WGS84")

    # Find largest connected component
    print("Finding largest connected component...")
    g_connected = ox.truncate.largest_component(g_wgs84)
    print(f"✓ Final network has {g_connected.number_of_nodes()} nodes and {g_connected.number_of_edges()} edges")

    print("Removing isolated islands from network...")

    # Get the largest strongly connected component (roads where you can actually reach anywhere)
    if should_strongly_connect:
        largest_scc = max(strongly_connected_components(g_connected), key=len)
    else:
        largest_scc = max(weakly_connected_components(g_connected), key=len)

    print(f"Network has {g_connected.number_of_nodes()} nodes initially")
    print(f"Largest connected component has {len(largest_scc)} nodes")

    # Create a subgraph with only the largest connected component
    g_osm = nx.MultiDiGraph(g_connected.subgraph(largest_scc).copy())
    print(f"After island removal: {g_osm.number_of_nodes()} nodes")

    # Validate and fix topology BEFORE any file operations
    g_network, validation_stats = validate_graph_topology(g_osm)

    # Alert if issues were found
    if validation_stats['self_loops_removed'] > 0:
        print(f"\nIMPORTANT: Fixed {validation_stats['self_loops_removed']} corrupt self-loop edges")
        print("   These were OSMnx bugs. Your exported network will now be clean.\n")

    # Check for duplicate edge IDs
    nodes, edges = ox.graph_to_gdfs(g_network)
    has_duplicates, duplicate_info = check_duplicate_edge_ids(edges, 'edge_id')

    if has_duplicates:
        dup_counts, dup_examples = duplicate_info
        print(f"\nFound {sum(dup_counts.values())} duplicate edge IDs")

    print("=== Network Download and Preparation Complete ===")
    return g_network


def scan_network_directories_for_ways(directory):
    import csv
    import subprocess
    import os

    def calculate_ways(osm_file):
        try:
            # Use osmium to get file info with summary
            result = subprocess.run(['osmium', 'fileinfo', '-e', osm_file],
                                    capture_output=True, text=True)
            # Initialize ways_count variable
            ways_count = 0

            # Extract the number of ways from the output
            for line in result.stdout.splitlines():
                if "Number of ways" in line:
                    ways_count = line.split(":")[1].strip()  # Get the number of ways
                    break  # Stop after finding the count

            return ways_count  # Return the number of ways
        except Exception as e:
            print(f"Error processing {osm_file}: {e}")
        return 0

    output_file = os.path.join(directory, 'ways_count.csv')
    scanned_files = set()

    # Check if output file exists and load already processed files
    if os.path.exists(output_file):
        try:
            with open(output_file, 'r', newline='') as f:
                reader = csv.reader(f)
                next(reader, None)  # Skip header, safely
                for row in reader:
                    if len(row) >= 3:  # Ensure the row has enough columns
                        scanned_files.add(row[2])  # Add scanned file path to the set
        except Exception as e:
            print(f"Error reading existing CSV: {e}")
    else:
        # Create the output file and write the header
        with open(output_file, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['name', 'ways', 'path'])
            print(f"Created output file: {output_file}")

    print(f"Scanning directory: {directory}")  # Log current directory being scanned
    for root, dirs, files in os.walk(directory):
        # Skip archive directories
        if 'archive' in root.lower():
            print(f"Ignoring archive directory: {root}")
            continue

        # Look for the first osm.pbf file using next() with a generator expression
        osm_file_path = next((os.path.join(root, file) for file in files if file.endswith('.osm.pbf')), None)

        if osm_file_path is not None:
            if osm_file_path in scanned_files:
                print(f"PBF file already processed: {osm_file_path}")  # Log already processed directory
                continue
            else:
                # Extract network name from the file name or directory name
                network_name = os.path.basename(root)  # Use the directory name as the network name
                number_of_ways = calculate_ways(osm_file_path)

                # Ensure file ends with newline before appending
                """Ensure the file ends with a newline character."""
                if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
                    with open(output_file, 'rb+') as f:
                        f.seek(-1, os.SEEK_END)  # Go to the last byte
                        last_char = f.read(1)
                        if last_char != b'\n':
                            f.seek(0, os.SEEK_END)  # Go to the end of the file
                            f.write(b'\n')  # Add a newline if it doesn't end with one

                # Append result to the output CSV file
                with open(output_file, 'a', newline='') as f:
                    writer = csv.writer(f)
                    writer.writerow([network_name, number_of_ways, osm_file_path])
                    # Write network name, number of ways, and path
                    print(f"Appended to CSV: {network_name}, {number_of_ways}, {osm_file_path}")  # Log appended data
        else:
            print(f"No OSM file found in this directory: {root}.")  # Log message if no file found
            continue  # Skip to the next directory if no file is found


def check_invalid_coordinates(graph):
    """
    Check for invalid coordinates in the graph nodes.

    Parameters:
    -----------
    graph : networkx.MultiDiGraph
        The graph to check

    Returns:
    --------
    tuple
        (has_invalid, invalid_nodes) where:
        - has_invalid: boolean indicating if any invalid coordinates were found
        - invalid_nodes: list of node IDs with invalid coordinates
    """
    nodes, _ = ox.graph_to_gdfs(graph)

    # Check for NaN, infinite, or out-of-range coordinates
    invalid_x = ~nodes['x'].between(-180, 180) | nodes['x'].isna() | nodes['x'].abs().eq(float('inf'))
    invalid_y = ~nodes['y'].between(-90, 90) | nodes['y'].isna() | nodes['y'].abs().eq(float('inf'))

    # Combine invalid x or y
    invalid_nodes = nodes[invalid_x | invalid_y]

    if len(invalid_nodes) > 0:
        print(f"\nWARNING: Found {len(invalid_nodes)} nodes with invalid coordinates:")
        for idx, node in invalid_nodes.iterrows():
            print(f"  Node ID: {idx}, x: {node['x']}, y: {node['y']}")
        return True, invalid_nodes.index.tolist()

    return False, []


class OSMTagHandler(osmium.SimpleHandler):
    def __init__(self):
        osmium.SimpleHandler.__init__(self)
        # Create separate counters for different element types
        self.way_tag_counters = defaultdict(Counter)
        self.node_tag_counters = defaultdict(Counter)
        self.relation_tag_counters = defaultdict(Counter)
        self.other_tags_counters = defaultdict(Counter)
        self.records = []
        self.unique_tags = set()
        self.total_ways = 0
        self.total_nodes = 0
        self.total_relations = 0

    def process_other_tags(self, other_tags_str):
        """Parse hstore-formatted other_tags string into a dictionary"""
        if not other_tags_str:
            return {}

        parsed_tags = {}
        try:
            # Handle the format: "key"=>"value","key2"=>"value2",...
            current = ""
            in_quotes = False
            key = None
            parts = []

            # First split into key=>value parts
            for char in other_tags_str:
                if char == '"' and (not current or current[-1] != '\\'):
                    in_quotes = not in_quotes

                current += char

                if char == ',' and not in_quotes:
                    parts.append(current[:-1])  # Remove the trailing comma
                    current = ""

            if current:  # Add the last part if there is one
                parts.append(current)

            # Now process each part to extract key and value
            for part in parts:
                if "=>" in part:
                    key_val = part.split("=>")
                    if len(key_val) == 2:
                        k = key_val[0].strip().strip('"')
                        v = key_val[1].strip().strip('"')
                        parsed_tags[k] = v

                        # Update counter for this key-value pair
                        self.other_tags_counters[k][v] += 1
        except Exception as e:
            print(f"Error parsing other_tags: {e}, value: {other_tags_str[:100]}")

        return parsed_tags

    def way(self, w):
        """Process a way and its tags"""
        self.total_ways += 1

        # Extract all tags into a dictionary
        tags_dict = {}
        other_tags_dict = {}

        for tag in w.tags:
            tag_key = tag.k
            tag_value = tag.v

            # Add to our unique tags set
            self.unique_tags.add(tag_key)

            # Store the tag and update its counter for ways
            tags_dict[tag_key] = tag_value
            self.way_tag_counters[tag_key][tag_value] += 1

            # Check if this is an other_tags field that needs parsing
            if tag_key == 'other_tags':
                other_tags_dict = self.process_other_tags(tag_value)

        # Store the record with all its tags
        record = {
            'id': w.id,
            **tags_dict,
            'other_tags_parsed': other_tags_dict
        }

        self.records.append(record)

    # Also handle nodes and relations if needed
    def node(self, n):
        self.total_nodes += 1
        for tag in n.tags:
            self.unique_tags.add(tag.k)
            self.node_tag_counters[tag.k][tag.v] += 1

    def relation(self, r):
        self.total_relations += 1
        for tag in r.tags:
            self.unique_tags.add(tag.k)
            self.relation_tag_counters[tag.k][tag.v] += 1


def analyze_osm_pbf(file_path, num_top_values=10):
    """
    Analyze an OSM PBF file and return statistics about all tags,
    separated by element type (way, node, relation)

    Args:
        file_path: Path to the OSM PBF file
        num_top_values: Optional, Number of top values to report for each tag

    Returns:
        Dictionary of statistics and DataFrame of records
    """
    print(f"Analyzing OSM PBF file: {file_path}")
    handler = OSMTagHandler()

    # Process the file
    handler.apply_file(file_path)

    print(f"Processed {handler.total_ways} ways, {handler.total_nodes} nodes, and {handler.total_relations} relations")
    print(f"Found {len(handler.unique_tags)} unique tag keys")

    # Create summary statistics for ways
    way_stats = {}
    for tag_key, counter in handler.way_tag_counters.items():
        total = sum(counter.values())
        way_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(num_top_values)),
            'percent_present':
                round(total / handler.total_ways * 100, 2) if handler.total_ways > 0 else 0
        }

    # Sort way stats by frequency
    way_stats = {k: v for k, v in sorted(
        way_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create summary statistics for nodes
    node_stats = {}
    for tag_key, counter in handler.node_tag_counters.items():
        total = sum(counter.values())
        node_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(num_top_values)),
            'percent_present':
                round(total / handler.total_nodes * 100, 2) if handler.total_nodes > 0 else 0
        }

    # Sort node stats by frequency
    node_stats = {k: v for k, v in sorted(
        node_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create summary statistics for relations
    relation_stats = {}
    for tag_key, counter in handler.relation_tag_counters.items():
        total = sum(counter.values())
        relation_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(num_top_values)),
            'percent_present':
                round(total / handler.total_relations * 100, 2) if handler.total_relations > 0 else 0
        }

    # Sort relation stats by frequency
    relation_stats = {k: v for k, v in sorted(
        relation_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create similar statistics for other_tags fields
    other_tags_stats = {}
    for tag_key, counter in handler.other_tags_counters.items():
        total = sum(counter.values())
        other_tags_stats[tag_key] = {
            'count': total,
            'unique_values': len(counter),
            'top_values': dict(counter.most_common(num_top_values)),
            'percent_present':
                round(total / (handler.total_ways + handler.total_nodes + handler.total_relations) * 100, 2)
        }

    # Sort other_tags stats by frequency
    other_tags_stats = {k: v for k, v in sorted(
        other_tags_stats.items(),
        key=lambda item: item[1]['count'],
        reverse=True
    )}

    # Create a DataFrame from the records
    records_df = pd.DataFrame(handler.records) if handler.records else pd.DataFrame()

    # Return the summary statistics and records
    return {
        'total_ways': handler.total_ways,
        'total_nodes': handler.total_nodes,
        'total_relations': handler.total_relations,
        'unique_tags': list(handler.unique_tags),
        'way_stats': way_stats,
        'node_stats': node_stats,
        'relation_stats': relation_stats,
        'other_tags_stats': other_tags_stats
    }, records_df


def print_tag_stats(stats, category_name="Tags", element_type="Elements", limit=None):
    """Print tag statistics in a formatted way"""
    print(f"\n=== {category_name} Statistics for {element_type} ===")
    print(f"Total unique {category_name.lower()}: {len(stats)}")

    for i, (tag, data) in enumerate(stats.items()):
        if limit and i >= limit:
            print(f"\n... and {len(stats) - limit} more {category_name.lower()}.")
            break

        print(f"\n{i + 1}. {tag}: {data['count']} instances ({data['percent_present']}% of {element_type.lower()})")
        print(f"   Unique values: {data['unique_values']}")
        print("   Top values:")

        # Print top values with their counts
        for val, count in data['top_values'].items():
            # Truncate very long values
            display_val = val[:50] + "..." if len(val) > 50 else val
            print(f"     - {display_val}: {count}")


def check_duplicate_edge_ids(edges_gdf, id_column='edge_id'):
    """
    Check for duplicate edge IDs in an OSMnx edges GeoDataFrame.

    Parameters:
    -----------
    edges_gdf : GeoDataFrame
        The edges GeoDataFrame from ox.graph_to_gdfs()
    id_column : str, default 'edge_id'
        The column name containing the edge IDs to check

    Returns:
    --------
    tuple
        (has_duplicates, duplicate_info) where:
        - has_invalid: boolean indicating if any invalid coordinates were found
        - invalid_nodes: list of node IDs with invalid coordinates
        (has_duplicates, duplicate_info) where:
        - has_duplicates: Boolean indicating if duplicates were found
        - duplicate_info: DataFrame containing the duplicate IDs and their counts
    """
    # Count occurrences of each edge_id
    id_counts = edges_gdf[id_column].value_counts()

    # Filter to only those with count > 1 (duplicates)
    duplicates = id_counts[id_counts > 1]

    if len(duplicates) > 0:
        # Create a DataFrame with duplicate IDs and their counts
        duplicate_info = duplicates.reset_index()
        duplicate_info.columns = ['edge_id', 'count']

        # Get examples of each duplicate
        examples = []
        for dup_id in duplicate_info['edge_id']:
            # Get the first few examples of this duplicate ID
            example_edges = edges_gdf[edges_gdf[id_column] == dup_id].head(3)
            examples.append(example_edges)

        if examples:
            # Concatenate all example edges into one DataFrame
            examples_df = pd.concat(examples)
            duplicate_info = (duplicate_info, examples_df)

        print(f"Found {len(duplicates)} duplicate edge IDs out of {len(edges_gdf)} total edges")
        return True, duplicate_info
    else:
        print(f"No duplicate edge IDs found in {len(edges_gdf)} edges")
        return False, None


def main(file_path=None):
    """Main function to analyze an OSM PBF file"""
    if not file_path:
        print("\nNo file provided. To analyze a file, run: python osm_analyzer.py <file.osm.pbf>")
        return

    # Analyze the PBF file
    stats, records_df = analyze_osm_pbf(file_path, 30)

    print(f"\n=== OSM PBF Analysis Summary ===")
    print(f"Total ways processed: {stats['total_ways']}")
    print(f"Total nodes processed: {stats['total_nodes']}")
    print(f"Total relations processed: {stats['total_relations']}")
    print(f"Total unique tags found: {len(stats['unique_tags'])}")

    # Print way tag statistics
    print_tag_stats(stats['way_stats'], "Way Tags", "Ways", limit=20)

    # Print node tag statistics
    print_tag_stats(stats['node_stats'], "Node Tags", "Nodes", limit=20)

    # Print relation tag statistics
    print_tag_stats(stats['relation_stats'], "Relation Tags", "Relations", limit=20)

    # Print other_tags statistics
    print_tag_stats(stats['other_tags_stats'], "other_tags Keys", "All Elements", limit=20)

    # Show column names in the data
    if not records_df.empty:
        print("\n=== DataFrame Columns ===")
        columns = list(records_df.columns)
        for i, col in enumerate(columns):
            print(f"{i + 1}. {col}")

    return stats, records_df


if __name__ == "__main__":
    if len(sys.argv) < 2:
        main()
    else:
        main(sys.argv[1])