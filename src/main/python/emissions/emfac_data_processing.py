from emissions_utils import *
import geopandas as gpd
import matplotlib.pyplot as plt

emfac_regions = ["SF"]
model_dir = os.path.abspath(os.path.expanduser('~/Workspace/Models/emfac/2018'))
work_dir = os.path.abspath(os.path.expanduser("~/Workspace/Simulation/sfbay"))
run_dir = work_dir + "/beam-freight/2024-01-23/Baseline"


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
statewide_trips_filename = model_dir + '/Default_Statewide_2018_Annual_fleet_data_trips_20240311153419.csv'

###########################################
# ## EMISSIONS RATES ##
# pd.set_option('display.max_columns', 20)
###########################################
regional_emfac_data_file = model_dir + '/imputed_MTC_emission_rate_agg_NH3_added.csv'


###########################################
# ## FRISM PLANS ##
###########################################
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
