import hashlib
import xml.etree.ElementTree as ET
import geopandas as gpd
import networkx as nx
import osmnx as ox
import pyproj
import osmium
import sys
import os
import pandas as pd
from collections import Counter, defaultdict
import shapely.geometry
from osmnx import settings
from osmnx import truncate
from shapely.ops import unary_union
from statistics import median

from data_collection_utils import collect_geographic_boundaries
from data_collection_utils import collect_census_data
from data_collection_utils import filter_boundaries_by_density


# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))

# Go up to the parent directory that contains the 'python' directory
# If your file is in /path/to/python/freight/frism_to_beam_freight_plans.py
# This will add /path/to to sys.path
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)
from python.utils.study_area_config import osm_highways


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
    Return "yes" only if all values are 'yes'/'true'/'1', otherwise "no".

    Parameters:
    -----------
    value : str, list, or scalar
        Input value(s) to standardize. Can be a single value, a semicolon-separated string,
        or a list of values.

    Returns:
    --------
    str
        "yes" if all values indicate "yes", "no" otherwise
    """
    valid_yes = {'yes', 'true', '1', True, 1}

    # Handle semicolon-separated string values
    if isinstance(value, str) and ';' in value:
        parts = [part.strip() for part in value.split(';')]
        return "no" if not parts or any(not p or p.lower() not in valid_yes for p in parts) else "yes"

    # Handle list case
    if isinstance(value, list):
        # Empty list or any value not in valid_yes should return "no"
        return "no" if not value or any(
            not v or (str(v).lower().strip() not in valid_yes if isinstance(v, (str, int)) else v is not True) for v in
            value) else "yes"

    # Handle single value case
    if isinstance(value, (str, int)):
        return "yes" if value and str(value).lower().strip() in valid_yes else "no"
    else:
        return "yes" if value is not None and bool(value) else "no"


def standardize_motorcar(value):
    """
    Standardize motorcar tag to "yes" or "no" strings.

    Parameters:
    -----------
    value : any
        The motorcar tag value

    Returns:
    --------
    str
        "no" if motorcars are explicitly prohibited
        "yes" otherwise (including empty values, which default to allowed)
    """
    # Define restrictive values
    restrictive_values = {"no", "false", "0"}

    # If value is None, NaN, or empty, assume motorcars are allowed
    if value is None or pd.isna(value) or (isinstance(value, str) and not value.strip()):
        return "yes"

    # Convert to string and lowercase for consistent processing
    if not isinstance(value, str):
        value = str(value)

    value = value.lower().strip()

    import re
    # Handle special cases with multiple values
    if ';' in value or '|' in value:
        parts = re.split(r'[;|]+', value)
        parts = [p.strip() for p in parts if p.strip()]

        if any(p in restrictive_values for p in parts):
            return "no"
        else:
            return "yes"

    # Check if the value is in the restrictive set
    if value in restrictive_values:
        return "no"

    # All other values (yes, empty, etc.) indicate access is allowed
    return "yes"


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

    # If value is None, NaN, or empty, assume motor vehicles are allowed
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
    Standardize maxspeed values to kilometers per hour (kph).

    Parameters:
    -----------
    value : any
        The maxspeed tag value
    default_kph : int, optional
        Default speed in kph to use if the value can't be parsed

    Returns:
    --------
    float or None
        Speed in kilometers per hour, or None if the value can't be parsed and no default is provided
    """
    if value is None or pd.isna(value) or (isinstance(value, str) and not value.strip()):
        return default_kph

    # Convert to string for processing
    if not isinstance(value, str):
        value = str(value)

    value = value.lower().strip()

    # Handle special cases
    if value == "signals" or value == "none" or value == "variable":
        return default_kph

    import re
    # Try to extract numeric value and unit
    match = re.match(r'^(\d+(?:\.\d+)?)\s*(mph|kmh|km/h|kph)?$', value)
    if match:
        speed_val = float(match.group(1))
        unit = match.group(2)

        # Convert to kph if necessary
        if unit in ["mph"]:
            return round(speed_val * 1.60934, 1)  # Convert mph to kph
        else:
            # If no unit or unit is already kph/kmh/km/h
            return float(speed_val)

    # If we can't parse the value
    return default_kph


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

    # If value is None, NaN, or empty, assume access is allowed
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
    edges['motorcar'] = edges['motorcar'].apply(standardize_motorcar)
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

    # Ensure hgv, mdv and oneway are strictly boolean
    edges["hgv"] = edges["hgv"].astype(bool)
    edges["mdv"] = edges["mdv"].astype(bool)
    edges['oneway'] = edges['oneway'].astype(bool)
    edges['access'] = edges['access'].astype(bool)
    edges['motor_vehicle'] = edges['motor_vehicle'].astype(bool)
    edges['motorcar'] = edges['motorcar'].astype(bool)

    # Convert back to MultiDiGraph
    g_updated = ox.graph_from_gdfs(nodes, edges)

    return g_updated

