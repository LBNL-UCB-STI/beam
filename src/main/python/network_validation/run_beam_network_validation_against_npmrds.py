from validation_utils import *
from pathlib import Path


work_dir = os.path.expanduser("~/Workspace/Simulation")
# beam run i.e. link stats and events file
# study_area = "seattle"
study_area = "sfbay"

study_area_dir = os.path.join(work_dir, study_area)

study_area_beam_network_dir = f"{study_area}_simple_no_local"
beam_network_mapped_to_npmrds_geo = os.path.join(
    study_area_dir,
    'validation/beam',
    study_area_beam_network_dir,
    f'{study_area_beam_network_dir}_network_mapped_to_npmrds.geojson'
)

# run_dir = os.path.expanduser("~/Workspace/Simulation/seattle/beam/runs/2024-04-20/Baseline")
batch = "2024-11-06"
batch_label = batch.replace("-", "")
scenario = "2018_Baseline"
scenario_label = scenario.replace("_", "-")
run_dir = study_area_dir + f"/beam-runs/{batch}/{scenario}/"
link_stats = [
    LinkStats(scenario=f"{batch}_{scenario_label}", demand_fraction=0.1,
              file_path=os.path.join(run_dir, "0.linkstats.csv.gz"))
]
vehicle_types_files = [(
    batch_label,
    scenario_label,
    study_area_dir + f"/beam-runs/{batch}/{scenario}/0.events.csv.gz",
    study_area_dir + f"/beam-freight/{batch}/{scenario}/vehicle-tech/ft-vehicletypes--{batch_label}--{scenario_label}.csv"
)]

run_link_speed_validation = False
run_network_speed_validation = False
run_vmt_validation = False

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

if run_link_speed_validation or run_network_speed_validation or run_vmt_validation:
    # The rest is automatically generated
    output_dir = run_dir + '/validation_output'
    plots_dir = output_dir + '/plots'
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    Path(plots_dir).mkdir(parents=True, exist_ok=True)
    # link_stats = [LinkStats(scenario="BEAM", demand_fraction=0.3, file_path=run_dir + "/0.linkstats.csv.gz")]
    print("Run: " + str(link_stats))
    processed_link_stats = setup.process_these_link_stats(link_stats=link_stats, assume_daylight_saving=True)
else:
    processed_link_stats = None
    output_dir = ''
    plots_dir = ''

# #########################################
# ########## Network-level speed validation
# #########################################
if run_network_speed_validation:
    hourly_speed = setup.get_hourly_average_speed(processed_link_stats)

    # Calculate average speed for the whole network
    average_network_speed = hourly_speed.groupby('scenario')['speed'].mean()
    print("Average network speed:")
    print(average_network_speed)

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
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.png', dpi=200)
    plt.show(block=False)

    hourly_speed_by_road_class.to_csv(
        output_dir + '/' + study_area + '_beam_npmrds_network_speed_road_class_validation.csv', index=False)
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
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(plots_dir + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.png', dpi=200)
    plt.show(block=False)

    hourly_link_speed_by_road_class.to_csv(
        output_dir + '/' + study_area + '_beam_npmrds_link_speed_road_class_validation.csv', index=False)

# Plot average link speed by link
# average_link_speed = setup.get_average_link_speed()
# average_link_speed.to_csv(output_dir + '/' + study_area + '_average_link_speed.csv', index=False)


if run_vmt_validation:
    pts = pd.DataFrame()
    # Read vehicle types
    for (batch, scenario, events_file, veh_types_file) in vehicle_types_files:
        # Read and process freight events
        run = read_events(
            events_file,
            veh_types_file,
            batch,
            scenario
        )
        pt = get_ft_path_traversals(run)
        pts = pd.concat([pts, pt])

    # Process baseline data
    baseline_summary, baseline_summary_levels, baseline_summary_colors = process_ft_path_traversals(baseline_runs,
                                                                                                    baseline_runs_name,
                                                                                                    baseline_output_dir)

    # Validate VMT
    validation = validate_vmt(baseline_summary, WORK_DIR)

    # Create plots
    plot_results(
        baseline_summary,
        validation,
        baseline_summary_colors,
        baseline_output_dir,
        "2024-08-07"
    )

print("END")
