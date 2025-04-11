import sys
import os.path
import time
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.csv as pv
from pyproj import Transformer
from shapely.geometry import LineString

from _beam_emissions_plotting import *
from _emissions_utils import generate_emfac_beam_class_mapping

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import emissions_config
from python.utils.study_area_config import get_fuel_key
from python.utils.study_area_config import generate_network_name

# Configure pandas display options
pd.set_option('display.max_columns', 20)

# ################
# ### Constants ##
# ################

# Define schema for skims data
SKIMS_SCHEMA = pa.schema([
    ('hour', pa.int64()),
    ('linkId', pa.int64()),
    ('tazId', pa.string()),
    ('vehicleTypeId', pa.string()),
    ('emissionsProcess', pa.string()),
    ('speedInMps', pa.float64()),
    ('energyInJoule', pa.float64()),
    ('observations', pa.int64()),
    ('iterations', pa.int64()),
    ('CH4', pa.float64()),
    ('CO', pa.float64()),
    ('CO2', pa.float64()),
    ('HC', pa.float64()),
    ('NH3', pa.float64()),
    ('NOx', pa.float64()),
    ('PM', pa.float64()),
    ('PM10', pa.float64()),
    ('PM2_5', pa.float64()),
    ('ROG', pa.float64()),
    ('SOx', pa.float64()),
    ('TOG', pa.float64()),
    ('BC', pa.float64()),
    ('BCm', pa.float64()),
    ('BCh', pa.float64())
])


# ################
# ### Functions ##
# ################

def read_skims_emissions_chunked(
        vehicle_types,
        network,
        emissions_skims_file,
        expansion_factor,
        scenario_name,
        chunk_size=1000000
):
    """
    Read and process emissions data from skims file in chunks

    Args:
        scenario_name: Name of the scenario
        chunk_size: Size of chunks to process at once

    Returns:
        DataFrame with processed emissions data
    """
    start_time = time.time()

    unique_vehicle_types_id = vehicle_types['vehicleTypeId'].unique()

    # Initialize an empty list to store the processed chunks
    result_chunks = []

    # Set up the CSV reader with chunking
    csv_reader = pv.open_csv(
        emissions_skims_file,
        read_options=pv.ReadOptions(block_size=chunk_size, use_threads=True),
        parse_options=pv.ParseOptions(delimiter=','),
        convert_options=pv.ConvertOptions(column_types=SKIMS_SCHEMA)
    )

    # Get total file size for progress bar
    total_size = os.path.getsize(emissions_skims_file)

    # Initialize progress bar
    pbar = tqdm(total=total_size, unit='B', unit_scale=True, desc="Processing chunks",
                position=0, leave=True, mininterval=1.0, maxinterval=10.0, miniters=1)

    # Process the skims file in chunks
    for chunk in csv_reader:
        chunk_size = chunk.nbytes

        # Filter the chunk
        filtered_chunk = chunk.filter(chunk['vehicleTypeId'].isin(unique_vehicle_types_id))

        # Perform calculations in PyArrow
        observations_expansion = pc.multiply(
            filtered_chunk['observations'], pc.cast(pa.scalar(expansion_factor), pa.float64())
        )

        new_columns = []
        new_fields = []
        for pollutant in emissions_config["pollutants"].keys():
            new_fields.append(pa.field(f'scaled_{pollutant}', pa.float64(), True))
            new_columns.append(pc.multiply(
                pc.divide(
                    filtered_chunk[pollutant], pc.cast(pa.scalar(1e6), pa.float64())
                ),
                observations_expansion
            ))

        new_fields.append(pa.field('kwh', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(
                    filtered_chunk['energyInJoule'], pc.cast(pa.scalar(3.6e6), pa.float64())
                ),
                observations_expansion
            )
        )

        new_fields.append(pa.field('vht', pa.float64(), True))
        new_columns.append(
            pc.multiply(
                pc.divide(
                    filtered_chunk['travelTimeInSecond'], pc.cast(pa.scalar(3.6e3), pa.float64())
                ),
                observations_expansion
            )
        )

        # Create a new RecordBatch with additional columns
        new_schema = filtered_chunk.schema
        for field in new_fields:
            new_schema = new_schema.append(field)

        new_columns = filtered_chunk.columns + new_columns
        filtered_chunk = pa.RecordBatch.from_arrays(new_columns, schema=new_schema)

        # Convert to pandas
        df_chunk = filtered_chunk.to_pandas()

        # Merge with vehicleTypes and network
        df_chunk_merged = (
            df_chunk
            .merge(vehicle_types[['vehicleTypeId', 'mappedClass', 'mappedFuel', 'emfacId']], on='vehicleTypeId', how='left')
            .merge(network[['linkId', 'linkLength']], on='linkId', how='left')
        )

        # Calculate annualHourlyMVMT
        df_chunk_merged['vmt'] = (df_chunk_merged['linkLength'] * 6.21371192e-4) * observations_expansion

        # Rename column
        df_chunk_merged.rename(columns={'emissionsProcess': 'process'}, inplace=True)

        # Melt the dataframe
        id_vars = ['hour', 'linkId', 'tazId', 'emfacId', 'mappedClass', 'mappedFuel', 'process', 'kwh', 'vmt', 'vht']
        value_vars = [f'scaled_{pollutant}' for pollutant in emissions_config["pollutants"].keys()]
        melted_chunk = df_chunk_merged.melt(
            id_vars=id_vars,
            value_vars=value_vars,
            var_name='pollutant',
            value_name='rate'
        )
        melted_chunk['pollutant'] = melted_chunk['pollutant'].str.replace('scaled_', '')
        melted_chunk['scenario'] = scenario_name

        result_chunks.append(melted_chunk)

        # Update progress bar
        pbar.update(chunk_size)

    # Close progress bar
    pbar.close()

    # Combine all processed chunks
    melted = pd.concat(result_chunks, ignore_index=True)

    end_time = time.time()
    print(f"Time taken to read the file: {end_time - start_time:.2f} seconds to read file {skims_file}")

    return melted


