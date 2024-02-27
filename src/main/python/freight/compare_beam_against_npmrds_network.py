import time
from pathlib import Path

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
# Files
link_stats = pd.read_csv(input_dir + "/BEAM_output/0.linkstats.csv.gz", sep=',')
beam_network = pd.read_csv(input_dir + '/BEAM_output/network.csv.gz', sep=',')
# Input Files
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
regional_npmrds_station = load_or_process_regional_npmrds_station(region_boundary, npmrds_geo_input,
                                                                  regional_npmrds_station_output)

# Drop geometry and get unique TMC
print("Drop geometry and get unique TMC")
regional_npmrds_station_df = regional_npmrds_station.drop(columns='geometry')
regional_npmrds_tmcs = regional_npmrds_station_df['Tmc'].unique()

# Load NPMRDS observations
regional_npmrds_data = load_or_process_regional_npmrds_data(npmrds_data_csv_input, regional_npmrds_tmcs,
                                                            regional_npmrds_data_output)

# Get unique TMC codes with data
print("Get unique TMC codes with data")
regional_npmrds_data_tmcs = regional_npmrds_data['tmc_code'].unique()

# Filter sf_npmrds_station for those TMCs
print("Filter sf_npmrds_station for those TMCs")
regional_npmrds_station_filtered = regional_npmrds_station[
    regional_npmrds_station['Tmc'].isin(regional_npmrds_data_tmcs)]
# regional_npmrds_station_filtered_geojson_path = work_dir + '/SFBay/regional_npmrds_station_filtered.geojson'
# regional_npmrds_station_filtered.to_file(regional_npmrds_station_filtered_geojson_path, driver='GeoJSON')

# Plotting
plot_output_file = output_dir + '/plots/regional_npmrds_station_filtered.png'
if not os.path.isfile(plot_output_file):
    print("Plotting region boundaries and npmrds stations")
    fig, ax = plt.subplots()
    region_boundary.boundary.plot(ax=ax, color='blue')
    regional_npmrds_station_filtered.plot(ax=ax, color='red')
    fig.savefig(plot_output_file, dpi=300)  # Adjust dpi for resolution, if necessary

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
plot_output_file = output_dir + '/plots/beam_npmrds_network_map.png'
if not os.path.isfile(plot_output_file):
    print("Plot BEAM mapped to NPMRDS Network")
    fig, ax = plt.subplots()
    beam_npmrds_network_map.plot(ax=ax, color='blue')
    regional_npmrds_station_filtered.plot(ax=ax, color='red', linewidth=0.5)
    fig.savefig(plot_output_file, dpi=300)

# Running beam npmrds hourly speed mapping
beam_npmrds_hourly_speed = run_beam_npmrds_hourly_speed_mapping(regional_npmrds_data, beam_network, link_stats,
                                                                demand_sample_size)

plot_output_file = output_dir + '/plots/beam_npmrds_average_speed_validation.png'
if not os.path.isfile(plot_output_file):
    fig, ax = plt.subplots()
    sns.lineplot(x='hour', y='speed', hue='scenario', data=beam_npmrds_hourly_speed.reset_index(), errorbar=('ci', 95),
                 ax=ax)
    plt.ylim([0, 70])
    plt.savefig(plot_output_file, dpi=200)

# Running beam npmrds link level hourly speed mapping
print("Running beam npmrds link level hourly speed mapping")
link_stats_dfs = [link_stats]  # Add more for more scenarios
output_label_suffix = "test"
combined_data = npmrds_screeline_validation(regional_npmrds_data, beam_npmrds_network_map, output_dir, 'NPMRDS')
for link_stats in link_stats_dfs:
    beam_data_hourly = beam_screeline_validation(link_stats, beam_npmrds_network_map, output_dir, 'BEAM',
                                                 demand_sample_size, False, True)
    combined_data = pd.concat([combined_data, beam_data_hourly])
combined_data = combined_data.reset_index()

fig, ax = plt.subplots()
sns.lineplot(x='hour', y='avgSpeed', hue='scenario', data=combined_data, errorbar=('ci', 95), ax=ax)
plt.ylim([0, 70])
plt.savefig(output_dir + '/plots/beam_npmrds_link_level_speed_validation_' + output_label_suffix + '.png', dpi=200)
plt.show()

# get the execution time
print('Execution time:', (time.time() - st) / 60.0, 'minutes')
