from emissions_utils import *
pd.set_option('display.max_columns', 20)


# Input
area = "sfbay"
batch = "2024-01-23"
mode_to_filter = "-TRUCK-"
expansion_factor = 1/0.1
source_epsg = "EPSG:26910"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
h3_resolution = 8  # Adjust as needed
run_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")
scenario_2018 = "2018_Baseline"
scenario_2050 = "2050_HOPhighp2"
skims_2018_file = f"{run_dir}/{scenario_2018}_TrAP/0.skimsEmissions.csv.gz"
skims_2050_file = f"{run_dir}/{scenario_2050}_TrAP/0.skimsEmissions.csv.gz"
network_file = f"{run_dir}/network.csv.gz"
plan_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{batch}")
types_2018_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2018-Baseline-TrAP.csv"
types_2050_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2050-HOPhighp2-TrAP.csv"
tours_2018_file = f"{plan_dir}/{scenario_2018}/tours--2018-Baseline.csv"
tours_2050_file = f"{plan_dir}/{scenario_2050}/tours--2050-HOPhighp2.csv"
carriers_2018_file = f"{plan_dir}/{scenario_2018}/carriers--2018-Baseline-TrAP.csv"
carriers_2050_file = f"{plan_dir}/{scenario_2050}/carriers--2050-HOPhighp2-TrAP.csv"

# ### Output ###
plot_dir = f'{run_dir}/_plots'
# ### Main ###

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
    scenario_2018.replace("_", " ")
)
skims_2050 = read_skims_emissions_chunked(
    skims_2050_file,
    types_2050_file,
    mode_to_filter,
    network,
    expansion_factor,
    scenario_2050.replace("_", " ")
)
skims = pd.concat([skims_2018, skims_2050])
fast_df_to_gzip(skims, f'{run_dir}/skims_{scenario_2018}_{scenario_2050}.csv.gz')


# Tours
tours_2018 = pd.read_csv(tours_2018_file)[["tourId", 'departureTimeInSec']]
tours_2050 = pd.read_csv(tours_2050_file)[["tourId", 'departureTimeInSec']]
carriers_2018 = pd.read_csv(carriers_2018_file)[["tourId", 'vehicleTypeId']]
carriers_2050 = pd.read_csv(carriers_2050_file)[["tourId", 'vehicleTypeId']]
types_2018 = pd.read_csv(types_2018_file)[["vehicleTypeId", 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']]
types_2050 = pd.read_csv(types_2050_file)[["vehicleTypeId", 'vehicleCategory', 'primaryFuelType', 'secondaryFuelType']]

tours_types_2018 = pd.merge(tours_2018, pd.merge(carriers_2018, types_2018, on="vehicleTypeId"), on="tourId")
tours_types_2018["scenario"] = scenario_2018
tours_types_2050 = pd.merge(tours_2050, pd.merge(carriers_2050, types_2050, on="vehicleTypeId"), on="tourId")
tours_types_2050["scenario"] = scenario_2050
tours_types = pd.concat([tours_types_2018, tours_types_2050])

plot_hourly_activity(tours_types, plot_dir)




pm25 = process_h3_emissions(df_combined, network_h3_intersection, 'PM2_5')
pm25["PM2_5 (g/m²)"] = pm25["PM2_5"] * 907184.74
# create_h3_histogram(pm25_2018, plot_dir, 'PM2_5 (g/m²)', in_log_scale=True)
create_h3_heatmap(pm25, plot_dir, 'PM2_5 (g/m²)', "2018 Baseline", remove_outliers=True, in_log_scale=True)
create_h3_heatmap(pm25, plot_dir, 'PM2_5 (g/m²)', "2050 HOPhighp2", remove_outliers=True, in_log_scale=True)

pm25_pivot = pm25.pivot(index='h3_cell', columns='scenario', values='PM2_5').reset_index()
pm25_pivot = pm25_pivot.fillna(0)
pm25_pivot['Delta_PM2_5'] = pm25_pivot["2050 HOPhighp2"] - pm25_pivot["2018 Baseline"]
pm25_pivot["ΔPM2_5 (g/m²)"] = pm25_pivot["Delta_PM2_5"] * 907184.74
pm25_pivot["scenario"] = "2050-2018"
create_h3_heatmap(pm25_pivot, plot_dir, 'ΔPM2_5 (g/m²)', "2050-2018", remove_outliers=False, in_log_scale=True)


# Plotting
emissions_by_scenario_hour_class_fuel(df_combined, 'PM2_5', plot_dir)
emissions_by_scenario_hour_class_fuel(df_combined, 'CO', plot_dir)
emissions_by_scenario_hour_class_fuel(df_combined, 'CO2', plot_dir)
emissions_by_scenario_hour_class_fuel(df_combined, 'NOx', plot_dir)

plot_hourly_vmt(df_combined, plot_dir)


print("End.")
