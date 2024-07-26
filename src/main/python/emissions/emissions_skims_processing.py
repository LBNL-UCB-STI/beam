from emissions_utils import *
pd.set_option('display.max_columns', 20)


# Input
area = "sfbay"
batch = "2024-01-23"
mode_to_filter = "-TRUCK-"
expansion_factor = 1/0.1
source_epsg = "EPSG:26910"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
h3_resolution = 7  # Adjust as needed
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
# Network
network = load_network(network_file, source_epsg)
network_h3_intersection = generate_h3_intersections(network, h3_resolution, run_dir)
network_h3_intersection.to_csv(f'{run_dir}/network.h3.csv', index=False)

# Skims
skims_2018 = read_skims_emissions(
    skims_2018_file, types_2018_file, mode_to_filter, network, expansion_factor, "2018 Baseline"
)
skims_2050 = read_skims_emissions(
    skims_2050_file, types_2050_file, mode_to_filter, network, expansion_factor, "2050 HOPhighp2"
)
df_combined = pd.concat([skims_2018, skims_2050])
df_combined.to_csv(f'{run_dir}/combined_skims.csv.gz', index=False, compression='gzip')

#
result = process_emissions_h3(df_filtered, network_h3_intersection, 'PM2_5')
create_heatmap_3(result, plot_dir, 'PM2_5')

result = process_emissions_h3(df_filtered, network_h3_intersection, 'CO')
create_heatmap_3(result, plot_dir, 'CO')




# Plotting
emissions_by_scenario_hour_class_fuel(df_filtered, 'PM2_5', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'CO', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'CO2', plot_dir)
emissions_by_scenario_hour_class_fuel(df_filtered, 'NOx', plot_dir)

plot_hourly_vmt(df_filtered, plot_dir)


print("End.")
