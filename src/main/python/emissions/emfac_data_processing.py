from emissions_utils import *
from joblib import Parallel, delayed
import geopandas as gpd
import matplotlib.pyplot as plt

passenger_veh_classes = [
    "LDA",  # Passenger Cars
    "LDT1",  # Light-Duty Trucks (GVWR* <6000 lbs and ETW** <= 3750 lbs)
    "LDT2",  # Light-Duty Trucks (GVWR <6000 lbs and ETW 3751-5750 lbs)
    "MDV",  # Medium-Duty Trucks (GVWR 5751-8500 lbs)
    "MCY",  # Motorcycles
]
transit_veh_classes = [
    "SBUS",  # School Buses
    "UBUS",  # Urban Buses
    "Motor Coach",  # Motor Coach
    "OBUS",  # Other Buses
    "All Other Buses"  # All Other Buses
]
freight_vehicle_classes = [
    "LHD1",  # Light-Heavy-Duty Trucks (GVWR 8501-10000 lbs)
    "LHD2",  # Light-Heavy-Duty Trucks (GVWR 10001-14000 lbs)

    "T6 Instate Tractor Class 6",  # Medium-Heavy Duty Tractor Truck (GVWR 19501-26000 lbs)
    "T6 Instate Delivery Class 4",  # Medium-Heavy Duty Delivery Truck (GVWR 14001-16000 lbs)
    "T6 Instate Delivery Class 5",  # Medium-Heavy Duty Delivery Truck (GVWR 16001-19500 lbs)
    "T6 Instate Delivery Class 6",  # Medium-Heavy Duty Delivery Truck (GVWR 19501-26000 lbs)
    "T6 Instate Other Class 4",  # Medium-Heavy Duty Other Truck (GVWR 14001-16000 lbs)
    "T6 Instate Other Class 5",  # Medium-Heavy Duty Other Truck (GVWR 16001-19500 lbs)
    "T6 Instate Other Class 6",  # Medium-Heavy Duty Other Truck (GVWR 19501-26000 lbs)
    "T6 Instate Tractor Class 7",  # Medium-Heavy Duty Tractor Truck (GVWR 26001-33000 lbs)
    "T6 Instate Delivery Class 7",  # Medium-Heavy Duty Delivery Truck (GVWR 26001-33000 lbs)
    "T6 Instate Other Class 7",  # Medium-Heavy Duty Other Truck (GVWR 26001-33000 lbs)

    "T6 OOS Class 4",  # Medium-Heavy Duty Out-of-state Truck (GVWR 14001-16000 lbs)
    "T6 OOS Class 5",  # Medium-Heavy Duty Out-of-state Truck (GVWR 16001-19500 lbs)
    "T6 OOS Class 6",  # Medium-Heavy Duty Out-of-state Truck (GVWR 19501-26000 lbs)
    "T6 OOS Class 7",  # Medium-Heavy Duty Out-of-state Truck (GVWR 26001-33000 lbs)

    "T6 CAIRP Class 4",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1400116000 lbs)
    "T6 CAIRP Class 5",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1600119500 lbs)
    "T6 CAIRP Class 6",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 1950126000 lbs)
    "T6 CAIRP Class 7",  # Medium-Heavy Duty CA International Registration Plan Truck (GVWR 2600133000 lbs)
    "T7 CAIRP Class 8",  # Heavy-Heavy Duty CA International Registration Plan Truck (GVWR 33001 lbs and over)
    "T7 Single Concrete/Transit Mix Class 8",
    # Heavy-Heavy Duty Single Unit Concrete/Transit Mix Truck (GVWR 33001 lbs and over)
    "T7 Single Dump Class 8",  # Heavy-Heavy Duty Single Unit Dump Truck (GVWR 33001 lbs and over)
    "T7 Single Other Class 8",  # Heavy-Heavy Duty Single Unit Other Truck (GVWR 33001 lbs and over)
    "T7 NNOOS Class 8",  # Heavy-Heavy Duty Non-Neighboring Outof-state Truck (GVWR 33001 lbs and over)
    "T7 NOOS Class 8",  # Heavy-Heavy Duty Neighboring Out-ofstate Truck (GVWR 33001 lbs and over)
    "T7 Tractor Class 8",  # Heavy-Heavy Duty Tractor Truck (GVWR 33001 lbs and over)
    "T7IS",  # Heavy-Heavy Duty Truck
]
other_veh_classes = [
    "MH",  # Motor Homes
    "T6 Utility Class 5",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Utility Class 6",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Utility Class 7",  # Medium-Heavy Duty Utility Fleet Truck (GVWR 26001-33000 lbs)

    ## used by public entities such as municipalities, state governments, and other public agencies
    "T6 Public Class 4",  # Medium-Heavy Duty Public Fleet Truck (GVWR 14001-16000 lbs)
    "T6 Public Class 5",  # Medium-Heavy Duty Public Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Public Class 6",  # Medium-Heavy Duty Public Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Public Class 7",  # Medium-Heavy Duty Public Fleet Truck (GVWR 26001-33000 lbs)
    "T7 Public Class 8",  # Heavy-Heavy Duty Public Fleet Truck (GVWR 33001 lbs and over)

    "T6TS",
    # Medium-Heavy Duty Truck; "Transit/Specialized," these trucks are designed for specific types of operations
    "T7 Utility Class 8",  # Heavy-Heavy Duty Utility Fleet Truck (GVWR 33001 lbs and over)
    "T7 Other Port Class 8",  # Heavy-Heavy Duty Drayage Truck at Other Facilities (GVWR 33001 lbs and over)
    "T7 POAK Class 8",  # Heavy-Heavy Duty Drayage Truck in Bay Area (GVWR 33001 lbs and ove
    "T7 POLA Class 8",  # Heavy-Heavy Duty Drayage Truck near South Coast (GVWR 33001 lbs and over)
    "T7 SWCV Class 8",  # Heavy-Heavy Duty Solid Waste Collection Truck (GVWR 33001 lbs and over)
    "PTO",  # Power Take Off
]

