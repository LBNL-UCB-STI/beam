import time
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.csv as pv
from pyproj import Transformer
from shapely.geometry import LineString

from beam_emissions_plotting import *
from emfac_emissions_mapping import *

pd.set_option('display.max_columns', 20)


# ################
# #### functions ####
# ################

skims_schema = pa.schema([
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
    ('TOG', pa.float64())
])

def read_skims_emissions_chunked(skims_file, vehicleTypes_file, vehicleTypeId_filter, network, expansion_factor,
                                 scenario_name, chunk_size=1000000):
    start_time = time.time()

    # Process vehicleTypes file
    vehicleTypes = pd.read_csv(vehicleTypes_file)
    vehicleTypes['emfacFuel'] = vehicleTypes['emfacId'].str.split('-').str[-1]
    vehicleTypes['class'] = vehicleTypes['vehicleCategory'].str.replace('Vocational|Tractor', '', regex=True).str.strip()
    vehicleTypes['beamFuel'] = np.where(
        (vehicleTypes['primaryFuelType'].str.lower() == fuel_emfac2beam_map["Elec"]) & vehicleTypes[
            'secondaryFuelType'].notna(),
        'Phe',
        vehicleTypes['primaryFuelType'].str.lower().map(fuel_beam2emfac_map)
    )

    # Initialize an empty list to store the processed chunks
    result_chunks = []

    # Set up the CSV reader with chunking
    csv_reader = pv.open_csv(
        skims_file,
        read_options=pv.ReadOptions(block_size=chunk_size, use_threads=True),
        parse_options=pv.ParseOptions(delimiter=','),
        convert_options=pv.ConvertOptions(column_types=skims_schema)
    )

    # Get total file size for progress bar
    total_size = os.path.getsize(skims_file)

    # Initialize progress bar
    pbar = tqdm(total=total_size, unit='B', unit_scale=True, desc="Processing chunks",
                position=0, leave=True, mininterval=1.0, maxinterval=10.0, miniters=1)

    # Process the skims file in chunks
    for chunk in csv_reader:
        chunk_size = chunk.nbytes

        # Filter the chunk
        mask = pc.match_substring(chunk['vehicleTypeId'], pattern=vehicleTypeId_filter)
        filtered_chunk = chunk.filter(mask)
        # del chunk  # Explicitly remove reference to the original chunk

        # Perform calculations in PyArrow
        observations_expansion = pc.multiply(
            filtered_chunk['observations'], pc.cast(pa.scalar(expansion_factor), pa.float64())
        )

        new_columns = []
        new_fields = []
        for pollutant in pollutant_columns.keys():
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
        # new_schema = filtered_chunk.schema.append(new_columns[::2])
        # new_columns = filtered_chunk.columns + new_columns[1::2]
        new_schema = filtered_chunk.schema
        for field in new_fields:
            new_schema = new_schema.append(field)

        new_columns = filtered_chunk.columns + new_columns
        filtered_chunk = pa.RecordBatch.from_arrays(new_columns, schema=new_schema)

        # Convert to pandas
        df_chunk = filtered_chunk.to_pandas()
        # del filtered_chunk

        # Merge with vehicleTypes and network
        df_chunk_merged = (
            df_chunk
            .merge(vehicleTypes[['vehicleTypeId', 'class', 'beamFuel', 'emfacFuel', 'emfacId']], on='vehicleTypeId', how='left')
            .merge(network[['linkId', 'linkLength']], on='linkId', how='left')
        )
        # del df_chunk

        # Calculate annualHourlyMVMT
        df_chunk_merged['vmt'] = (df_chunk_merged['linkLength'] * 6.21371192e-4) * observations_expansion

        # Rename column
        df_chunk_merged.rename(columns={'emissionsProcess': 'process'}, inplace=True)

        # Melt the dataframe
        id_vars = ['hour', 'linkId', 'tazId', 'emfacId', 'class', 'beamFuel', 'emfacFuel', 'process', 'kwh', 'vmt', 'vht']
        value_vars = [f'scaled_{pollutant}' for pollutant in pollutant_columns.keys()]
        melted_chunk = df_chunk_merged.melt(
            id_vars=id_vars,
            value_vars=value_vars,
            var_name='pollutant',
            value_name='rate'
        )
        # del df_chunk_merged
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

