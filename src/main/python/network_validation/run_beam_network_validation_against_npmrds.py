import seaborn as sns
from pathlib import Path
from validation_utils import *

work_dir = os.path.expanduser("~/Workspace/Simulation")
# beam run i.e. link stats and events file
# study_area = "seattle"
study_area = "sfbay"

study_area_dir = work_dir + "/" + study_area

# beam_network_mapped_to_npmrds_geo = study_area_dir + '/validation/beam/' + study_area + '_unclassified_simplified_network_mapped_to_npmrds.geojson'
beam_network_mapped_to_npmrds_geo = study_area_dir + '/validation/beam/' + study_area + '_residential_simpl_network_mapped_to_npmrds.geojson'


# validation data
# npmrds_station_geo = study_area_dir + '/validation/npmrds/seattle_npmrds_station.geojson'
# npmrds_data_csv = study_area_dir + '/validation/npmrds/seattle_npmrds_data.csv'
# npmrds_hourly_speed_csv = study_area_dir + '/validation/npmrds/seattle_npmrds_hourly_speeds.csv'
# npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation/npmrds/' + study_area + '_npmrds_hourly_speed_by_road_class.csv'
npmrds_station_geo = study_area_dir + '/validation/npmrds/' + study_area + '_npmrds_station.geojson'
npmrds_data_csv = study_area_dir + '/validation/npmrds/' + study_area + '_npmrds_data.csv'
npmrds_hourly_speed_csv = study_area_dir + '/validation/npmrds/' + study_area + '_npmrds_hourly_speeds.csv'
npmrds_hourly_speed_by_road_class_csv = study_area_dir + '/validation/npmrds/' + study_area + '_npmrds_hourly_speed_by_road_class.csv'



# ########## Initialize
setup = SpeedValidationSetup(npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
                             npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
                             beam_network_mapped_to_npmrds_geo=beam_network_mapped_to_npmrds_geo)


run_link_speed_validation = False
run_network_speed_validation = False

if run_link_speed_validation or run_network_speed_validation:
    # The rest is automatically generated
    # run_dir = os.path.expanduser("~/Workspace/Simulation/seattle/beam/runs/2024-04-20/Baseline")
    run_dir = study_area_dir + "/beam-runs/calibration-jdeqsim/sfbay-calib--rs-101010-netset5__2024-07-09_04-48-50_tww"
    output_dir = run_dir + '/validation_output'
    plots_dir = output_dir + '/plots'
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    Path(plots_dir).mkdir(parents=True, exist_ok=True)
    link_stats = [
        LinkStats(scenario="BEAM_netset1", demand_fraction=0.1, file_path=run_dir + "/12.linkstats.csv.gz")
    ]
    # link_stats = [LinkStats(scenario="BEAM", demand_fraction=0.3, file_path=run_dir + "/0.linkstats.csv.gz")]
    print("Run: " + str(link_stats))
    processed_link_stats = setup.process_these_link_stats(link_stats=link_stats, assume_daylight_saving=True)
else:
    processed_link_stats = None

# #########################################
# ########## Network-level speed validation
# #########################################
if run_network_speed_validation:
    hourly_speed = setup.get_hourly_average_speed(processed_link_stats)

    # Plot hourly network speed
    plt.figure()
    sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_speed, errorbar=('ci', 95))
    plt.ylim([0, 70])
    plt.title("Network-level Speed Validation")
    plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_network_speed_validation.png', dpi=200)
    plt.show(block=False)

    hourly_speed_by_road_class = setup.get_hourly_average_speed_by_road_class(processed_link_stats)

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
if run_link_speed_validation:
    hourly_link_speed = setup.get_hourly_link_speed(processed_link_stats)

    # Plot hourly link speed
    plt.figure()
    sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_link_speed, errorbar=('ci', 95))
    plt.ylim([0, 70])
    plt.title("Link-level Speed Validation")
    plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_link_speed_validation.png', dpi=200)
    plt.show(block=False)

    hourly_link_speed_by_road_class = setup.get_hourly_link_speed_by_road_class(processed_link_stats)

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


# Plot average link speed by link
# average_link_speed = setup.get_average_link_speed()
# average_link_speed.to_csv(output_dir + '/' + study_area + '_average_link_speed.csv', index=False)

print("END")
