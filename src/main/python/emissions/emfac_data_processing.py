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
emfac_pop = prepare_emfac_emissions_for_mapping(statewide_pop_file, emfac_regions)

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
