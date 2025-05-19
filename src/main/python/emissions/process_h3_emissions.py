import os
import h3
import sys
from pathlib import Path
from shapely.geometry import Polygon
from shapely.geometry import LineString
import pandas as pd
import geopandas as gpd
from concurrent.futures import ProcessPoolExecutor
from rtree import index
from _beam_emissions_plotting import plot_h3_heatmap
from _emissions_utils import read_skims_emissions_chunked

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.network_utils import load_network
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name
from python.utils.study_area_config import get_fuel_key

def create_h3_polygon(h3_cell):
    """
    Create a Shapely polygon from an H3 cell index

    Args:
        h3_cell: H3 cell index

    Returns:
        Shapely Polygon object representing the H3 cell boundary
    """
    # Get the boundary coordinates (lat, lng pairs)
    boundary = h3.cell_to_boundary(h3_cell)

    # Convert to (lng, lat) format needed by Shapely
    shapely_coords = [(lng, lat) for lat, lng in boundary]

    # Create and return a Shapely polygon
    return Polygon(shapely_coords)


def calculate_intersection(data):
    """
    Calculate intersection between an H3 cell and a network link

    Args:
        data: Tuple containing (h3_cell, from_x, from_y, to_x, to_y, link_id, link_length)

    Returns:
        Tuple of (h3_cell, link_id, length_ratio)
    """
    h3_cell, from_x, from_y, to_x, to_y, link_id, link_length = data

    try:
        # Create H3 cell polygon
        boundary = h3.cell_to_boundary(h3_cell)
        shapely_coords = [(lng, lat) for lat, lng in boundary]
        h3_poly = Polygon(shapely_coords)

        # Create network link linestring
        link_line = LineString([(from_x, from_y), (to_x, to_y)])

        # Calculate intersection and ratio
        intersection = h3_poly.intersection(link_line)
        length_ratio = intersection.length / link_length if link_length > 0 else 0

        return h3_cell, link_id, length_ratio

    except Exception as e:
        print(f"Error calculating intersection: {e}")
        return h3_cell, link_id, 0


def calculate_batch_intersections(batch_data):
    """
    Process a batch of H3 cells and calculate intersections with network links

    Args:
        batch_data: Tuple of (batch_df, network_df) where:
            batch_df: DataFrame with h3_cell and linkId columns
            network_df: DataFrame with network link information

    Returns:
        List of dictionaries with intersection results
    """
    batch_df, network_df = batch_data
    results = []

    for _, row in batch_df.iterrows():
        try:
            # Get the H3 cell and create polygon
            h3_cell = row['h3_cell']
            boundary = h3.cell_to_boundary(h3_cell)
            shapely_coords = [(lng, lat) for lat, lng in boundary]
            h3_poly = Polygon(shapely_coords)

            # Get network link data and create linestring
            link_id = row['linkId']
            link_data = network_df.loc[network_df['linkId'] == link_id].iloc[0]
            link_line = LineString([
                (link_data['fromLocationX'], link_data['fromLocationY']),
                (link_data['toLocationX'], link_data['toLocationY'])
            ])

            # Calculate intersection
            intersection = h3_poly.intersection(link_line)

            # Store result
            results.append({
                'h3_cell': h3_cell,
                'linkId': link_id,
                'intersection_length': intersection.length
            })

        except Exception as e:
            print(f"Error processing intersection for cell {row['h3_cell']}, link {row['linkId']}: {e}")
            # Add zero-length intersection as fallback
            results.append({
                'h3_cell': row['h3_cell'],
                'linkId': row['linkId'],
                'intersection_length': 0
            })

    return results