def shorten_osmid(osmid):
    # Convert osmid to string if it isn't already
    osmid_str = str(osmid)
    # Create a hash of the osmid
    hash_object = hashlib.md5(osmid_str.encode())
    # Get first 8 characters of the hash
    short_id = hash_object.hexdigest()[:8]
    return short_id


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


def str_median(values):
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


def download_and_prepare_osm_network(_study_area_config: dict) -> nx.MultiDiGraph:
    print("\n=== Starting OSM Network Download and Preparation ===")

    # Apply OSMNX settings
    for setting, value in _study_area_config["osmnx_settings"].items():
        setattr(ox.settings, setting, value)
    print("✓ OSMNX settings applied")

    # List to store the graphs
    graphs = []

    study_area = _study_area_config['study_area']
    print(f"Collecting {study_area} boundaries!")
    # Create density-specific paths
    base_name = f"{_study_area_config['work_dir']}/geo/{study_area}"
    census_year = _study_area_config["census_year"]
    utm_epsg = _study_area_config["utm_epsg"]
    state_fips_code = _study_area_config["state_fips"]
    county_fips_codes = _study_area_config["county_fips"]
    tolerance = _study_area_config["tolerance"]

    for layer_name, layer_config in _study_area_config["graph_layers"].items():
        # Get the geographic level for this layer
        geo_level = layer_config["geo_level"]

        # Get the minimum density if specified (for residential layers)
        min_density = layer_config.get("min_density_per_km2", 0)

        # Get the custom filter for this layer
        custom_filter = layer_config["custom_filter"]

        # Get the buffer zone size in meters if specified (for residential layers)
        buffer_in_meters = layer_config["buffer_zone_in_meters"]

        # Create the region boundary GeoDataFrame
        region_boundary_wgs84 = collect_geographic_boundaries(
            state_fips_code=state_fips_code,
            county_fips_codes=county_fips_codes,
            year=census_year,
            study_area_boundary_geo_path=f"{base_name}_{geo_level}_{census_year}_wgs84.geojson",
            geo_level=geo_level
        )

        if layer_name == "main":
            print(f"\nProcessing {layer_name} layer")
            graph_layer = to_convex_hull(region_boundary_wgs84, utm_epsg, buffer_in_meters)
            network_type = "drive"
            simplify = False
            retain_all = True
            truncate_by_edge = True

        elif layer_name == "residential":
            print(f"\nProcessing {layer_name} layer with minimum density: {min_density} pop/km²")
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
            print(f"\nProcessing {layer_name} layer to connect island through motor ferries...")
            graph_layer = to_convex_hull(region_boundary_wgs84, utm_epsg, buffer_in_meters)
            network_type = "all"
            simplify = True
            retain_all = True
            truncate_by_edge = False

        else:
            raise ValueError(f"Invalid layer name: {layer_name}")

        print("✓ Boundaries collected and unified")

        # Download OSM Network for this density level
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
                # Skip adding this empty graph
                continue

        # Add the graph to the list if it has edges
        adjust_and_add_graph(graphs, g)

    print("\n=== Processing Combined Network ===")
    g_combined = nx.compose_all(graphs)

    # Rest of the function remains the same...
    print(f"✓ Combined network has {g_combined.number_of_nodes()} nodes and {g_combined.number_of_edges()} edges")

    g_projected = ox.project_graph(g_combined, to_crs=utm_epsg).copy()
    print("✓ Network projected")

    g_with_speeds = ox.add_edge_speeds(g_projected)
    print("✓ Edge speeds added")

    g_processed_tags = process_tags(g_with_speeds, _study_area_config)
    print("✓ Edge tags processed")

    g_consolidated = ox.consolidate_intersections(
        g_processed_tags,
        tolerance=tolerance,
        rebuild_graph=True,
        dead_ends=True,
        reconnect_edges=True
    )
    print("✓ Intersections consolidated")

    nodes, edges = ox.graph_to_gdfs(g_consolidated)
    edges['length'] = edges['geometry'].length
    g_length_updated = ox.graph_from_gdfs(nodes, edges, graph_attrs=g_consolidated.graph)
    print("✓ Edge lengths updated")

    g_simplified = ox.simplification.simplify_graph(
        g_length_updated,
        edge_attrs_differ=["highway", "lanes", "maxspeed"],
        remove_rings=False,
        track_merged=True,
        edge_attr_aggs={
            "length": sum,
            "travel_time": sum,
            "hgv": bool_all,
            "mdv": bool_all,
            "lanes": str_median,
            "speed_kph": min,
            "maxspeed": min,
            "oneway": yes_no_all,
            "access": yes_no_all,
            "motor_vehicle": yes_no_all,
            "motorcar": yes_no_all,
            "reversed": bool_all,
            "maxweight": min

        }
    )
    print("✓ Network simplified")

    # Hash OSMID
    nodes, edges = ox.graph_to_gdfs(g_simplified)
    edges['osmid_hash'] = edges['osmid'].apply(lambda x: shorten_osmid(x))
    nodes['osmid_hash'] = nodes['osmid_original'].apply(lambda x: shorten_osmid(x))
    g_hashed = ox.graph_from_gdfs(nodes, edges)
    print("✓ OSMID Hashed")

    g_wgs84 = ox.project_graph(g_hashed, to_crs="epsg:4326")
    print("✓ Projected to WGS84")

    g_connected = ox.truncate.largest_component(g_wgs84.copy())
    print(f"✓ Final network has {g_connected.number_of_nodes()} nodes and {g_connected.number_of_edges()} edges")

    print("\n=== Network Download and Preparation Complete ===\n")
    return g_connected


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
    next_node_id = -1  # Start negative for generated IDs

    # OSM metadata fields to preserve
    metadata_fields = ["version", "changeset", "timestamp", "user", "uid"]

    # Write nodes + attributes as tags
    for n, d in G.nodes(data=True):
        lat, lon = d.get('y'), d.get('x')
        if lat is None or lon is None: continue

        # Use original OSM ID if available
        if 'osmid' in d:
            # Handle possible list of IDs
            if isinstance(d['osmid'], list) and d['osmid']:
                current_node_id = str(d['osmid'][0])
            else:
                current_node_id = str(d['osmid'])
        elif 'osmid_original' in d:
            # Sometimes OSMnx stores original IDs here
            if isinstance(d['osmid_original'], list) and d['osmid_original']:
                current_node_id = str(d['osmid_original'][0])
            else:
                current_node_id = str(d['osmid_original'])
        else:
            # Generate a negative ID if no original exists
            current_node_id = str(next_node_id)
            next_node_id -= 1

        # Prepare node attributes with default values
        node_attrs = {
            "id": current_node_id,
            "lat": str(lat),
            "lon": str(lon),
            "version": "1",
            "changeset": "1",
            "user": "osmnx",
            "uid": "1",
            "timestamp": "2020-01-01T00:00:00Z"
        }

        # Override with original metadata if available
        for field in metadata_fields:
            if field in d:
                node_attrs[field] = str(d[field])

        # Create node with all attributes
        node = ET.SubElement(root, "node", **node_attrs)

        node_map[n] = current_node_id
        for k, v in d.items():
            # Skip coordinates, IDs, and metadata fields that are already included as attributes
            if k not in ("x", "y", "osmid", "osmid_original") and k not in metadata_fields and v is not None:
                # Handle different value types appropriately
                if isinstance(v, list):
                    v_str = ";".join(str(item) for item in v)
                else:
                    v_str = str(v)
                ET.SubElement(node, "tag", k=str(k), v=v_str)

    # Write ways (edges) + attributes as tags
    next_way_id = -1  # Start negative for generated IDs
    for u, v, edata in G.edges(data=True):
        if u not in node_map or v not in node_map:
            continue

        # Use original way ID if available
        if 'osmid' in edata:
            if isinstance(edata['osmid'], list) and edata['osmid']:
                current_way_id = str(edata['osmid'][0])
            else:
                current_way_id = str(edata['osmid'])
        else:
            # Generate a negative ID if no original exists
            current_way_id = str(next_way_id)
            next_way_id -= 1

        # Prepare way attributes with default values
        way_attrs = {
            "id": current_way_id,
            "version": "1",
            "changeset": "1",
            "user": "osmnx",
            "uid": "1",
            "timestamp": "2020-01-01T00:00:00Z"
        }

        # Override with original metadata if available
        for field in metadata_fields:
            if field in edata:
                way_attrs[field] = str(edata[field])

        # Create way with all attributes
        way = ET.SubElement(root, "way", **way_attrs)

        ET.SubElement(way, "nd", ref=str(node_map[u]))
        ET.SubElement(way, "nd", ref=str(node_map[v]))

        # Dump all attributes, with proper handling for different data types
        for k, v_ in edata.items():
            # Skip metadata fields and osmid that are already included as attributes
            if k not in metadata_fields and k != 'osmid' and v_ is not None:
                # Handle list-type values
                if isinstance(v_, list):
                    v_str = ";".join(str(item) for item in v_)
                else:
                    v_str = str(v_)

                ET.SubElement(way, "tag", k=str(k), v=v_str)

    ET.ElementTree(root).write(filename, encoding="utf-8", xml_declaration=True)


