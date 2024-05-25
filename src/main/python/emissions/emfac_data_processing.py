from emissions_utils import *

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
    "LHD1",  # Light-Heavy-Duty Trucks (GVWR 850110000 lbs)
    "LHD2",  # Light-Heavy-Duty Trucks (GVWR 1000114000 lbs)
    "T6 Public Class 4",  # Medium-Heavy Duty Public Fleet Truck (GVWR 14001-16000 lbs)
    "T6 Public Class 5",  # Medium-Heavy Duty Public Fleet Truck (GVWR 16001-19500 lbs)
    "T6 Public Class 6",  # Medium-Heavy Duty Public Fleet Truck (GVWR 19501-26000 lbs)
    "T6 Public Class 7",  # Medium-Heavy Duty Public Fleet Truck (GVWR 26001-33000 lbs)
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
    "T7 Public Class 8",  # Heavy-Heavy Duty Public Fleet Truck (GVWR 33001 lbs and over)
    "T7 CAIRP Class 8",  # Heavy-Heavy Duty CA International Registration Plan Truck (GVWR 33001 lbs and over)
    "T7 Single Concrete/Transit Mix Class 8", # Heavy-Heavy Duty Single Unit Concrete/Transit Mix Truck (GVWR 33001 lbs and over)
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

    "T6TS",  # Medium-Heavy Duty Truck
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
emfac_pop_freight_normalized['share_population'] = emfac_pop_freight_normalized['sum_population'] / emfac_pop_freight_normalized['sum_population'].sum()
# Save the filtered and normalized data to a new CSV file
emfac_pop_file = get_regional_emfac_filename(statewide_pop_file, emfac_regions, label="Normalized_")
emfac_pop_freight_normalized.to_csv(emfac_pop_file, index=False)


# ## VMT ##
statewide_vmt_file = model_dir + '/Default_Statewide_2018_Annual_fleet_data_vmt_20240311153419.csv'

emfac_vmt = get_regional_emfac_data(statewide_vmt_file, emfac_regions)
emfac_vmt_freight = emfac_vmt[emfac_vmt["vehicle_class"].isin(freight_vehicle_classes)]
emfac_vmt_freight_normalized = emfac_vmt_freight.groupby(['vehicle_class', 'fuel'])['total_vmt'].sum().reset_index()
emfac_vmt_freight_normalized.rename(columns={'total_vmt': 'sum_vmt'}, inplace=True)
emfac_vmt_freight_normalized['share_vmt'] = emfac_vmt_freight_normalized['sum_vmt'] / emfac_vmt_freight_normalized['sum_vmt'].sum()

emfac_vmt_file = get_regional_emfac_filename(statewide_vmt_file, emfac_regions, label="Normalized_")
emfac_vmt_freight_normalized.to_csv(emfac_vmt_file, index=False)

# ## TRIPS ##
statewide_trips_filename = 'Default_Statewide_2018_Annual_fleet_data_trips_20240311153419.csv'