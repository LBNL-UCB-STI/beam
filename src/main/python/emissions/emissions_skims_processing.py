from emissions_utils import *
pd.set_option('display.max_columns', 20)


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

# Output
plot_dir = f'{run_dir}/_plots'


# ################
# ##### Main #####
# ################

scenario_2018_label = scenario_2018.replace("_", " ")
scenario_2050_label = scenario_2050.replace("_", " ")

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
tours_types_2018["scenario"] = scenario_2018
tours_types_2050 = pd.merge(tours_2050, pd.merge(carriers_2050, types_2050, on="vehicleTypeId"), on="tourId")
tours_types_2050["scenario"] = scenario_2050
famos_tours = pd.concat([tours_types_2018, tours_types_2050])

# FAMOS VMT
# Group by scenario, hour, and fuel_class, sum annualHourlyMVMT
famos_vmt = skims.groupby(['scenario', 'hour', 'beamFuel', 'class'])['vmt'].sum().reset_index().copy()

# EMFAC VMT
emfac_famos_vmt = create_model_vmt_comparison_chart(
    emfac_vmt_file, area, 2050, skims, scenario_2050.replace("_", " "), plot_dir
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
# plot_h3_heatmap(co2, co2_column, scenario_2018_label, plot_dir, is_delta=False, remove_outliers=True, in_log_scale=True)
# Figure 5
plot_hourly_emissions_by_scenario_class_fuel(skims, 'PM2_5', plot_dir, plot_legend=True, height_size=6, font_size=24)
plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO', plot_dir, plot_legend=True, height_size=6, font_size=24)
plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=True, height_size=6, font_size=24)
#plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=False, height_size=11, font_size=30)
#plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=False, height_size=11, font_size=30)
# Figure 6
plot_h3_heatmap(pm25_delta, pm25_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir, is_delta=True, remove_outliers=True, in_log_scale=True)
plot_h3_heatmap(nox_delta, nox_delta_column, "-".join([scenario_2050_label, scenario_2018_label]), plot_dir, is_delta=True, remove_outliers=True, in_log_scale=True)
# Figure 7

plot_pollution_variability_by_process_vehicle_types(skims, "PM2_5", scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollution_variability_by_process_vehicle_types(skims, "NOx", scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollution_variability_by_process_vehicle_types(skims, "CO", scenario_2018_label, plot_dir, height_size=6, font_size=24)
plot_pollution_variability_by_process_vehicle_types(skims, "SOx", scenario_2018_label, plot_dir, height_size=6, font_size=24)

plot_pollutants_by_process(skims, scenario_2018_label, plot_dir, height_size=6, font_size=24)

print("End.")