model_dir = '~/Workspace/Models/emfac/2018'
emfac_regions = ["SF"]

# ## Population ##
statewide_pop_file = model_dir + '/Default_Statewide_2018_Annual_fleet_data_population_20240311153419.csv'

# Load the dataset from the uploaded CSV file
emfac_pop = get_regional_emfac_data(statewide_pop_file, emfac_regions)
emfac_pop_freight = emfac_pop[emfac_pop["vehicle_class"].isin(freight_vehicle_classes)]
# Group by 'vehicle_class' and 'fuel', and calculate the sum of 'population'
emfac_pop_freight_normalized = emfac_pop_freight.groupby(['vehicle_class', 'fuel'])['population'].sum().reset_index()
emfac_pop_freight_normalized.rename(columns={'population': 'sum_population'}, inplace=True)
# Calculate the share of 'sum_population' for each group
emfac_pop_freight_normalized['share_population'] = emfac_pop_freight_normalized['sum_population'] / \
                                                   emfac_pop_freight_normalized['sum_population'].sum()
# Save the filtered and normalized data to a new CSV file
emfac_pop_file = get_regional_emfac_filename(statewide_pop_file, emfac_regions, label="Normalized_")
emfac_pop_freight_normalized.to_csv(emfac_pop_file, index=False)

# ## VMT ##
statewide_vmt_file = model_dir + '/Default_Statewide_2018_Annual_fleet_data_vmt_20240311153419.csv'

emfac_vmt = get_regional_emfac_data(statewide_vmt_file, emfac_regions)
emfac_vmt_freight = emfac_vmt[emfac_vmt["vehicle_class"].isin(freight_vehicle_classes)]
emfac_vmt_freight_normalized = emfac_vmt_freight.groupby(['vehicle_class', 'fuel'])['total_vmt'].sum().reset_index()
emfac_vmt_freight_normalized.rename(columns={'total_vmt': 'sum_vmt'}, inplace=True)
emfac_vmt_freight_normalized['share_vmt'] = emfac_vmt_freight_normalized['sum_vmt'] / emfac_vmt_freight_normalized[
    'sum_vmt'].sum()

emfac_vmt_file = get_regional_emfac_filename(statewide_vmt_file, emfac_regions, label="Normalized_")
emfac_vmt_freight_normalized.to_csv(emfac_vmt_file, index=False)

# ## TRIPS ##
statewide_trips_filename = '/Default_Statewide_2018_Annual_fleet_data_trips_20240311153419.csv'