def load_graph_from_osm(filename: str) -> nx.MultiDiGraph:
    """
    Load a graph from an OSM file.

    Parameters:
    -----------
    filename : str
        The path to the OSM file.

    Returns:
    --------
    nx.MultiDiGraph
        The loaded graph.
    """
    G = nx.MultiDiGraph()

    tree = ET.parse(filename)
    root = tree.getroot()

    node_map = {}

    # Read nodes
    for node in root.findall('node'):
        node_id = int(node.get('id'))
        lat = float(node.get('lat'))
        lon = float(node.get('lon'))
        G.add_node(node_id, y=lat, x=lon)
        node_map[node_id] = (lat, lon)

        for tag in node.findall('tag'):
            G.nodes[node_id][tag.get('k')] = tag.get('v')

    # Read ways (edges)
    for way in root.findall('way'):
        nd_refs = [int(nd.get('ref')) for nd in way.findall('nd')]
        for u, v in zip(nd_refs[:-1], nd_refs[1:]):
            # Add edge and get the key for the new edge
            key = G.add_edge(u, v)
            for tag in way.findall('tag'):
                G.edges[u, v, key][tag.get('k')] = tag.get('v')

    return G


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
                    writer.writerow([network_name, number_of_ways, osm_file_path])  # Write network name, number of ways, and path
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
            'percent_present': round(total / handler.total_ways * 100, 2) if handler.total_ways > 0 else 0
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
            'percent_present': round(total / handler.total_nodes * 100, 2) if handler.total_nodes > 0 else 0
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
            'percent_present': round(total / handler.total_relations * 100, 2) if handler.total_relations > 0 else 0
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
            'percent_present': round(total / (handler.total_ways + handler.total_nodes + handler.total_relations) * 100, 2)
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



