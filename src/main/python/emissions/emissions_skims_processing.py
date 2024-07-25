from emissions_utils import *
pd.set_option('display.max_columns', 20)


# Input
area = "sfbay"
batch = "2024-01-23"
mode_to_filter = "-TRUCK-"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
run_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")
skims_2018_file = f"{run_dir}/2018_Baseline_TrAP/0.skimsEmissions.csv.gz"
skims_2050_file = f"{run_dir}/2050_HOPhighp2_TrAP/0.skimsEmissions.csv.gz"
network_file = f"{run_dir}/network.csv.gz"
plan_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{batch}")
types_2018_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2018-Baseline-TrAP.csv"
types_2050_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2050-HOPhighp2-TrAP.csv"



# Output
plot_dir = f'{run_dir}/plots'

# Main
skims_2018 = read_skims_emissions(skims_2018_file, types_2018_file, network_file, mode_to_filter, "2018 Baseline")
skims_2050 = read_skims_emissions(skims_2050_file, types_2050_file, network_file, mode_to_filter, "2050 HOPhighp2")
df_combined = pd.concat([skims_2018, skims_2050])
df_filtered = df_combined[df_combined["process"] != "IDLEX"]
emissions_by_scenario_hour_class_fuel(df_filtered, 'PM2_5', f'{run_dir}/plots')



# emissions_by_process_and_vehicle_type_and_scenarios(
#     grouped_emissions[grouped_emissions['pollutant'] == 'HC'],
#     ['2018 Baseline', '2050 HOPhighp2'],
#     "HC",
#     emissions_by_process_file + "_HC.png")


print("End.")