# ## EMISSIONS RATES ##
# pd.set_option('display.max_columns', 20)
statewide_emissions_rates_file = model_dir + '/imputed_MTC_emission_rate_agg_NH3_added.csv'
emissions_rates, emissions_file_path = get_regional_emfac_data(statewide_emissions_rates_file, emfac_regions)
df = pd.DataFrame(emissions_rates)
df_filtered = df[
    (df["calendar_year"] == 2018) &
    (df["season_month"] == "Annual") &
    ((df["relative_humidity"] == 40) | (df["relative_humidity"].isna())) &
    ((df["temperature"] == 65) | (df["temperature"].isna()))
].drop(['calendar_year', 'season_month', 'relative_humidity', 'temperature'], axis=1)


# Assuming emissions_rates is already loaded into a DataFrame `df`
group_by_cols = ["sub_area", "vehicle_class", "fuel"]
df_unique = df_filtered[group_by_cols].drop_duplicates().reset_index(drop=True)

# Parallel processing
df_output_list = Parallel(n_jobs=-1)(delayed(process_rates_group)(df_filtered, row) for index, row in df_unique.iterrows())

# Concatenate all collected DataFrames at once
df_output = pd.concat(df_output_list, ignore_index=True)

# Pivot table to spread pollutants into columns
pivot_df = df_output.pivot_table(index=["vehicle_class", "fuel", 'speed_mph_float_bins', 'time_minutes_float_bins', 'sub_area', 'process'],
                              columns='pollutant', values='emission_rate', aggfunc='first', fill_value=0).reset_index()

pivot_df = pivot_df.rename(columns=pollutant_columns)
# Add missing columns with default values
for col in pollutant_columns.values():
    if col not in pivot_df.columns:
        pivot_df[col] = 0.0


geojson_file = '~/Workspace/Simulation/sfbay/geo/sfbay_tazs_epsg26910.geojson'  # Update this path to your GeoJSON file
geo_df = gpd.read_file(geojson_file)

pivot_df['sub_area'] = pivot_df['sub_area'].astype(str)
pivot_df['county'] = pivot_df['sub_area'].str.extract(r'^([^()]+)')
pivot_df['county'] = pivot_df['county'].str.strip()
rates_by_taz = pd.merge(geo_df[['taz1454', 'county']], pivot_df, on="county", how='left')
rates_by_taz.rename(columns={'taz1454': 'taz'}, inplace=True)
emissions_rates_for_beam = rates_by_taz.drop(["county", "sub_area"], axis=1)


#sub_area_counts = pivot_df.groupby('sub_area').size().reset_index(name='count')
#sub_area_counts["count"].mean()*len(geo_df)
#len(emissions_rates_for_beam)



# # ## FRISM PLANS ##
# freight_carriers = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/freight-carriers.csv")
# freight_payloads = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/freight-payload-plans.csv")
# freight_tours = pd.read_csv("~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/freight-tours.csv")
#
# # Plot the histogram for the 'Values' column
# freight_payloads['sequenceRank'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of sequenceRank')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_sequenceRank.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
#
# freight_payloads['operationDurationInSec'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of operationDurationInSec')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_operationDurationInSec.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
# freight_payloads['weightInKg'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of weightInKg')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_weightInKg.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
# freight_payloads['arrivalTimeWindowInSecUpper'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of arrivalTimeWindowInSecUpper')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_arrivalTimeWindowInSecUpper.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
# freight_payloads['estimatedTimeOfArrivalInSec'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of estimatedTimeOfArrivalInSec')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_estimatedTimeOfArrivalInSec.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
# freight_payloads['arrivalTimeWindowInSecLower'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of arrivalTimeWindowInSecUpper')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_arrivalTimeWindowInSecLower.png'), dpi=300, bbox_inches='tight')
# plt.clf()
#
# freight_tours['departureTimeInSec'].plot(kind='hist', bins=20, edgecolor='black')
# plt.title('Histogram of departureTimeInSec')
# plt.xlabel('Value')
# plt.ylabel('Frequency')
# plt.savefig(os.path.expanduser('~/Workspace/Data/FREIGHT/sfbay/beam_freight/scenarios-23Jan2024/Base/histogram_departureTimeInSec.png'), dpi=300, bbox_inches='tight')
# plt.clf()
