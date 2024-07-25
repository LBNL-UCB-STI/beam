from emissions_utils import *
pd.set_option('display.max_columns', 20)





# Input
area = "sfbay"
batch = "2024-01-23"
transport_to_filter = "-TRUCK-"
selected_pollutants = ['PM2_5', 'NOx', 'CO', 'ROG', 'CO2', 'HC']
run_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-runs/{batch}")
skims_2018_file = f"{run_dir}/2018_Baseline_TrAP/0.skimsEmissions.csv.gz"
skims_2050_file = f"{run_dir}/2050_HOPhighp2_TrAP/0.skimsEmissions.csv.gz"
plan_dir = os.path.expanduser(f"~/Workspace/Simulation/{area}/beam-freight/{batch}")
types_2018_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2018-Baseline-TrAP.csv"
types_2050_file = f"{plan_dir}/vehicle-tech/ft-vehicletypes--2050-HOPhighp2-TrAP.csv"

# Output
emissions_by_process_file = f'{run_dir}/plots/emissions_by_process_and_vehicle_type_and_scenarios'

# Main
types_2018 = pd.read_csv(types_2018_file)
types_2050 = pd.read_csv(types_2050_file)
skims_2018 = read_skims_emissions(skims_2018_file, transport_to_filter, selected_pollutants, "2018 Baseline")
skims_2050 = read_skims_emissions(skims_2050_file, transport_to_filter, selected_pollutants, "2050 HOPhighp2")

# Process types data
for types in [types_2018, types_2050]:
    types['fuel'] = types['emfacId'].apply(lambda x: x.split('-')[-1])

# Merge skims data with vehicle types
skims_2018 = pd.merge(skims_2018, types_2018[['vehicleTypeId', 'vehicleClass', 'fuel']], on='vehicleTypeId', how='left')
skims_2050 = pd.merge(skims_2050, types_2050[['vehicleTypeId', 'vehicleClass', 'fuel']], on='vehicleTypeId', how='left')

# Combine the dataframes
df_combined = pd.concat([skims_2018, skims_2050])
# df_combined.rename(columns={'Pollutant': 'pollutant', 'Emissions': 'emissions', 'Scenario': 'scenario'}, inplace=True)
# Group by vehicleClass, fuel, scenario, and process, and sum emissions
grouped_emissions = df_combined.groupby(['scenario', 'vehicleClass', 'fuel', 'emissionsProcess', 'pollutant'])['emissions'].sum().reset_index()
grouped_emissions = grouped_emissions[grouped_emissions["emissionsProcess"] != "IDLEX"]
grouped_emissions['class_fuel'] = grouped_emissions['vehicleClass'] + ' - ' + grouped_emissions['fuel']

# Filter data for PM2_5
emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'PM2_5'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "PM 2.5",
    emissions_by_process_file + "_PM2_5.png")

emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'NOx'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "NOx",
    emissions_by_process_file + "_NOx.png")

emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'CO'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "CO",
    emissions_by_process_file + "_CO.png")

emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'ROG'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "ROG",
    emissions_by_process_file + "_ROG.png")

emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'CO2'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "CO2",
    emissions_by_process_file + "_CO2.png")

emissions_by_process_and_vehicle_type_and_scenarios(
    grouped_emissions[grouped_emissions['pollutant'] == 'HC'],
    ['2018 Baseline', '2050 HOPhighp2'],
    "HC",
    emissions_by_process_file + "_HC.png")


print("End.")
