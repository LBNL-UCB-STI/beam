import time
from pathlib import Path

import seaborn as sns

from validation_utils import *

# ## Input Initializing ##
work_dir = os.path.expanduser("~/Workspace/Data/Scenarios/")
output_dir = work_dir + '/SFBay/output'
input_dir = work_dir + '/SFBay'
projected_crs_epsg = 26910
demand_sample_size = 0.1
# ### You can rerun only network matching and change distance buffer to compare results
rerun_network_matching = False
distance_buffer = 20  # meters
# ###
# BEAM Files
link_stats_dfs = read_link_stats([input_dir + "/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/10.linkstats.csv.gz"],
                                 ["BEAM_2024"])
beam_network = pd.read_csv(input_dir + '/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/network.csv.gz', sep=',')
assume_daylight_savings = True
# Input Files
npmrds_label = "NPMRDS_2018"
npmrds_geo_input = input_dir + "/NPMRDS_data/California.shp"
region_boundary_geo_input = input_dir + '/NPMRDS_data/SF_counties.geojson'
npmrds_data_csv_input = input_dir + '/NPMRDS_data/al_ca_oct2018_1hr_trucks_pax.csv'
# Output Files
regional_npmrds_station_output = output_dir + '/regional_npmrds_station.geojson'
regional_npmrds_data_output = output_dir + '/regional_npmrds_data.csv'
beam_network_geo_output = output_dir + '/beam_network_by_county.geojson'
beam_network_filtered_geo_output = output_dir + '/beam_network_by_county_filtered.geojson'
beam_npmrds_network_map_geo_output = output_dir + '/beam_npmrds_network_map_' + str(distance_buffer) + 'm.geojson'
# #########################

# Checking execution time
st = time.time()

Path(output_dir + '/plots/').mkdir(parents=True, exist_ok=True)

# Load regional map boundaries
print("Load regional map boundaries")
region_boundary = gpd.read_file(region_boundary_geo_input).to_crs(epsg=4326)

# Load or process regional npmrds station
regional_npmrds_station = load_or_process_regional_npmrds_station(region_boundary, npmrds_geo_input, npmrds_label,
                                                                  regional_npmrds_station_output)

# Drop geometry and get unique TMC
print("Drop geometry and get unique TMC")
regional_npmrds_station_df = regional_npmrds_station.drop(columns='geometry')
regional_npmrds_tmcs = regional_npmrds_station_df['Tmc'].unique()

# Load NPMRDS observations
regional_npmrds_data = load_or_process_regional_npmrds_data(npmrds_data_csv_input, regional_npmrds_tmcs, npmrds_label,
                                                            regional_npmrds_data_output)

# Get unique TMC codes with data
print("Get unique TMC codes with data")
regional_npmrds_data_tmcs = regional_npmrds_data['tmc_code'].unique()

# Filter sf_npmrds_station for those TMCs
print("Filter sf_npmrds_station for those TMCs")
regional_npmrds_station_filtered = regional_npmrds_station[
    regional_npmrds_station['Tmc'].isin(regional_npmrds_data_tmcs)]

# Plotting
print("Plotting region boundaries and npmrds stations: " + npmrds_label)
fig, ax = plt.subplots()
region_boundary.boundary.plot(ax=ax, color='blue')
regional_npmrds_station_filtered.plot(ax=ax, color='red')
ax.set_title("Region Boundaries and " + npmrds_label + " Stations")
fig.savefig(output_dir + '/plots/regional_npmrds_station_filtered.png', dpi=300)  # Adjust dpi for resolution

# ## Load or filter beam network roadway and car link modes
beam_network_with_cars = None
if not os.path.isfile(beam_network_geo_output):
    beam_network_by_county = load_beam_network_to_geojson(region_boundary,
                                                          beam_network,
                                                          beam_network_geo_output,
                                                          projected_crs_epsg)

    beam_network_with_cars = filter_beam_network(beam_network_by_county,
                                                 beam_network_filtered_geo_output)
else:
    beam_network_with_cars = gpd.read_file(beam_network_filtered_geo_output)

