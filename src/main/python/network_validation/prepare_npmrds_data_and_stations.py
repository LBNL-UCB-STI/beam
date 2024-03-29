import os
from pathlib import Path
from validation_utils import *

# In Mac, you might need to cd to folder '/Applications/Python {version}'
# and run ./Install\ Certificates.command
work_dir = os.path.expanduser("~/Workspace/Data/Scenarios")
# To prepare data for a new study area, make sure to change the following configuration variables
# Example of preparing SFBay data (9 counties + Santa Cruz and Yolo)
# ## study_area = "sfbay"
# ## year = 2018
# ## state = 'CA'
# ## fips_code = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']
# ## npmrds_geo_path_path = work_dir + "/sfbay/input/sfbay_counties.geojson"
study_area = "sfbay"
year = 2018
state = 'CA'
fips_code = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']
npmrds_counties_geo_path_output = work_dir + "/sfbay/input/sfbay_counties.geojson"
npmrds_cbgs_geo_path_output = work_dir + "/sfbay/input/sfbay_cbgs.geojson"
# 4
project_dir = work_dir + '/' + study_area
input_dir = project_dir + '/input'
output_dir = project_dir + '/output'
plots_dir = output_dir + '/plots'
Path(input_dir).mkdir(parents=True, exist_ok=True)
Path(output_dir).mkdir(parents=True, exist_ok=True)
Path(plots_dir).mkdir(parents=True, exist_ok=True)
#
npmrds_geo = work_dir + "/sfbay/validation_data/NPMRDS/California.shp"
npmrds_data_csv = work_dir + '/sfbay/validation_data/NPMRDS/al_ca_oct2018_1hr_trucks_pax.csv'
beam_network_csv = work_dir + '/sfbay/runs/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/network.csv.gz'
#
regional_npmrds_station_geo_input = input_dir + '/regional_npmrds_station_map.geojson'
regional_npmrds_data_input = input_dir + '/regional_npmrds_data.csv'
npmrds_hourly_speed_input = input_dir + '/npmrds_hourly_speeds.csv'
beam_network_car_links_geo_input = input_dir + '/beam_network_car_links_map.geojson'
beam_npmrds_network_map_geo_input = input_dir + '/beam_npmrds_network_map.geojson'
npmrds_hourly_speed_by_road_class_input = input_dir + '/npmrds_hourly_speed_by_road_class.csv'

st = time.time()

print("Generating block groups boundaries for San Francisco only")
collect_geographic_boundaries(state, ['075'], year, work_dir + "/sfbay/input/sf_cbgs.geojson", geo_level='cbgs')

print("Generating block groups boundaries")
collect_geographic_boundaries(state, ['075'], year, npmrds_cbgs_geo_path_output, geo_level='cbgs')

print("Generating block groups boundaries")
collect_geographic_boundaries(state, fips_code, year, npmrds_counties_geo_path_output, geo_level='cbgs')

# Either download boundaries directly
print("Generating counties boundaries")
region_boundary = collect_geographic_boundaries(state, fips_code, year, npmrds_counties_geo_path_output, geo_level='counties')
# or load it
# npmrds_geo_path_path = gpd.read_file(npmrds_geo).to_crs(epsg=4326)

regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
    region_boundary=region_boundary,
    npmrds_geo_input=npmrds_geo,
    npmrds_data_csv_input=npmrds_data_csv,
    npmrds_label="NPMRDS_2018",
    distance_buffer_m=20,
    beam_network_csv_input=beam_network_csv,
    projected_crs_epsg=26910,
    regional_npmrds_station_output=regional_npmrds_station_geo_input,
    regional_npmrds_data_output=regional_npmrds_data_input,
    npmrds_hourly_speed_output=npmrds_hourly_speed_input,
    beam_network_car_links_geo_output=beam_network_car_links_geo_input,
    beam_npmrds_network_map_geo_output=beam_npmrds_network_map_geo_input,
    npmrds_hourly_speed_by_road_class_output=npmrds_hourly_speed_by_road_class_input,
    npmrds_observed_speed_weight=0.5)

# ########## Checking Network
print("Plotting region boundaries and stations")
plt.figure()
fig, ax = plt.subplots()
region_boundary.boundary.plot(ax=ax, color='black')
regional_npmrds_station.plot(ax=ax, color='blue')
plt.title("Region Boundaries and NPMRDS Stations")
fig.savefig(plots_dir + '/regional_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print("Plotting BEAM Network and NPMRDS stations")
plt.figure()
fig, ax = plt.subplots()
regional_npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
beam_npmrds_network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
plt.title("BEAM Network and NPMRDS Stations")
fig.savefig(plots_dir + '/regional_beam_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print(f"Execution time of prepare_npmrds_data: {(time.time() - st) / 60.0}min")
