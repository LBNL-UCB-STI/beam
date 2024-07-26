from emissions_utils import *
pd.set_option('display.max_columns', 20)


# Input
area = "sfbay"
batch = "2024-01-23"
mode_to_filter = "-TRUCK-"
expansion_factor = 1/0.1
source_epsg = "EPSG:26910"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
run_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")
skims_2018_file = f"{run_dir}/2018_Baseline_TrAP/0.skimsEmissions.csv.gz"
skims_2050_file = f"{run_dir}/2050_HOPhighp2_TrAP/0.skimsEmissions.csv.gz"
network_file = f"{run_dir}/network.csv.gz"
plan_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{batch}")
types_2018_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2018-Baseline-TrAP.csv"
types_2050_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2050-HOPhighp2-TrAP.csv"


# Output
plot_dir = f'{run_dir}/_plots'
# Main
skims_2018 = read_skims_emissions(
    skims_2018_file, types_2018_file, network_file, mode_to_filter, expansion_factor, source_epsg, "2018 Baseline"
)
skims_2050 = read_skims_emissions(
    skims_2050_file, types_2050_file, network_file, mode_to_filter, expansion_factor, source_epsg, "2050 HOPhighp2"
)
df_combined = pd.concat([skims_2018, skims_2050])
df_filtered = df_combined[df_combined["process"] != "IDLEX"]

# Plotting
emissions_by_scenario_hour_class_fuel(df_filtered, 'PM2_5', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'CO', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'CO2', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'NOx', plot_dir)

plot_hourly_vmt(df_filtered, plot_dir)

#
# Main process
resolution = 8  # Adjust as needed

temp = df_filtered.head(1)
"""Create an H3 grid covering the area of the dataframe."""
lats = temp[['fromLocationY', 'toLocationY']].values.flatten()
lons = temp[['fromLocationX', 'toLocationX']].values.flatten()
list(set(h3.polyfill(
    {'type': 'Polygon', 'coordinates': [[[min(lons), min(lats)], [max(lons), min(lats)],
                                         [max(lons), max(lats)], [min(lons), max(lats)]]]},
    resolution
)))

# Assuming df_combined is your dataframe
h3_grid = create_h3_grid(df_filtered, resolution)
segments_df = process_dataframe(df_combined, resolution)
create_heatmap(segments_df, h3_grid)

print("End.")