def generate_h3_intersections(network_df, resolution, output_dir):
    """
    Generate H3 cell intersections with the network

    Args:
        network_df: DataFrame with network link data
        resolution: H3 grid resolution (higher = smaller cells)
        output_dir: Directory to save output

    Returns:
        DataFrame with H3 cell to network link intersection data
    """
    # Check for existing cached results
    output_file = f'{output_dir}/network.h3.csv'
    if os.path.exists(output_file):
        print(f"Loading existing H3 intersection data from {output_file}")
        return pd.read_csv(output_file)

    print(f"Generating H3 intersection data with resolution {resolution}")
    print(f"Network data shape: {network_df.shape}")

    # Clean network data - remove rows with missing coordinates
    coord_cols = ['fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']
    network_clean = network_df.dropna(subset=coord_cols)
    print(f"Clean network data shape: {network_clean.shape}")

    # Create bounding box for the network
    lats = network_clean[['fromLocationY', 'toLocationY']].values.flatten()
    lngs = network_clean[['fromLocationX', 'toLocationX']].values.flatten()

    # Create a simple polygon of the bounding box (counterclockwise)
    bbox_coords = [
        (min(lats), min(lngs)),  # Bottom-left
        (min(lats), max(lngs)),  # Bottom-right
        (max(lats), max(lngs)),  # Top-right
        (max(lats), min(lngs)),  # Top-left
        (min(lats), min(lngs))  # Close the polygon
    ]

    # Generate H3 cells that cover the bounding box
    bbox_poly = h3.LatLngPoly(bbox_coords)
    h3_cells = list(h3.h3shape_to_cells(bbox_poly, resolution))
    print(f"Generated {len(h3_cells)} H3 cells")

    if len(h3_cells) == 0:
        print("No H3 cells created. Check bounding box and resolution.")
        return pd.DataFrame()

    # Create H3 cell geometries in parallel for better performance
    print("Creating H3 cell geometries...")
    chunk_size = 10000
    h3_cell_geometries = []

    for i in range(0, len(h3_cells), chunk_size):
        chunk = h3_cells[i:i + chunk_size]
        with ProcessPoolExecutor() as executor:
            chunk_geometries = list(executor.map(create_h3_polygon, chunk))
        h3_cell_geometries.extend(chunk_geometries)

    # Create GeoDataFrame with H3 cells
    h3_gdf = gpd.GeoDataFrame(
        {'h3_cell': h3_cells},
        geometry=h3_cell_geometries,
        crs="EPSG:4326"
    )

    # Create network GeoDataFrame with LineStrings
    print("Creating network LineStrings...")
    network_geoms = []

    for _, row in network_clean.iterrows():
        network_geoms.append(LineString([
            (row['fromLocationX'], row['fromLocationY']),
            (row['toLocationX'], row['toLocationY'])
        ]))

    network_gdf = gpd.GeoDataFrame(
        network_clean,
        geometry=network_geoms,
        crs="EPSG:4326"
    )

    # Create spatial index for network lines
    print("Building spatial index...")
    idx = index.Index()
    for i, geom in enumerate(network_gdf.geometry):
        idx.insert(i, geom.bounds)

    # Find potential intersections using spatial index
    print("Finding potential intersections...")
    join_pairs = []

    for h_idx, h_geom in enumerate(h3_gdf.geometry):
        for n_idx in idx.intersection(h_geom.bounds):
            if h_geom.intersects(network_gdf.geometry.iloc[n_idx]):
                join_pairs.append((h_idx, n_idx))

    # If no intersections found, try with buffered lines
    if not join_pairs:
        print("No intersections found. Trying with buffered network lines...")
        network_gdf['buffered_geom'] = network_gdf.geometry.buffer(0.0001)

        # Update spatial index with buffered geometries
        idx = index.Index()
        for i, geom in enumerate(network_gdf['buffered_geom']):
            idx.insert(i, geom.bounds)

        # Find intersections with buffered lines
        for h_idx, h_geom in enumerate(h3_gdf.geometry):
            for n_idx in idx.intersection(h_geom.bounds):
                if h_geom.intersects(network_gdf['buffered_geom'].iloc[n_idx]):
                    join_pairs.append((h_idx, n_idx))

    if not join_pairs:
        print("No intersections found between H3 cells and network.")
        return pd.DataFrame()

    # Create dataframe from join pairs
    print(f"Found {len(join_pairs)} potential intersections")
    h_indices, n_indices = zip(*join_pairs)

    joined = pd.DataFrame({
        'h3_cell': h3_gdf.iloc[list(h_indices)]['h3_cell'].values
    })

    # Add network data
    for col in network_gdf.columns:
        if col not in ['geometry', 'buffered_geom']:
            joined[col] = network_gdf.iloc[list(n_indices)][col].values

    # Process intersections in parallel batches
    print("Calculating precise intersections...")
    batch_size = 1000
    batches = [joined.iloc[i:i + batch_size] for i in range(0, len(joined), batch_size)]
    batch_data = [(batch, network_clean) for batch in batches]

    all_results = []
    with ProcessPoolExecutor() as executor:
        batch_results = list(executor.map(calculate_batch_intersections, batch_data))

    for batch in batch_results:
        all_results.extend(batch)

    # Create final dataframe with intersection data
    print("Processing final results...")
    intersection_df = pd.DataFrame(all_results)

    # Calculate length ratios
    intersection_df = pd.merge(
        intersection_df,
        network_clean[['linkId', 'linkLength']],
        on='linkId'
    )
    intersection_df['length_ratio'] = intersection_df['intersection_length'] / intersection_df['linkLength']

    # Keep only necessary columns
    result_df = intersection_df[['h3_cell', 'linkId', 'length_ratio']]

    # Save results
    print(f"Saving results to {output_file}")
    result_df.to_csv(output_file, index=False)

    return result_df


