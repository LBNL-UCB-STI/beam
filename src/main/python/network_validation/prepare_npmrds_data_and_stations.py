from validation_utils import *

# In Mac, you might need to cd to folder '/Applications/Python {version}'
# and run ./Install\ Certificates.command
#
# To prepare data for a new study area, make sure to change the following configuration variables
# Example of preparing SFBay data (9 counties + Santa Cruz and Yolo)
census_year = 2018
state_fips = '06'
study_area = "sfbay"
study_area_crs = 26910
study_area_fips = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']

"beam/runs/calibration-jdeqsim"
study_area_dir = os.path.expanduser("~/Workspace/Data/Simulation") + "/" + study_area
study_area_taz_geo = study_area_dir + "/geo/shp/sfbay-tazs-epsg-26910.shp"
study_area_taz_id = "taz1454"
study_area_beam_network_csv = study_area_dir + '/validation/beam/sfbay_residential_psimpl_network.csv.gz'
npmrds_data_label = "NPMRDS_2018"
npmrds_raw_geo = study_area_dir + "/validation/npmrds/California.shp"
npmrds_raw_data_csv = study_area_dir + '/validation/npmrds/al_ca_oct2018_1hr_trucks_pax.csv'
#
# The following need to be set/added manually
# census_year = 2018
# state_fips = '53'
# study_area = "seattle"
# study_area_crs = 32048
# study_area_fips = ["061", "033", "035", "053"]
# study_area_dir = os.path.expanduser("~/Workspace/Data/FREIGHT") + "/" + study_area
# study_area_taz_geo = study_area_dir + "/zones/shp/block-groups-32048.shp"
# study_area_taz_id = "OBJECTID"
# study_area_beam_network_csv = study_area_dir + '/validation_data/BEAM/seattle_unclassified_simplified_network.csv.gz'
# npmrds_data_label = "NPMRDS_2018"
# npmrds_raw_geo = study_area_dir + "/validation_data/NPMRDS/Washington.shp"
# npmrds_raw_data_csv = study_area_dir + '/validation_data/NPMRDS/vt_wi_2018_1hr.csv'
#
#
#
#
#
#
# The following will be generated automatically
study_area_county_geo = study_area_dir + "/geo/" + study_area + "_counties.geojson"
study_area_cbg_geo = study_area_dir + "/geo/" + study_area + "_cbgs.geojson"
study_area_cbg_id = "GEOID"
# study_area_taz_geo = study_area_dir + "/geo/" + study_area + "_tazs.geojson"
# study_area_taz_id = "TAZCE10"
study_area_cbg_taz_map_csv = study_area_dir + "/geo/" + study_area + "_cbg_taz_map.csv"
#
npmrds_station_geo = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_station.geojson"
npmrds_data_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_data.csv"
npmrds_hourly_speed_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_hourly_speeds.csv"
npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_hourly_speed_by_road_class.csv"
#
first_dot_index = study_area_beam_network_csv.find('.')
beam_network_prefix = study_area_beam_network_csv[:first_dot_index] if first_dot_index != -1 else study_area_beam_network_csv
beam_network_car_links_geo = beam_network_prefix + '_car_only.geojson'
beam_network_mapped_to_npmrds_geo = beam_network_prefix + '_mapped_to_npmrds.geojson'

st = time.time()

if os.path.exists(study_area_county_geo):
    print("Loading county boundaries...")
    region_boundary_wgs84 = gpd.read_file(study_area_county_geo)
else:
    print("Downloading county boundaries...")
    region_boundary_wgs84 = collect_geographic_boundaries(state_fips, study_area_fips, census_year, study_area_county_geo,
                                                          study_area_crs, geo_level='county')

# sf_cbg_geo = study_area_dir + "/zones/sf_cbgs.geojson"
# if os.path.exists(sf_cbg_geo):
#     print("Loading sf boundaries...")
#     region_boundary_wgs84 = gpd.read_file(sf_cbg_geo)
# else:
#     print("Downloading sf boundaries...")
#     region_boundary_wgs84 = collect_geographic_boundaries(state_fips, ['075'], cbg_year, sf_cbg_geo,
#                                                           projected_coordinate_system, geo_level='cbg')

if os.path.exists(study_area_cbg_geo):
    print("Loading block groups boundaries...")
    cbg_boundary_wgs84 = gpd.read_file(study_area_cbg_geo)
else:
    print("Downloading block groups boundaries...")
    cbg_boundary_wgs84 = collect_geographic_boundaries(state_fips, study_area_fips, census_year, study_area_cbg_geo,
                                                       study_area_crs, geo_level='cbg')

if os.path.exists(study_area_taz_geo):
    print("Loading taz boundaries...")
    taz_boundary_wgs84 = gpd.read_file(study_area_taz_geo).to_crs(epsg=4326)
else:
    print("Downloading taz boundaries...")
    taz_boundary_wgs84 = None
    # TODO We need to fix the source of the TAZ data
    # taz_boundary_wgs84 = collect_geographic_boundaries(state_fips, study_area_fips, 2011, study_area_taz_geo,
    #                                                    projected_coordinate_system, geo_level='taz')

if taz_boundary_wgs84 is not None:
    print("Mapping block groups to taz boundaries.")
    map_cbg_to_taz(cbg_boundary_wgs84, study_area_cbg_id, taz_boundary_wgs84, study_area_taz_id, study_area_crs, study_area_cbg_taz_map_csv)

regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
    # input
    npmrds_label=npmrds_data_label,
    npmrds_raw_geo=npmrds_raw_geo,
    npmrds_raw_data_csv=npmrds_raw_data_csv,
    npmrds_observed_speed_weight=0.5,
    region_boundary=region_boundary_wgs84,
    beam_network_csv_input=study_area_beam_network_csv,
    projected_crs_epsg=study_area_crs,
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
region_boundary_wgs84.boundary.plot(ax=ax, color='black')
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
