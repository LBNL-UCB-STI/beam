import os
from pathlib import Path
from validation_utils import *

# In Mac, you might need to cd to folder '/Applications/Python {version}'
# and run ./Install\ Certificates.command
#
# To prepare data for a new study area, make sure to change the following configuration variables
# Example of preparing SFBay data (9 counties + Santa Cruz and Yolo)
# ## study_area = "sfbay"
# ## year = 2018
# ## state = 'CA'
# ## fips_code = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']
# ## npmrds_geo_path_path = run_dir + "/input/sfbay_counties.geojson"
#
# The following need to be set/added manually
study_area = "sfbay"
study_area_dir = os.path.expanduser("~/Workspace/Data/Scenarios") + "/" + study_area
year = 2018
state = 'CA'
fips_code = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']
npmrds_raw_geo = study_area_dir + "/validation_data/NPMRDS/California.shp"
npmrds_raw_data_csv = study_area_dir + '/validation_data/NPMRDS/al_ca_oct2018_1hr_trucks_pax.csv'
beam_network_csv = study_area_dir + '/validation_data/BEAM/sfbay_residential_psimpl_network.csv.gz'

# The following will be generated automatically
study_area_counties_geo = study_area_dir + "/zones/sfbay_counties.geojson"
study_area_cbgs_geo = study_area_dir + "/zones/sfbay_cbgs.geojson"
#
npmrds_station_geo = study_area_dir + '/validation_data/NPMRDS/npmrds_station.geojson'
npmrds_data_csv = study_area_dir + '/validation_data/NPMRDS/npmrds_data.csv'
npmrds_hourly_speed_csv = study_area_dir + '/validation_data/NPMRDS/npmrds_hourly_speeds.csv'
npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation_data/NPMRDS/npmrds_hourly_speed_by_road_class.csv'
#
first_dot_index = beam_network_csv.find('.')
beam_network_prefix = beam_network_csv[:first_dot_index] if first_dot_index != -1 else beam_network_csv
beam_network_car_links_geo = beam_network_prefix + '_car_only.geojson'
beam_network_mapped_to_npmrds_geo = beam_network_prefix + '_mapped_to_npmrds.geojson'

st = time.time()

print("Generating block groups boundaries")
if os.path.exists(study_area_counties_geo):
    region_boundary = gpd.read_file(study_area_counties_geo)
else:
    region_boundary = collect_geographic_boundaries(state, fips_code, year, study_area_counties_geo)
    # Either download boundaries directly
    collect_geographic_boundaries(state, fips_code, year, study_area_cbgs_geo, geo_level='cbg')
    # collect_geographic_boundaries(state, ['075'], year, study_area_dir + "/zones/sf_cbgs.geojson", geo_level='cbgs')
    # or load it
    # npmrds_geo_path_path = gpd.read_file(npmrds_geo).to_crs(epsg=4326)


regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
    # input
    npmrds_label="NPMRDS_2018",
    npmrds_raw_geo=npmrds_raw_geo,
    npmrds_raw_data_csv=npmrds_raw_data_csv,
    npmrds_observed_speed_weight=0.5,
    region_boundary=region_boundary,
    beam_network_csv_input=beam_network_csv,
    projected_crs_epsg=26910,
    distance_buffer_m=20,
    # output
    npmrds_station_geo=npmrds_station_geo,
    npmrds_data_csv=npmrds_data_csv,
    npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
    npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
    beam_network_car_links_geo=beam_network_car_links_geo,
    beam_npmrds_network_map_geo=beam_network_mapped_to_npmrds_geo)

# ########## Checking Network
print("Plotting region boundaries and stations")
plt.figure()
fig, ax = plt.subplots()
region_boundary.boundary.plot(ax=ax, color='black')
regional_npmrds_station.plot(ax=ax, color='blue')
plt.title("Region Boundaries and NPMRDS Stations")
fig.savefig(os.path.splitext(npmrds_station_geo)[0] + ".png", dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print("Plotting BEAM Network and NPMRDS stations")
plt.figure()
fig, ax = plt.subplots()
regional_npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
beam_npmrds_network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
plt.title("BEAM Network and NPMRDS Stations")
fig.savefig(os.path.splitext(beam_network_mapped_to_npmrds_geo)[0] + ".png", dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print(f"Execution time of prepare_npmrds_data: {(time.time() - st) / 60.0}min")