# Generic function to process H3 data for any data column
def process_h3_data(h3_df, data_df, data_col):
    """
    Process H3 data for a given data column

    Args:
        h3_df: DataFrame with H3 intersection data
        data_df: DataFrame with data to process
        data_col: Column name for data to process

    Returns:
        DataFrame with H3 cell aggregated data
    """
    print(f"Processing H3 data for column: {data_col}")
    print(f"Input data shape: {data_df.shape}")

    # Ensure data column is numeric
    data_df[data_col] = pd.to_numeric(data_df[data_col], errors='coerce')
    data_df = data_df.dropna(subset=[data_col])
    print(f"Filtered data shape: {data_df.shape}")

    # Merge with intersection data
    merged = pd.merge(h3_df, data_df, on='linkId', how='inner')
    print(f"Merged data shape: {merged.shape}")

    # Calculate weighted values based on intersection ratio
    merged[f'weighted_{data_col}'] = merged[data_col] * merged['length_ratio']

    # Group by H3 cell and scenario, then sum weighted values
    result = merged.groupby(['scenario', 'h3_cell'])[f'weighted_{data_col}'].sum().reset_index()
    print(f"Result shape: {result.shape}")

    return result


def process_h3_emissions(emissions_df, intersection_df, pollutant):
    """
    Process H3 emissions data for a specific pollutant

    Args:
        emissions_df: DataFrame with emissions data
        intersection_df: DataFrame with H3 intersection data
        pollutant: Name of the pollutant to process

    Returns:
        DataFrame with H3 cell emissions data
    """
    print(f"Processing H3 emissions for pollutant: {pollutant}")

    # Filter emissions data for the specific pollutant
    filtered = emissions_df[emissions_df['pollutant'] == pollutant][['scenario', 'linkId', 'rate']]
    filtered['rate'] = pd.to_numeric(filtered['rate'], errors='coerce')
    filtered = filtered.dropna()
    print(f"Filtered emissions shape: {filtered.shape}")

    # Merge with intersection data
    merged = pd.merge(intersection_df, filtered, on='linkId', how='inner')
    print(f"Merged data shape: {merged.shape}")

    # Calculate weighted emissions based on intersection ratio
    merged[pollutant] = merged['rate'] * merged['length_ratio']

    # Group by H3 cell and scenario, then sum emissions
    result = merged.groupby(['scenario', 'h3_cell'])[pollutant].sum().reset_index()
    print(f"Result shape: {result.shape}")

    return result