def create_model_vmt_comparison_chart(emfac_vmt_file, emfac_area, emfac_scenario, skims_data, famos_scenario, output_dir):
    df = pd.read_csv(emfac_vmt_file)
    _, ft_emfac_class_map = create_vehicle_class_mapping(df["vehicle_class"].unique())
    filtered_df = df[
        (df['calendar_year'] == emfac_scenario) &
        (df['sub_area'].str.contains(fr'\({region_to_carb_area[emfac_area]}\)')) &
        (df['vehicle_class'].map(ft_emfac_class_map))].copy()
    filtered_df["class"] = df['vehicle_class'].map(ft_emfac_class_map).map(
        {
            'Class 4-6 Vocational': 'Class456',
            'Class 7&8 Vocational': 'Class78',
            'Class 7&8 Tractor': 'Class78'
        }
    )
    filtered_df["fuel_class"] = filtered_df["fuel"] + "-" + filtered_df["class"]
    emfac_vmt = filtered_df.groupby(["fuel_class"])["total_vmt"].sum().reset_index()
    emfac_vmt.rename(columns={'total_vmt': 'mvmt'}, inplace=True)
    emfac_vmt["model"] = "emfac"
    famos_vmt = skims_data[skims_data["scenario"] == famos_scenario].groupby(
        ["class", "beamFuel"]
    )["vmt"].sum().reset_index()
    famos_vmt["fuel_class"] = famos_vmt["beamFuel"] + "-" + famos_vmt["class"]
    famos_vmt = famos_vmt[["fuel_class", "vmt"]].copy()
    famos_vmt.rename(columns={'vmt': 'mvmt'}, inplace=True)
    famos_vmt["model"] = "famos"
    emfac_famos_vmt = pd.concat([emfac_vmt, famos_vmt], axis=0)
    emfac_famos_vmt.to_csv(f"{output_dir}/emfac_famos_vmt_by_fuel_class.csv")
    return emfac_famos_vmt

def load_network(network_file, source_epsg):
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

# ################
# #### Header ####
# ################

# Input
area = "sfbay"
batch = "2024-01-23"
mode_to_filter = "-TRUCK-"
expansion_factor = 1/0.1
source_epsg = "EPSG:26910"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
h3_resolution = 8  # Adjust as needed
emfac_vmt_file = os.path.expanduser(f"~/Workspace/Models/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_vmt_20240612233346.csv")
run_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")
scenario_2018 = "2018_Baseline"
scenario_2050 = "2050_Refhighp6"
skims_2018_file = f"{run_dir}/{scenario_2018}/0.skimsEmissions.csv.gz"
skims_2050_file = f"{run_dir}/{scenario_2050}/0.skimsEmissions.csv.gz"
network_file = f"{run_dir}/network.csv.gz"
plan_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{batch}")
types_2018_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--{scenario_2018.replace('_', '-')}-TrAP.csv"
types_2050_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--{scenario_2050.replace('_', '-')}-TrAP.csv"
tours_2018_file = f"{plan_dir}/{scenario_2018}/tours--{scenario_2018.replace('_', '-')}.csv"
tours_2050_file = f"{plan_dir}/{scenario_2050}/tours--{scenario_2050.replace('_', '-')}.csv"
carriers_2018_file = f"{plan_dir}/{scenario_2018}/carriers--{scenario_2018.replace('_', '-')}-TrAP.csv"
carriers_2050_file = f"{plan_dir}/{scenario_2050}/carriers--{scenario_2050.replace('_', '-')}-TrAP.csv"

# Output
plot_dir = f'{run_dir}/_plots'
Path(plot_dir).mkdir(parents=True, exist_ok=True)

# ################
# ##### Main #####
# ################