def create_model_vmt_comparison_chart(skims_data, emfac_vmt, output_dir):
    """
    Create a comparison chart between EMFAC and FAMOS VMT data

    Args:
        skims_data: Processed skims data
        output_dir: Directory to save output

    Returns:
        DataFrame with combined EMFAC and FAMOS VMT data
    """
    df = emfac_vmt.copy()
    df["fuel_class"] = df["mappedFuel"] + "-" + df["mappedClass"]
    emfac_vmt = df.groupby(["fuel_class"])["total_vmt"].sum().reset_index()
    emfac_vmt.rename(columns={'total_vmt': 'mvmt'}, inplace=True)
    emfac_vmt["model"] = "emfac"

    beam_vmt = skims_data.groupby(["mappedClass", "mappedFuel"])["vmt"].sum().reset_index()
    beam_vmt["fuel_class"] = beam_vmt["mappedFuel"] + "-" + beam_vmt["mappedClass"]
    beam_vmt = beam_vmt[["fuel_class", "vmt"]].copy()
    beam_vmt.rename(columns={'vmt': 'mvmt'}, inplace=True)
    beam_vmt["model"] = "beam"
    emfac_beam_vmt = pd.concat([emfac_vmt, beam_vmt], axis=0)
    emfac_beam_vmt.to_csv(f"{output_dir}/emfac_beam_vmt_by_fuel_class.csv")
    return emfac_beam_vmt


def load_network(network_file, source_epsg):
    """
    Load and transform network data

    Args:
        network_file: Path to network CSV file
        source_epsg: Source EPSG code for coordinate transformation

    Returns:
        DataFrame with network data
    """
    # Read and process network file
    network = pd.read_csv(network_file)
    transformer = Transformer.from_crs(source_epsg, "EPSG:4326", always_xy=True)

    # Vectorized coordinate conversion
    network[['fromLocationX', 'fromLocationY']] = network.apply(
        lambda row: pd.Series(transformer.transform(row['fromLocationX'], row['fromLocationY'])),
        axis=1, result_type='expand'
    )
    network[['toLocationX', 'toLocationY']] = network.apply(
        lambda row: pd.Series(transformer.transform(row['toLocationX'], row['toLocationY'])),
        axis=1, result_type='expand'
    )

    return network[['linkId', 'linkLength', 'fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']]