def main():
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    driving_processes = ["RUNEX", "PMBW", "PMTW", "RUNLOSS", "PRDUST"]
    stationary_processes = ["STREX", "DIURN", "HOTSOAK", "RUNLOSS", "IDLEX"]
    h3_resolution = 8
    study_area_config = get_area_config(area)
    scenario_config = study_area_config["emissions"][scenario]
    run_config = scenario_config["run"]
    run_config["emissions_dir"] = f"emissions/{run_batch}"
    run_config["events_file"] = f"beam-runs/{run_batch}/{scenario}/0.events.csv.gz"
    run_config["emissions_skims_file"] = f"beam-runs/{run_batch}/{scenario}/0.skimsEmissions.csv.gz"
    run_config["link_stats_file"] = f"beam-runs/{run_batch}/{scenario}/0.linkstats.csv.gz"
    run_config["sample_portion"] = 0.1

    ###################################################################################################


    work_dir = study_area_config["work_dir"]
    output_dir = os.path.join(work_dir, run_config["output_dir"])
    utm_epsg = study_area_config["geo"]["utm_epsg"]

    # Output directories
    plot_dir = f'{output_dir}/_plots'
    Path(plot_dir).mkdir(parents=True, exist_ok=True)

    network_name = generate_network_name(study_area_config)
    network_file = f'{work_dir}/network/{network_name}/network.csv.gz'
    expansion_factor = 1 / run_config["sample_portion"]
    car_bike_fuel_map = scenario_config["mapping"]["fuel"]["emfac-pax"]
    bus_fuel_map = scenario_config["mapping"]["fuel"]["emfac-bus"]
    freight_fuel_map = scenario_config["mapping"]["fuel"]["emfac-ft"]

    # File paths
    ft_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["ft_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    pax_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["pax_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    tours_file = f"{work_dir}/{scenario_config["beam"]["tours_file"]}"
    carriers_file = f"{work_dir}/{scenario_config["beam"]["carriers_file"].replace(".csv", "--EM.csv")}"
    emissions_skims_file = f"{work_dir}/{run_config["emissions_skims_file"]}"

    # Reading files
    pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
    ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
    tours = pd.read_csv(tours_file)[["tourId", 'departureTimeInSec']]
    carriers = pd.read_csv(carriers_file)[["tourId", 'vehicleTypeId']]

    # Processing
    pax_vehicle_types = pax_vehicle_types[~pax_vehicle_types["emissionsRatesFile"].isna()].copy()
    pax_vehicle_types['mappedClass'] = pax_vehicle_types['vehicleCategory'].str.strip()
    pax_vehicle_types['fuel_key'] = pax_vehicle_types.apply(get_fuel_key, axis=1)
    bus_mask = pax_vehicle_types['vehicleCategory'] == "MediumDutyPassenger"
    car_bike_vehicle_types = pax_vehicle_types[~bus_mask].copy()
    car_bike_vehicle_types['mappedFuel'] = car_bike_vehicle_types['fuel_key'].map(car_bike_fuel_map)
    bus_vehicle_types = pax_vehicle_types[bus_mask].copy()
    bus_vehicle_types['mappedFuel'] = bus_vehicle_types['fuel_key'].map(bus_fuel_map)
    ft_vehicle_types['fuel_key'] = ft_vehicle_types.apply(get_fuel_key, axis=1)
    ft_vehicle_types['mappedFuel'] = ft_vehicle_types['fuel_key'].map(freight_fuel_map)
    ft_vehicle_types['mappedClass'] = ft_vehicle_types['vehicleCategory'].str.strip()
    vehicle_types = pd.concat([pax_vehicle_types, ft_vehicle_types], axis=0)

    tours_types_2018 = pd.merge(
        tours,
        pd.merge(
            carriers,
            ft_vehicle_types[["vehicleTypeId", 'mappedFuel', 'mappedClass']],
            on="vehicleTypeId"),
        on="tourId"
    )
    tours_types_2018["scenario"] = scenario

    print("Loading network data...")
    network = load_network(network_file, utm_epsg)
    network_h3_intersection = generate_h3_intersections(network, h3_resolution, output_dir)

    print("Processing skims data...")
    skims = read_skims_emissions_chunked(
        vehicle_types,
        network,
        emissions_skims_file,
        expansion_factor,
        scenario,
        chunk_size=1000000
    )

    print("Processing activities...")
    driving_process_activity = skims[
        (skims["process"].isin(driving_processes)) & (skims["vht"] > 0)
    ].groupby(["scenario", "linkId"])["vmt"].sum().reset_index(name="vmt")

    h3_vmt = process_h3_data(network_h3_intersection, driving_process_activity, "vmt")
    vmt_column = "Weighted VMT from driving activities"
    h3_vmt.rename(columns={"weighted_vmt": vmt_column}, inplace=True)

    parking_process_activity = skims[
        (skims["process"].isin(stationary_processes)) & (skims["vht"] == 0)
    ].groupby(["scenario", "linkId"]).size().reset_index(name='count')

    h3_count = process_h3_data(network_h3_intersection, parking_process_activity, "count")
    count_column = "Weighted count of parking activities"
    h3_count.rename(columns={"weighted_count": count_column}, inplace=True)

    print("Processing emissions...")
    # Process each pollutant
    pm25 = process_h3_emissions(skims, network_h3_intersection, 'PM2_5')
    nox = process_h3_emissions(skims, network_h3_intersection, 'NOx')
    co = process_h3_emissions(skims, network_h3_intersection, 'CO')
    co2 = process_h3_emissions(skims, network_h3_intersection, 'CO2')

    # Convert to grams per square meter
    pm25_column = "PM2_5 in grams per square meter"
    pm25[pm25_column] = pm25["PM2_5"] * 1e6  # from metric ton to gram

    nox_column = "NOx in grams per square meter"
    nox[nox_column] = nox["NOx"] * 1e6

    co_column = "CO in grams per square meter"
    co[co_column] = co["CO"] * 1e6

    co2_column = "CO2 in grams per square meter"
    co2[co2_column] = co2["CO2"] * 1e6

    print("Calculating delta emissions...")
    # Calculate delta emissions between scenarios
    # PM2.5 delta
    # pm25_delta = pm25.pivot(index='h3_cell', columns='scenario', values='PM2_5').reset_index()
    # pm25_delta = pm25_delta.fillna(0)
    # pm25_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # pm25_delta['Delta_PM2_5'] = pm25_delta[scenario_2050_label] - pm25_delta[scenario_2018_label]
    # pm25_delta_column = "Delta PM2_5 in grams per square meter"
    # pm25_delta[pm25_delta_column] = pm25_delta["Delta_PM2_5"] * 1e6

    # NOx delta
    # nox_delta = nox.pivot(index='h3_cell', columns='scenario', values='NOx').reset_index()
    # nox_delta = nox_delta.fillna(0)
    # nox_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # nox_delta['Delta_NOx'] = nox_delta[scenario_2050_label] - nox_delta[scenario_2018_label]
    # nox_delta_column = "Delta NOx in grams per square meter"
    # nox_delta[nox_delta_column] = nox_delta["Delta_NOx"] * 1e6

    # CO2 delta
    # co2_delta = co2.pivot(index='h3_cell', columns='scenario', values='CO2').reset_index()
    # co2_delta = co2_delta.fillna(0)
    # co2_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
    # co2_delta['Delta_CO2'] = co2_delta[scenario_2050_label] - co2_delta[scenario_2018_label]
    # co2_delta_column = "Delta CO2 in grams per square meter"
    # co2_delta[co2_delta_column] = co2_delta["Delta_CO2"] * 1e6

    # Figure 3: Activity heatmaps
    plot_h3_heatmap(h3_vmt, vmt_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(h3_count, count_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 4: Emissions heatmaps
    plot_h3_heatmap(pm25, pm25_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(nox, nox_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(co2, co2_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 6: Delta emissions heatmaps
    # plot_h3_heatmap(pm25_delta, pm25_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(nox_delta, nox_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(co2_delta, co2_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    print("Processing completed successfully.")


if __name__ == "__main__":
    main()