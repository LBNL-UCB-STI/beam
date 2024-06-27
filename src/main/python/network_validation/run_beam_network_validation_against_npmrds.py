import seaborn as sns
from pathlib import Path
from validation_utils import *

# beam run i.e. link stats and events file
# run_dir = os.path.expanduser("~/Workspace/Data/FREIGHT/seattle/beam/runs/2024-04-20/Baseline")
# link_stats = [LinkStats(scenario="BEAM", demand_fraction=0.3, file_path=run_dir + "/0.linkstats.csv.gz")]
run_dir = os.path.expanduser("~/Workspace/Data/Scenarios/sfbay/runs/sfbay-calib--rps-jdeq-081025__2024-04-08_08-26-12_fvf")
link_stats = [LinkStats(scenario="BEAM_081025", demand_fraction=0.1, file_path=run_dir + "/11.linkstats.csv.gz")]


# validation data
# study_area = "seattle"
# study_area_dir = os.path.expanduser("~/Workspace/Data/FREIGHT/seattle")
# beam_network_car_links_geo = study_area_dir + '/validation_data/BEAM/seattle_unclassified_simplified_network_car_only.geojson'
# beam_network_mapped_to_npmrds_geo = study_area_dir + '/validation_data/BEAM/seattle_unclassified_simplified_network_mapped_to_npmrds.geojson'
study_area = "sfbay"
study_area_dir = os.path.expanduser("~/Workspace/Data/Scenarios/sfbay")
beam_network_car_links_geo = study_area_dir + '/validation_data/BEAM/' + study_area + '_residential_psimpl_network_car_only.geojson'
beam_network_mapped_to_npmrds_geo = study_area_dir + '/validation_data/BEAM/' + study_area + '_residential_psimpl_network_mapped_to_npmrds.geojson'

#
# npmrds_station_geo = study_area_dir + '/validation_data/NPMRDS/seattle_npmrds_station.geojson'
# npmrds_data_csv = study_area_dir + '/validation_data/NPMRDS/seattle_npmrds_data.csv'
# npmrds_hourly_speed_csv = study_area_dir + '/validation_data/NPMRDS/seattle_npmrds_hourly_speeds.csv'
# npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation_data/NPMRDS/seattle_npmrds_hourly_speed_by_road_class.csv'
npmrds_station_geo = study_area_dir + '/validation_data/NPMRDS/' + study_area + '_npmrds_station.geojson'
npmrds_data_csv = study_area_dir + '/validation_data/NPMRDS/' + study_area + '_npmrds_data.csv'
npmrds_hourly_speed_csv = study_area_dir + '/validation_data/NPMRDS/' + study_area + '_npmrds_hourly_speeds.csv'
npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation_data/NPMRDS/' + study_area + '_npmrds_hourly_speed_by_road_class.csv'


print("Run: " + run_dir)

# The rest is automatically generated
output_dir = run_dir + '/validation_output'
plots_dir = output_dir + '/plots'
Path(output_dir).mkdir(parents=True, exist_ok=True)
Path(plots_dir).mkdir(parents=True, exist_ok=True)

# ########## Initialize
setup = SpeedValidationSetup(link_stats=link_stats,
                             npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
                             npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
                             beam_network_mapped_to_npmrds_geo=beam_network_mapped_to_npmrds_geo,
                             assume_daylight_saving=True)

# #########################################
# ########## Network-level speed validation
# #########################################
hourly_speed = setup.get_hourly_average_speed()

# Plot hourly network speed
plt.figure()
sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_speed, errorbar=('ci', 95))
plt.ylim([0, 70])
plt.title("Network-level Speed Validation")
plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_network_speed_validation.png', dpi=200)
plt.show(block=False)

hourly_speed_by_road_class = setup.get_hourly_average_speed_by_road_class()

# plot hourly network speed by road class
plt.figure()
g = sns.relplot(x='hour', y='speed', hue='road_class', col='scenario', kind="line",
                data=hourly_speed_by_road_class,
                errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True})
g.set_titles("{col_name}")
g.fig.suptitle('Network-level Speed Validation by Road Class', fontsize=16, y=0.98)
g.set_xlabels("Hour")
g.set_ylabels("Speed (mph)")
g._legend.set_title("Road Category")
plt.subplots_adjust(top=0.85)
plt.ylim([0, 70])
plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.png', dpi=200)
plt.show(block=False)

hourly_speed_by_road_class.to_csv(output_dir + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.csv', index=False)

# ######################################
# ########## Link-level speed validation
# ######################################
hourly_link_speed = setup.get_hourly_link_speed()

# Plot hourly link speed
plt.figure()
sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_link_speed, errorbar=('ci', 95))
plt.ylim([0, 70])
plt.title("Link-level Speed Validation")
plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_link_speed_validation.png', dpi=200)
plt.show(block=False)

hourly_link_speed_by_road_class = setup.get_hourly_link_speed_by_road_class()

# Plot hourly link speed by road class
plt.figure()
road_class_order = list(fsystem_to_roadclass_lookup.values())
g = sns.relplot(x='hour', y='speed', hue='road_class', col='scenario', kind="line", hue_order=road_class_order,
                data=hourly_link_speed_by_road_class,
                errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True})
g.set_titles("{col_name}")
g.fig.suptitle('Link-Level Speed Validation by Road Class', fontsize=16, y=0.98)
g.set_xlabels("Hour")
g.set_ylabels("Speed (mph)")
g._legend.set_title("Road Category")
plt.subplots_adjust(top=0.85)
plt.ylim([0, 70])
plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.png', dpi=200)
plt.show(block=False)

hourly_link_speed_by_road_class.to_csv(output_dir + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.csv', index=False)

print("END")