# ## Find BEAM links close to NPMRDS TMCs ##
beam_npmrds_network_map = None
if not os.path.isfile(beam_npmrds_network_map_geo_output) or rerun_network_matching:
    beam_npmrds_network_map_proj = map_nearest_links(beam_network_with_cars,
                                                     regional_npmrds_station_filtered,
                                                     distance_buffer,
                                                     projected_crs_epsg)
    beam_npmrds_network_map_proj = beam_npmrds_network_map_proj.set_crs('EPSG:' + str(projected_crs_epsg),
                                                                        allow_override=True)
    beam_npmrds_network_map = beam_npmrds_network_map_proj.to_crs(epsg=4326)
    beam_npmrds_network_map.to_file(beam_npmrds_network_map_geo_output, driver='GeoJSON')
else:
    print("Loading beam network mapped with npmrds")
    beam_npmrds_network_map = gpd.read_file(beam_npmrds_network_map_geo_output)

# ## Plot BEAM + NPMRDS Network
print("Plot BEAM mapped to NPMRDS Network: " + npmrds_label)
fig, ax = plt.subplots()
beam_npmrds_network_map.plot(ax=ax, color='blue')
regional_npmrds_station_filtered.plot(ax=ax, color='red', linewidth=0.5)
ax.set_title("BEAM Network and " + npmrds_label + " Station Mapped")
fig.savefig(output_dir + '/plots/beam_npmrds_network_map.png', dpi=300)

npmrds_hourly_link_speeds_output_file = output_dir + '/plots/npmrds_hourly_link_speeds.csv'
npmrds_hourly_link_speeds = None
if not os.path.isfile(npmrds_hourly_link_speeds_output_file):
    npmrds_hourly_link_speeds = agg_npmrds_to_hourly_link_speeds(regional_npmrds_data,
                                                                 beam_npmrds_network_map.loc[:, 'Tmc'].unique())
    npmrds_hourly_link_speeds.to_csv(npmrds_hourly_link_speeds_output_file, index=False)
else:
    npmrds_hourly_link_speeds = pd.read_csv(npmrds_hourly_link_speeds_output_file, sep=',')

# Running beam npmrds hourly speed mapping
road_category = ['motorway', 'motorway_link']
combined_data = None
for link_stats in link_stats_dfs:
    beam_npmrds_hourly_speed = run_beam_npmrds_hourly_speed_mapping(npmrds_hourly_link_speeds, beam_network, link_stats,
                                                                    demand_sample_size, road_category,
                                                                    assume_daylight_savings).sort_values(
        by='scenario').reset_index()
    combined_data = pd.concat([combined_data, beam_npmrds_hourly_speed])
fig, ax = plt.subplots()
sns.lineplot(x='hour', y='speed', hue='scenario', data=combined_data, errorbar=('ci', 95), ax=ax)
plt.ylim([0, 70])
plt.title("Network-level Speed Validation")
plt.savefig(output_dir + '/plots/beam_npmrds_average_speed_validation.png', dpi=200)
plt.clf()

# Running beam npmrds link level hourly speed mapping
print("Running beam npmrds link level hourly speed mapping")
combined_data = npmrds_hourly_link_speeds
for link_stats in link_stats_dfs:
    beam_data_hourly = agg_beam_to_hourly_link_speeds(link_stats, beam_npmrds_network_map, demand_sample_size,
                                                      assume_daylight_savings).sort_values(by='scenario')
    combined_data = pd.concat([combined_data, beam_data_hourly])
combined_data = combined_data.reset_index()

fig, ax = plt.subplots()
sns.lineplot(x='hour', y='speed', hue='scenario', data=combined_data, errorbar=('ci', 95), ax=ax)
plt.ylim([0, 70])
plt.title("Link-level Speed Validation")
plt.savefig(output_dir + '/plots/beam_npmrds_link_level_speed_validation.png', dpi=200)
plt.show()

# agg_hourly_link_stats_by_road_class(beam_npmrds_hourly_speed, link_stats, beam_npmrds_network_map)

# get the execution time
print('Execution time:', (time.time() - st) / 60.0, 'minutes')