scenario_2018_label = scenario_2018.replace("_", " ")
scenario_2050_label = scenario_2050.replace("_", " ").replace("HOPhighp2", "HAVF")

# Network
network = load_network(network_file, source_epsg)
network_h3_intersection = generate_h3_intersections(network, h3_resolution, run_dir)
network_h3_intersection.to_csv(f'{run_dir}/network.h3.csv', index=False)

# Skims
skims_2018 = read_skims_emissions_chunked(
    skims_2018_file,
    types_2018_file,
    mode_to_filter,
    network,
    expansion_factor,
    scenario_2018_label
)
skims_2050 = read_skims_emissions_chunked(
    skims_2050_file,
    types_2050_file,
    mode_to_filter,
    network,
    expansion_factor,
    scenario_2050_label
)
skims = pd.concat([skims_2018, skims_2050])
print(f"Read {len(skims)} rows of skims")
# fast_df_to_gzip(skims, f'{run_dir}/skims_{scenario_2018}_{scenario_2050}.csv.gz')

# FAMOS Tours
tours_2018 = pd.read_csv(tours_2018_file)[["tourId", 'departureTimeInSec']]
tours_2050 = pd.read_csv(tours_2050_file)[["tourId", 'departureTimeInSec']]
carriers_2018 = pd.read_csv(carriers_2018_file)[["tourId", 'vehicleTypeId']]
carriers_2050 = pd.read_csv(carriers_2050_file)[["tourId", 'vehicleTypeId']]
types_2018 = pd.read_csv(types_2018_file)[["vehicleTypeId", 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']]
types_2050 = pd.read_csv(types_2050_file)[["vehicleTypeId", 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']]

tours_types_2018 = pd.merge(tours_2018, pd.merge(carriers_2018, types_2018, on="vehicleTypeId"), on="tourId")
tours_types_2018["scenario"] = scenario_2018_label
tours_types_2050 = pd.merge(tours_2050, pd.merge(carriers_2050, types_2050, on="vehicleTypeId"), on="tourId")
tours_types_2050["scenario"] = scenario_2050_label
famos_tours = pd.concat([tours_types_2018, tours_types_2050])

# FAMOS VMT
# Group by scenario, hour, and fuel_class, sum annualHourlyMVMT
famos_vmt = skims.groupby(['scenario', 'hour', 'beamFuel', 'class'])['vmt'].sum().reset_index().copy()

# EMFAC VMT
emfac_famos_vmt = create_model_vmt_comparison_chart(
    emfac_vmt_file, area, 2050, skims, scenario_2050_label, plot_dir
)

# Processes
driving_process_activity = skims[
    (skims["process"].isin(["RUNEX", "PMBW", "PMTW", "RUNLOSS"])) &
    (skims["vht"] > 0)
].groupby(["scenario", "linkId"])["vmt"].sum().reset_index(name="vmt")
h3_vmt = process_h3_data(network_h3_intersection, driving_process_activity, "vmt")
vmt_column = "Weighted VMT from driving activities"
h3_vmt.rename(columns={"weighted_vmt": vmt_column}, inplace=True)

parking_process_activity = skims[
    (skims["process"].isin(["STREX", "DIURN", "HOTSOAK", "RUNLOSS", "IDLEX"])) &
    (skims["vht"] == 0)
].groupby(["scenario", "linkId"]).size().reset_index(name='count')
h3_count = process_h3_data(network_h3_intersection, parking_process_activity, "count")
count_column = "Weighted count of parking activities"
h3_count.rename(columns={"weighted_count": count_column}, inplace=True)

# Emissions
pm25 = process_h3_emissions(skims, network_h3_intersection, 'PM2_5')
nox = process_h3_emissions(skims, network_h3_intersection, 'NOx')
co = process_h3_emissions(skims, network_h3_intersection, 'CO')
co2 = process_h3_emissions(skims, network_h3_intersection, 'CO2')
#
pm25_column = "PM2_5 in grams per square meter"
pm25[pm25_column] = pm25["PM2_5"] * 1e6  # from metric ton to gram
#
nox_column = "NOx in grams per square meter"
nox[nox_column] = nox["NOx"] * 1e6  # from metric ton to gram
#
co_column = "CO in grams per square meter"
co[co_column] = co["CO"] * 1e6  # from metric ton to gram
#
co2_column = "CO2 in grams per square meter"
co2[co2_column] = co2["CO2"] * 1e6  # from metric ton to gram

# Delta Emissions
pm25_delta = pm25.pivot(index='h3_cell', columns='scenario', values='PM2_5').reset_index()
pm25_delta = pm25_delta.fillna(0)
pm25_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
pm25_delta['Delta_PM2_5'] = pm25_delta[scenario_2050_label] - pm25_delta[scenario_2018_label]
pm25_delta_column = "Delta PM2_5 in grams per square meter"
pm25_delta[pm25_delta_column] = pm25_delta["Delta_PM2_5"] * 1e6  # from metric ton to gram
#
nox_delta = nox.pivot(index='h3_cell', columns='scenario', values='NOx').reset_index()
nox_delta = nox_delta.fillna(0)
nox_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
nox_delta['Delta_NOx'] = nox_delta[scenario_2050_label] - nox_delta[scenario_2018_label]
nox_delta_column = "Delta NOx in grams per square meter"
nox_delta[nox_delta_column] = nox_delta["Delta_NOx"] * 1e6  # from metric ton to gram
#
co2_delta = co2.pivot(index='h3_cell', columns='scenario', values='CO2').reset_index()
co2_delta = co2_delta.fillna(0)
co2_delta["scenario"] = "-".join([scenario_2050_label, scenario_2018_label])
co2_delta['Delta_CO2'] = co2_delta[scenario_2050_label] - co2_delta[scenario_2018_label]
co2_delta_column = "Delta CO2 in grams per square meter"
co2_delta[co2_delta_column] = co2_delta["Delta_CO2"] * 1e6  # from metric ton to gram



# ################
# ### Plotting ###
# ################
# Figure 1
plot_hourly_activity(famos_tours, plot_dir, height_size=6)
plot_hourly_vmt(famos_vmt, plot_dir, height_size=6)
# Figure 2
plot_multi_pie_emfac_famos_vmt(emfac_famos_vmt, plot_dir)
# Figure 3
plot_h3_heatmap(h3_vmt, vmt_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(h3_count, count_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
# Figure 4
plot_h3_heatmap(pm25, pm25_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(nox, nox_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
# plot_h3_heatmap(co, co_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(co2, co2_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
# Figure 5
plot_hourly_emissions_by_scenario_class_fuel(skims, 'PM2_5', plot_dir, plot_legend=True, height_size=6, font_size=24)
plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
#plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO', plot_dir, plot_legend=True, height_size=6, font_size=24)
#plot_hourly_emissions_by_scenario_class_fuel(skims, 'SOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
#plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=False, height_size=11, font_size=30)
plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=True, height_size=6, font_size=24)
# Figure 6
plot_h3_heatmap(pm25_delta, pm25_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir, is_delta=True, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(nox_delta, nox_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir, is_delta=True, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(co2_delta, co2_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir, is_delta=True, remove_outliers=True, in_log_scale=True)
# Figure 7

plot_pollution_variability_by_process_vehicle_types(skims, "PM2_5", scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollution_variability_by_process_vehicle_types(skims, "NOx", scenario_2018_label, plot_dir, height_size=6, font_size=24)
#plot_pollution_variability_by_process_vehicle_types(skims, "CO", scenario_2018_label, plot_dir, height_size=6, font_size=24)
#plot_pollution_variability_by_process_vehicle_types(skims, "SOx", scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollution_variability_by_process_vehicle_types(skims, "CO2", scenario_2018_label, plot_dir, height_size=6, font_size=24)

plot_pollutants_by_process(skims, scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollutants_by_process(skims, scenario_2050_label, plot_dir, height_size=6, font_size=24)

print("End.")