def generate_h3_intersections(network_df, resolution, output_dir):
    """
    Generate H3 cell intersections with network

    Args:
        network_df: Network dataframe
        resolution: H3 resolution
        output_dir: Directory to save output

    Returns:
        DataFrame with intersection data
    """
    print(f"Initial network_df shape: {network_df.shape}")

    # Remove rows with NaN values in coordinate columns
    coord_columns = ['fromLocationX', 'fromLocationY', 'toLocationX', 'toLocationY']
    network_clean = network_df.dropna(subset=coord_columns)
    print(f"Clean network_df shape: {network_clean.shape}")

    # Create bounding box
    lats = network_clean[['fromLocationY', 'toLocationY']].values.flatten()
    lons = network_clean[['fromLocationX', 'toLocationX']].values.flatten()
    bbox = [[
        [min(lats), min(lons)],
        [min(lats), max(lons)],
        [max(lats), max(lons)],
        [max(lats), min(lons)],
        [min(lats), min(lons)]  # Close the polygon
    ]]

    # Generate H3 cells
    h3_cells = list(h3.polyfill({'type': 'Polygon', 'coordinates': bbox}, resolution))
    print(f"Number of H3 cells: {len(h3_cells)}")

    if len(h3_cells) == 0:
        print("No H3 cells created. Check your bounding box and resolution.")
        return pd.DataFrame()

    # Create GeoDataFrame of H3 cells
    h3_gdf = gpd.GeoDataFrame(
        {'h3_cell': h3_cells},
        geometry=[Polygon(h3.h3_to_geo_boundary(h, geo_json=True)) for h in h3_cells],
        crs="EPSG:4326"
    )

    # Create network GeoDataFrame
    def create_linestring(row):
        return LineString([(row['fromLocationX'], row['fromLocationY']),
                           (row['toLocationX'], row['toLocationY'])])

    network_gdf = gpd.GeoDataFrame(
        network_clean,
        geometry=network_clean.apply(create_linestring, axis=1),
        crs="EPSG:4326"
    )

    # Spatial join
    joined = gpd.sjoin(h3_gdf, network_gdf, how="inner", predicate="intersects")
    print(f"Joined DataFrame shape after spatial join: {joined.shape}")

    if joined.empty:
        print("No intersections found between H3 cells and network geometries.")
        return pd.DataFrame()

    # Calculate intersections and lengths
    def calculate_intersection(row):
        try:
            h3_poly = Polygon(h3.h3_to_geo_boundary(row['h3_cell'], geo_json=True))
            line = row['geometry']
            intersection = h3_poly.intersection(line)
            return pd.Series({'intersection_length': intersection.length})
        except Exception as e:
            print(f"Error in calculate_intersection: {e}")
            return pd.Series({'intersection_length': 0})

    tqdm.pandas(desc="Calculating intersections")
    joined['intersection_length'] = joined.progress_apply(calculate_intersection, axis=1)

    # Calculate length ratios
    joined['length_ratio'] = joined['intersection_length'] / joined['linkLength']

    # Keep only necessary columns
    intersection_df = joined[['h3_cell', 'linkId', 'length_ratio']]

    intersection_df.to_csv(f'{output_dir}/network.h3.csv', index=False)

    return intersection_df


def process_h3_data(h3_df, data_df, data_col):
    """
    Process H3 data for a given data column

    Args:
        h3_df: H3 intersection dataframe
        data_df: Data dataframe
        data_col: Column name for data to process

    Returns:
        DataFrame with H3 cell data
    """
    print(f"Initial emissions_df shape: {data_df.shape}")

    # Filter emissions data for the specific pollutant
    data_df[data_col] = pd.to_numeric(data_df[data_col], errors='coerce')
    data_df_filtered = data_df.dropna()
    print(f"Filtered emissions shape: {data_df_filtered.shape}")

    # Merge with intersection data
    merged = pd.merge(h3_df, data_df_filtered, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'weighted_{data_col}'] = merged[data_col] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'weighted_{data_col}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result


def process_h3_emissions(emissions_df, intersection_df, pollutant):
    """
    Process H3 emissions data for a specific pollutant

    Args:
        emissions_df: Emissions dataframe
        intersection_df: H3 intersection dataframe
        pollutant: Pollutant name

    Returns:
        DataFrame with H3 cell emissions data
    """
    print(f"Initial emissions_df shape: {emissions_df.shape}")

    # Filter emissions data for the specific pollutant
    filtered_emissions = emissions_df[emissions_df['pollutant'] == pollutant][['scenario', 'linkId', 'rate']]
    filtered_emissions['rate'] = pd.to_numeric(filtered_emissions['rate'], errors='coerce')
    filtered_emissions = filtered_emissions.dropna()
    print(f"Filtered emissions shape: {filtered_emissions.shape}")

    # Merge with intersection data
    merged = pd.merge(intersection_df, filtered_emissions, on='linkId', how='inner')
    print(f"Merged DataFrame shape: {merged.shape}")

    # Calculate normalized emissions
    merged[f'{pollutant}'] = merged['rate'] * merged['length_ratio']

    # Group by H3 cell and sum normalized emissions
    result = merged.groupby(['scenario', 'h3_cell'])[f'{pollutant}'].sum().reset_index()
    print(f"Final result shape: {result.shape}")
    return result


