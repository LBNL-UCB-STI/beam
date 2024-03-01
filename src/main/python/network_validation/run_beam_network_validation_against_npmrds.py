import seaborn as sns

from validation_utils import *

work_dir = os.path.expanduser("~/Workspace/Data/Scenarios")

setup = SpeedValidationSetup(project_dir_path=work_dir + '/SFBay',
                             npmrds_label="NPMRDS_2018",
                             npmrds_geo_path=work_dir + "/SFBay/NPMRDS_data/California.shp",
                             npmrds_data_csv_path=work_dir + '/SFBay/NPMRDS_data/al_ca_oct2018_1hr_trucks_pax.csv',
                             region_boundary_geo_path=work_dir + '/SFBay/NPMRDS_data/SF_counties.geojson',
                             projected_crs_epsg=26910,
                             beam_network_csv_path=work_dir + '/SFBay/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/network.csv.gz',
                             link_stats_paths_and_labels_list=[
                                 ("BEAM_2024",
                                  work_dir + "/SFBay/sfbay-simp-jdeq-0.07__2024-02-21_19-22-50_obb/10.linkstats.csv.gz")],
                             demand_sample_size=0.1,
                             assume_daylight_saving=True)

# ########## Initialize
setup.init_npmrds_and_beam_data()

# ########## Checking Network
print("Plotting region boundaries and stations")
plt.figure()
fig, ax = plt.subplots()
setup.region_boundary.boundary.plot(ax=ax, color='black')
setup.regional_npmrds_station.plot(ax=ax, color='blue')
plt.title("Region Boundaries and NPMRDS Stations")
fig.savefig(setup.plots_dir + '/regional_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
plt.show(block=False)
# plt.close(fig)

# #########################################
# ########## Network-level speed validation
# #########################################
hourly_speed, hourly_speed_by_road_class = setup.prepare_data_for_hourly_average_speed_validation()

# Plot hourly network speed
plt.figure()
sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_speed, errorbar=('ci', 95))
plt.ylim([0, 70])
plt.title("Network-level Speed Validation")
plt.savefig(setup.plots_dir + '/beam_npmrds_network_speed_validation.png', dpi=200)
plt.show(block=False)

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
plt.savefig(setup.plots_dir + '/beam_npmrds_network_speed_road_class_validation.png', dpi=200)
plt.show(block=False)

# ######################################
# ########## Link-level speed validation
# ######################################
hourly_link_speed, hourly_link_speed_by_road_class = setup.prepare_data_for_hourly_link_speed_validation(
    distance_buffer_m=20, rerun_network_matching=False)

# Plot hourly link speed
plt.figure()
sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_link_speed, errorbar=('ci', 95))
plt.ylim([0, 70])
plt.title("Link-level Speed Validation")
plt.savefig(setup.plots_dir + '/beam_npmrds_link_speed_validation.png', dpi=200)
plt.show(block=False)

# Plot hourly link speed by road class
plt.figure()
g = sns.relplot(x='hour', y='speed', hue='road_class', col='scenario', kind="line",
                data=hourly_link_speed_by_road_class,
                errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True})
g.set_titles("{col_name}")
g.fig.suptitle('Link-Level Speed Validation by Road Class', fontsize=16, y=0.98)
g.set_xlabels("Hour")
g.set_ylabels("Speed (mph)")
g._legend.set_title("Road Category")
plt.subplots_adjust(top=0.85)
plt.ylim([0, 70])
plt.savefig(setup.plots_dir + '/beam_npmrds_link_speed_road_class_validation.png', dpi=200)
plt.show(block=False)

print("Plotting BEAM Network and NPMRDS stations")
plt.figure()
fig, ax = plt.subplots()
setup.regional_npmrds_station.plot(ax=ax, color='blue', linewidth=2)
setup.beam_npmrds_network_map.plot(ax=ax, color='red', linewidth=0.5)
plt.title("BEAM Network and NPMRDS Stations")
fig.savefig(setup.plots_dir + '/regional_beam_npmrds_network.png', dpi=300)  # Adjust dpi for resolution
plt.show(block=False)

plt.show()
