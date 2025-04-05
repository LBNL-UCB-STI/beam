from validation_utils import *
from _data_collection_utils import collect_geographic_boundaries
import sys

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name

# In Mac, you might need to cd to folder '/Applications/Python {version}'
# and run ./Install\ Certificates.command
#
# To prepare data for a new study area, make sure to change the following configuration variables
# Example of preparing SFBay data (9 counties + Santa Cruz and Yolo)
study_area = "sfbay"
batch = "2024-01-23"
scenario = "2018_Baseline"
config = get_area_config(study_area)
config["network"]["graph_layers"]["residential"]["min_density_per_km2"] = 5500

config_network = config["network"]
config_npmrds = config_network["validation"]["npmrds"]
config_geo = config["geo"]

network_name = generate_network_name(config)
network_dir = f'{config["work_dir"]}/network/{network_name}'
osm_pbf_path = os.path.expanduser(f"{network_dir}/{network_name}.osm.pbf")

output_dir = f"{config["work_dir"]}/beam-runs/{batch}/{scenario}"

# census_year = 2018
# state_fips = '06'
# study_area_crs = 26910
# study_area_fips = ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113']

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
# # The following will be generated automatically
# study_area_county_geo = study_area_dir + "/geo/" + study_area + "_counties.geojson"
# study_area_cbg_geo = study_area_dir + "/geo/" + study_area + "_cbgs.geojson"
# # study_area_taz_geo = study_area_dir + "/geo/" + study_area + "_tazs.geojson"
# # study_area_taz_id = "TAZCE10"
# study_area_cbg_taz_map_csv = study_area_dir + "/geo/" + study_area + "_cbg_taz_map.csv"
# #
# npmrds_station_geo = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_station.geojson"
# npmrds_data_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_data.csv"
# npmrds_hourly_speed_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_hourly_speeds.csv"
# npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation/npmrds/' + study_area + "_npmrds_hourly_speed_by_road_class.csv"
# #
# first_dot_index = study_area_beam_network_csv.find('.')
# beam_network_prefix = study_area_beam_network_csv[
#                       :first_dot_index] if first_dot_index != -1 else study_area_beam_network_csv
# beam_network_car_links_geo = beam_network_prefix + '_car_only.geojson'
# beam_network_mapped_to_npmrds_geo = beam_network_prefix + '_mapped_to_npmrds.geojson'

st = time.time()

region_boundary_wgs84 = collect_geographic_boundaries(
        config["state_fips"],
        config["county_fips"],
        config["census_year"],
        study_area,
        geo_level='county',
        work_dir=f'{config["work_dir"]}/geo'
    )

# sf_cbg_geo = study_area_dir + "/zones/sf_cbgs.geojson"
# if os.path.exists(sf_cbg_geo):
#     print("Loading sf boundaries...")
#     region_boundary_wgs84 = gpd.read_file(sf_cbg_geo)
# else:
#     print("Downloading sf boundaries...")
#     region_boundary_wgs84 = collect_geographic_boundaries(state_fips, ['075'], cbg_year, sf_cbg_geo,
#                                                           projected_coordinate_system, geo_level='cbg')

cbg_boundary_wgs84 = collect_geographic_boundaries(
    config["state_fips"],
    config["county_fips"],
    config["census_year"],
    study_area,
    geo_level='county',
    work_dir=f'{config["work_dir"]}/geo'
)

# taz_boundary_wgs84 = gpd.read_file(f"{config['work_dir']}/{config_geo["taz_shp"]}").to_crs(epsg=4326)
# map_cbg_to_taz(
#     cbg_boundary_wgs84,
#     config_geo["cbg_id"],
#     taz_boundary_wgs84,
#     config_geo["taz_id"],
#     config_geo["utm_epsg"],
#     study_area_cbg_taz_map_csv
# )

regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
    # input
    npmrds_label=f"NPMRDS_{config_npmrds["year"]}",
    npmrds_raw_geo=f"{config['work_dir']}/{config_npmrds["geo"]}",
    npmrds_raw_data_csv=f'{config['work_dir']}/{config_npmrds["data"]}',
    npmrds_observed_speed_weight=0.5,
    region_boundary=region_boundary_wgs84,
    beam_network_csv_input=f"{network_dir}/network.csv.gz",
    projected_crs_epsg=config_geo["utm_epsg"],
    distance_buffer_m=20,
    # output
    npmrds_station_geo=f"{output_dir}/{study_area}_npmrds_station.geojson",
    npmrds_data_csv=f"{output_dir}/{study_area}_npmrds_data.csv",
    npmrds_hourly_speed_csv=f"{output_dir}/{study_area}_npmrds_hourly_speeds.csv",
    npmrds_hourly_speed_by_road_class_csv=f"{output_dir}/{study_area}_npmrds_hourly_speed_by_road_class.csv",
    beam_network_car_links_geo=f"{output_dir}/{study_area}_network_car_only.geojson",
    beam_npmrds_network_map_geo=f"{output_dir}/{study_area}_network_mapped_to_npmrds.geojson")

# ########## Checking Network
print("Plotting region boundaries and stations")
plt.figure()
fig, ax = plt.subplots()
region_boundary_wgs84.boundary.plot(ax=ax, color='black')
regional_npmrds_station.plot(ax=ax, color='blue')
plt.title("Region Boundaries and NPMRDS Stations")
fig.savefig(f"{output_dir}/{study_area}_npmrds_station.png", dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print("Plotting BEAM Network and NPMRDS stations")
plt.figure()
fig, ax = plt.subplots()
regional_npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
beam_npmrds_network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
plt.title("BEAM Network and NPMRDS Stations")
fig.savefig(f"{output_dir}/{study_area}_network_mapped_to_npmrds.png", dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

print(f"Execution time of prepare_npmrds_data: {((time.time() - st) / 60.0):.2f}min")