def calculate_delta_emissions(emissions_df, pollutant, scenario1, scenario2):
    """
    Calculate delta emissions between two scenarios

    Args:
        emissions_df: Emissions dataframe
        pollutant: Pollutant name
        scenario1: First scenario name
        scenario2: Second scenario name

    Returns:
        DataFrame with delta emissions data
    """
    pivot_df = emissions_df.pivot(index='h3_cell', columns='scenario', values=pollutant).reset_index()
    pivot_df = pivot_df.fillna(0)
    pivot_df["scenario"] = f"{scenario1}-{scenario2}"
    pivot_df[f'Delta_{pollutant}'] = pivot_df[scenario1] - pivot_df[scenario2]
    return pivot_df


# ################
# ##### Main #####
# ################

def main():
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
    driving_processes = ["RUNEX", "PMBW", "PMTW", "RUNLOSS", "PRDUST"]
    stationary_processes = ["STREX", "DIURN", "HOTSOAK", "RUNLOSS", "IDLEX"]
    h3_resolution = 8
    study_area_config = get_area_config(area)
    run_config = study_area_config["run"]
    run_config["emissions_dir"] = f"emissions/{run_batch}"
    run_config["events_file"] = f"beam-runs/{run_batch}/{scenario}/0.events.csv.gz"
    run_config["emissions_skims_file"] = f"beam-runs/{run_batch}/{scenario}/0.skimsEmissions.csv.gz"
    run_config["link_stats_file"] = f"beam-runs/{run_batch}/{scenario}/0.linkstats.csv.gz"
    run_config["sample_portion"] = 0.1

    ###################################################################################################

    scenario_config = study_area_config["emissions"][scenario]
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
    ft_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["ft_vehicle_types_file"]}--TrAP.csv"
    pax_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["pax_vehicle_types_file"]}--TrAP.csv"
    tours_file = f"{work_dir}/{scenario_config["beam"]["tours_file"]}.csv"
    carriers_file = f"{work_dir}/{scenario_config["beam"]["carriers_file"]}--TrAP.csv"
    emissions_skims_file = f"{work_dir}/{run_config["emissions_skims_file"]}.csv"
    emfac_vmt_file = f"{output_dir}/{area}_emfac_vmt_{scenario}.csv"

    # Reading files
    pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
    ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
    tours = pd.read_csv(tours_file)[["tourId", 'departureTimeInSec']]
    carriers = pd.read_csv(carriers_file)[["tourId", 'vehicleTypeId']]
    emfac_vmt = pd.read_csv(emfac_vmt_file)

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

    print("Calculating VMT...")
    freight_vmt = skims.groupby(['scenario', 'hour', 'beamFuel', 'class'])['vmt'].sum().reset_index().copy()

    print("Creating VMT comparison with EMFAC...")
    emfac_freight_vmt = create_model_vmt_comparison_chart(skims, emfac_vmt, output_dir)

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

    print("Generating plots...")
    # Figure 1: Activity plots
    plot_hourly_activity(tours_types_2018, plot_dir, height_size=6)
    plot_hourly_vmt(freight_vmt, plot_dir, height_size=6)

    # Figure 2: VMT comparison
    plot_multi_pie_emfac_famos_vmt(emfac_freight_vmt, plot_dir)

    # Figure 3: Activity heatmaps
    plot_h3_heatmap(h3_vmt, vmt_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(h3_count, count_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 4: Emissions heatmaps
    plot_h3_heatmap(pm25, pm25_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(nox, nox_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
    plot_h3_heatmap(co2, co2_column, scenario, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)

    # Figure 5: Hourly emissions
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'PM2_5', plot_dir, plot_legend=True, height_size=6,font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=True, height_size=6, font_size=24)

    # Figure 6: Delta emissions heatmaps
    # plot_h3_heatmap(pm25_delta, pm25_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(nox_delta, nox_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)
    # plot_h3_heatmap(co2_delta, co2_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir,
    #                 is_delta=True, remove_outliers=True, in_log_scale=True)

    # Figure 7: Pollution variability
    plot_pollution_variability_by_process_vehicle_types(skims, "PM2_5", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "NOx", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "CO2", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)

    print("Processing completed successfully.")


if __name__ == "__main__":
    main()